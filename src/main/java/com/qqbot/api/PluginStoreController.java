package com.qqbot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.install.PluginInstaller;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.PluginRegistry;
import com.qqbot.plugin.store.PluginStoreClient;
import com.qqbot.plugin.store.StoreCache;
import com.qqbot.plugin.store.StoreIndex;
import com.qqbot.plugin.store.StorePlugin;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商店前台（设计文档 §7.3）。
 *
 * <p>预览与安装必须是两次请求：预览只下载和校验、绝不写库；安装读同一份缓存包，
 * 再比对一次 sha256，保证「用户看到的」和「装进去的」是同一个字节流。
 */
@RestController
@RequestMapping("/api/store")
public class PluginStoreController {

    private final PluginStoreClient store;
    private final StoreCache cache;
    private final PluginInstaller installer;
    private final PluginRegistry registry;
    private final PluginProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public PluginStoreController(PluginStoreClient store, StoreCache cache,
                                 PluginInstaller installer, PluginRegistry registry,
                                 PluginProperties props, ObjectMapper mapper) {
        this.store = store;
        this.cache = cache;
        this.installer = installer;
        this.registry = registry;
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(3000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", store.enabled());
        out.put("url", store.baseUrl());
        out.put("hostVersion", props.getHostVersion());
        out.put("signatureRequired", store.signatureRequired());
        out.put("signatureConfigured", store.signatureConfigured());
        out.put("signatureActive", store.signatureActive());
        // 前台据此提示「商店不可用：先配公钥」，而不是只显示一个空列表
        out.put("needsPublicKey", store.signatureRequired() && !store.signatureConfigured());
        out.put("semanticEnabled", props.isSemanticEnabled());
        out.put("canPublish", store.enabled());
        out.put("canReview", canReview());
        StoreIndex idx = cache.current();
        out.put("cached", idx != null);
        out.put("count", idx == null || idx.plugins() == null ? 0 : idx.plugins().size());
        long age = cache.ageMillis();
        out.put("ageSeconds", age < 0 ? null : Duration.ofMillis(age).toSeconds());
        return out;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        requireEnabled();
        try {
            StoreIndex idx = store.fetchNow();
            return Map.of("ok", true, "count", idx.plugins() == null ? 0 : idx.plugins().size());
        } catch (PluginStoreClient.StoreUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @GetMapping("/plugins")
    public Map<String, Object> plugins(@RequestParam(required = false) String hostVersion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", store.enabled());
        if (!store.enabled()) {
            out.put("available", false);
            out.put("reason", "未配置商店地址（qqbot.plugin.store.url）");
            out.put("plugins", List.of());
            return out;
        }

        StoreIndex index;
        try {
            index = store.index();
        } catch (PluginStoreClient.StoreUnavailableException e) {
            out.put("available", false);
            out.put("reason", "商店暂不可用：" + e.getMessage());
            out.put("plugins", List.of());
            return out;
        }

        String host = hostVersion == null || hostVersion.isBlank() ? props.getHostVersion() : hostVersion;
        List<Map<String, Object>> items = new ArrayList<>();
        for (StorePlugin p : index.plugins() == null ? List.<StorePlugin>of() : index.plugins()) {
            if (!compatible(host, p.minHostVersion(), p.apiVersion())) {
                continue;
            }
            InstalledPlugin installed = registry.find(p.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("summary", p.summary());
            m.put("latest", p.latest());
            m.put("runtime", p.runtime());
            m.put("intent", p.intent());
            m.put("tags", p.tags());
            m.put("downloads", p.downloads());
            m.put("updatedAt", p.updatedAt());
            m.put("size", p.size());
            m.put("sha256", p.sha256());
            m.put("installed", installed != null);
            m.put("installedVersion", installed == null ? null : installed.version());
            m.put("upgradable", installed != null && installed.version() != null
                    && compareVersion(p.latest(), installed.version()) > 0);
            items.add(m);
        }
        out.put("available", true);
        out.put("generatedAt", index.generatedAt());
        out.put("plugins", items);
        return out;
    }

    /** 预览：下载 + 全部校验，返回「装下去会发生什么」，不写任何状态。 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, String> body) {
        requireEnabled();
        String id = body.get("id");
        String version = body.get("version");
        if (id == null || version == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 id / version");
        }
        if (!id.matches("[a-z0-9][a-z0-9._-]{2,48}") || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id / version 格式不合法");
        }
        StorePlugin signed = signedEntry(id, version);
        if (signed == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商店索引里没有这个最新版本，请刷新商店");
        }

        PluginStoreClient.Package pkg;
        try {
            pkg = store.download(id, version);
        } catch (PluginStoreClient.StoreUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        // 关键：包体必须等于签名索引里声明的 sha256，而不是只信本次下载/响应头
        if (signed.sha256() == null || !signed.sha256().equalsIgnoreCase(pkg.sha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "包体与商店签名索引不一致，已拒绝；请刷新商店后重试");
        }
        PluginInstaller.InspectResult inspect = installer.inspect(pkg.bytes());
        try {
            Path dir = downloadDir();
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(cacheName(id, version));
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.write(tmp, pkg.bytes());
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(dir.resolve(cacheName(id, version) + ".sha"), signed.sha256());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "缓存包体失败: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", inspect.ok());
        out.put("pluginId", id);
        out.put("version", version);
        out.put("sha256", pkg.sha256());
        out.put("size", pkg.bytes().length);
        out.put("issues", inspect.issues());
        if (inspect.manifest() != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", inspect.manifest().id());
            m.put("name", inspect.manifest().name());
            m.put("version", inspect.manifest().version());
            m.put("runtime", inspect.manifest().runtime());
            m.put("description", inspect.manifest().description());
            m.put("intent", inspect.manifest().intent() == null ? null : inspect.manifest().intent().id());
            m.put("outbound", inspect.manifest().outboundHosts());
            m.put("memoryWrite", inspect.manifest().memoryPolicy());
            m.put("returnsAudio", inspect.manifest().returnsAudio());
            out.put("manifest", m);
        }
        return out;
    }

    /** 安装：读预览时缓存的那份包，比对 sha256 后再交给 installer。 */
    @PostMapping("/install")
    public Map<String, Object> install(@RequestBody Map<String, String> body) {
        requireEnabled();
        String id = body.get("id");
        String version = body.get("version");
        String expectedSha = body.get("sha256");
        if (id == null || version == null || expectedSha == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 id / version / sha256");
        }

        if (!id.matches("[a-z0-9][a-z0-9._-]{2,48}") || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id / version 格式不合法");
        }
        Path dir = downloadDir();
        Path file = dir.resolve(cacheName(id, version));
        Path shaFile = dir.resolve(cacheName(id, version) + ".sha");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
            String signedSha = Files.readString(shaFile).trim();
            String actual = PluginStoreClient.sha256(bytes);
            if (!actual.equalsIgnoreCase(signedSha)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "包体与签名索引不一致，请重新预览");
            }
            if (!actual.equalsIgnoreCase(expectedSha)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "包体已变化，请重新预览");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "找不到预览缓存，请重新预览");
        }

        PluginInstaller.InspectResult check = installer.inspect(bytes);
        if (!check.ok() || check.manifest() == null
                || !id.equals(check.manifest().id()) || !version.equals(check.manifest().version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "包内 manifest 与要安装的版本不一致");
        }
        PluginInstaller.InstallResult r = installer.install(bytes, "store");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("pluginId", r.pluginId());
        out.put("version", r.version());
        out.put("issues", r.issues());
        out.put("hint", r.ok() ? "装好了，去打开账号开关才会生效" : null);
        return out;
    }

    private void requireEnabled() {
        if (!store.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未配置商店地址");
        }
    }

    private Path downloadDir() {
        return cache.file().getParent().resolve("downloads");
    }

    private static String cacheName(String id, String version) {
        // 调用方已做格式校验；这里再兜一层，绝不让 id/version 拼出目录
        return id.replaceAll("[^a-z0-9._-]", "_") + "-"
                + version.replaceAll("[^0-9.]", "_") + ".zip";
    }

    /** 只信任签名索引里的最新版本与 sha256；这是包体校验的信任锚。 */
    private StorePlugin signedEntry(String id, String version) {
        try {
            StoreIndex idx = store.index();
            if (idx.plugins() == null) {
                return null;
            }
            for (StorePlugin p : idx.plugins()) {
                if (id.equals(p.id()) && version.equals(p.latest())
                        && p.sha256() != null && !p.sha256().isBlank()) {
                    return p;
                }
            }
        } catch (Exception ignored) {
            // 下面统一按“索引不可用”处理
        }
        return null;
    }

    private static boolean compatible(String host, String minHost, int apiVersion) {
        if (apiVersion != 1) {
            return false;
        }
        if (host == null || minHost == null) {
            return true;
        }
        return compareVersion(host, minHost) >= 0;
    }

    /** 够用的 semver 比较：只比数字段，预发布标识按字典序。 */
    static int compareVersion(String a, String b) {
        return com.qqbot.plugin.util.Versions.compare(a, b);
    }

    // ── 上架（任何部署实例都能投递，不需要账号）────────────────────────
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> publish(@RequestParam("file") MultipartFile file) {
        requireStore();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 zip 文件");
        }
        try {
            String boundary = "----nuanyu" + UUID.randomUUID().toString().replace("-", "");
            String filename = file.getOriginalFilename() == null ? "plugin.zip"
                    : file.getOriginalFilename().replace("\"", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(file.getBytes());
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(adminBaseUrl() + "/api/v1/publish/upload"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Publisher", publisherName())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            return readJson(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发布失败: " + e.getMessage());
        }
    }

    // ── 审核（只有配置了 admin-token 的实例可用）─────────────────────
    @GetMapping("/admin/pending")
    public Map<String, Object> adminPending() {
        return adminJson("GET", "/api/v1/admin/pending", null, null);
    }

    @PostMapping("/admin/review/{id}")
    public Map<String, Object> adminReview(@PathVariable long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String action = body == null ? "" : String.valueOf(body.getOrDefault("action", ""));
        String note = body == null ? "" : String.valueOf(body.getOrDefault("note", ""));
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("note", note);
            return adminJson("POST", "/api/v1/admin/review/" + id,
                    mapper.writeValueAsBytes(payload), "application/json");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "审核失败: " + e.getMessage());
        }
    }

    private boolean canReview() {
        String token = props.getStore().getAdminToken();
        return token != null && !token.isBlank();
    }

    private void requireStore() {
        if (!store.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置插件商店");
        }
    }

    private Map<String, Object> adminJson(String method, String path, byte[] body, String contentType) {
        requireStore();
        if (!canReview()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本实例未配置商店管理令牌");
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(adminBaseUrl() + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Admin-Token", props.getStore().getAdminToken());
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
                if (contentType != null) {
                    b.header("Content-Type", contentType);
                }
            }
            return readJson(http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "商店不可达: " + e.getMessage());
        }
    }

    /** 审核/发布代理优先走 A 的内网地址；普通部署没配，就用官方公网地址 */
    private String adminBaseUrl() {
        String admin = props.getStore().getAdminUrl();
        return admin == null || admin.isBlank() ? store.baseUrl() : admin.replaceAll("/+$", "");
    }

    private String publisherName() {
        String name = props.getStore().getPublisher();
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        if (name == null || name.isBlank()) {
            name = "nuanyu-instance";
        }
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpResponse<String> res) {
        Map<String, Object> out;
        try {
            out = mapper.readValue(res.body() == null ? "{}" : res.body(), Map.class);
        } catch (Exception e) {
            out = new LinkedHashMap<>();
        }
        if (res.statusCode() >= 300) {
            String msg = "商店返回 " + res.statusCode();
            Object errors = out.get("errors");
            if (errors instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
                Object m2 = m.get("message");
                if (m2 != null) {
                    msg = String.valueOf(m2);
                }
            } else if (out.get("message") != null) {
                msg = String.valueOf(out.get("message"));
            }
            HttpStatus status = HttpStatus.resolve(res.statusCode());
            throw new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status, msg);
        }
        return out;
    }

    /** 把商店返回的具体原因透给前端（默认 Spring 错误体不含 message） */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStoreError(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", e.getReason() == null ? "商店请求失败" : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
