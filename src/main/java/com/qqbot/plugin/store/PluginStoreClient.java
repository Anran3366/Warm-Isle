package com.qqbot.plugin.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.PluginProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商店客户端（设计文档 §7.5）。
 *
 * <p>三条硬约束：启动路径绝不同步拉索引；商店不可达时用本地缓存兜底；
 * 下载完必须比对 sha256，不一致直接拒绝安装。
 */
@Component
public class PluginStoreClient {

    private static final Logger log = LoggerFactory.getLogger(PluginStoreClient.class);
    private static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /**
     * 官方商店地址与公钥：写死在代码里，不读任何配置。
     * 部署者不能把实例指向自建商店；即使改了外部配置，官方公钥也验不过自建商店的签名。
     * 换域名/换密钥必须发版。
     */
    public static final String OFFICIAL_STORE_URL = "https://store.example.com";
    private static final boolean REQUIRE_SIGNATURE = true;

    private final PluginProperties props;
    private final StoreCache cache;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final StoreSignatureVerifier verifier;
    private final StoreKeyRing keyRing;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-store-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public PluginStoreClient(PluginProperties props, StoreCache cache, ObjectMapper mapper) {
        this.props = props;
        this.cache = cache;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getStore().getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.keyRing = new StoreKeyRing(props);
        this.verifier = new StoreSignatureVerifier(keyRing, REQUIRE_SIGNATURE);
        if (!verifier.configured()) {
            if (verifier.required()) {
                log.error("商店公钥未配置且 signature-required=true：商店索引/吊销名单会被拒绝，"
                        + "后台商店页将不可用。请配置 PLUGIN_STORE_PUBLIC_KEY（从商店 /api/v1/pubkey 获取），"
                        + "或显式设置 PLUGIN_STORE_SIGNATURE_REQUIRED=false");
            } else {
                log.warn("商店验签已关闭：索引/吊销名单不做签名校验，吊销插件只提示不阻断");
            }
        } else if (!verifier.active()) {
            log.warn("商店公钥已配置但 signature-required=false：验签不生效，吊销插件只提示不阻断");
        }
    }

    /**
     * 验签；遇到不认识的 keyId 时自动拉轮换链、采纳新公钥后重试一次。
     * 这样 A 换签名密钥后，B/C/D 不需要发版或人工改配置。
     */
    private boolean verifyOrRotate(byte[] body, HttpResponse<?> res) {
        String signature = res.headers().firstValue("X-Store-Signature").orElse(null);
        String keyId = res.headers().firstValue("X-Store-Key-Id").orElse(null);
        if (verifier.verify(body, signature, keyId)) {
            return true;
        }
        if (refreshKeyRing()) {
            return verifier.verify(body, signature, keyId);
        }
        return false;
    }

    /** 拉 /api/v1/keys/rotation，逐条验链；失败不影响主流程（沿用本地缓存） */
    private boolean refreshKeyRing() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/keys/rotation"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(res.body());
            boolean changed = keyRing.adopt(root.path("chain"), root.path("currentKeyId").asText(""));
            if (changed) {
                log.info("商店签名密钥已轮换，新 keyId={}", keyRing.currentKeyId());
            }
            return changed;
        } catch (Exception e) {
            log.debug("拉取商店轮换链失败: {}", e.getMessage());
            return false;
        }
    }

    /** 验签是否生效：生效后被吊销的插件停止触发（§6.8），不生效时只提示 */
    public boolean signatureActive() {
        return verifier.active();
    }

    /** 公钥是否配置成功（后台状态用） */
    public boolean signatureConfigured() {
        return verifier.configured();
    }

    public boolean signatureRequired() {
        return verifier.required();
    }

    public record Package(byte[] bytes, String sha256) {
    }

    public static class StoreUnavailableException extends Exception {
        public StoreUnavailableException(String message) {
            super(message);
        }
    }

    /** 插件链路开着就有商店；地址固定为官方商店，没有“不配商店”或“自建商店”的选项。 */
    public boolean enabled() {
        return props.isEnabled();
    }

    public String baseUrl() {
        return OFFICIAL_STORE_URL;
    }

    /**
     * 取索引：新鲜就直接给；过期先给旧的，后台刷新；一份都没有才同步拉。
     * 调用方永远拿不到「阻塞 10 秒」的结果。
     */
    public StoreIndex index() throws StoreUnavailableException {
        if (!enabled()) {
            throw new StoreUnavailableException("未配置商店地址");
        }
        StoreIndex cached = cache.current();
        if (cached != null && cache.fresh()) {
            return cached;
        }
        if (cached != null) {
            refreshAsync();
            return cached;
        }
        return fetchNow();
    }

    public StoreIndex fetchNow() throws StoreUnavailableException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/index.json"))
                .timeout(Duration.ofMillis(props.getStore().getReadTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new StoreUnavailableException("商店返回 " + res.statusCode());
            }
            // §6.8：验签失败的索引直接丢弃（沿用本地缓存），商店被入侵也投不了毒
            if (!verifyOrRotate(res.body(), res)) {
                log.warn("商店索引验签失败（缺签名或签名不符），本次响应已丢弃");
                throw new StoreUnavailableException("商店索引签名校验失败");
            }
            StoreIndex index = mapper.readValue(res.body(), StoreIndex.class);
            // 吊销名单随索引一起拉（§6.8）；拉不到/验签失败不清空旧名单，也不让索引刷新失败
            index = withRevocations(index);
            cache.save(index);
            log.info("商店索引已刷新: {} 个插件，{} 条吊销",
                    index.plugins() == null ? 0 : index.plugins().size(),
                    index.revocationsOrEmpty().size());
            return index;
        } catch (StoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreUnavailableException("连不上商店: " + e.getMessage());
        }
    }

    /** 拉吊销名单并合并进索引；失败时保留索引原有名单（不清空，防误判） */
    private StoreIndex withRevocations(StoreIndex index) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/revocations.json"))
                    .timeout(Duration.ofMillis(props.getStore().getReadTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                log.debug("吊销名单返回 {}，沿用索引内名单", res.statusCode());
                return index;
            }
            if (!verifyOrRotate(res.body(), res)) {
                log.warn("吊销名单验签失败，沿用旧名单（不清空，防误判）");
                return index;
            }
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(res.body());
            java.util.List<StoreIndex.Revocation> list = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root.path("revocations")) {
                list.add(new StoreIndex.Revocation(
                        n.path("pluginId").asText(null),
                        n.hasNonNull("version") ? n.path("version").asText() : null,
                        n.path("reason").asText(""),
                        n.path("createdAt").asText(null)));
            }
            return new StoreIndex(index.schemaVersion(), index.generatedAt(),
                    index.intentsVersion(), index.plugins(), list);
        } catch (Exception e) {
            log.debug("拉吊销名单失败: {}，沿用索引内名单", e.getMessage());
            return index;
        }
    }

    /**
     * 这版插件是否被商店吊销（只读本地缓存，绝不联网）。
     *
     * <p>是否阻断触发由调用方结合 {@link #signatureActive()} 决定（§6.8）：
     * 验签生效 → 命中即停止触发；未生效 → 只标红提示。任何情况下都不自动卸载。
     */
    public boolean isRevoked(String pluginId, String version) {
        StoreIndex cached = cache.current();
        return cached != null && cached.revoked(pluginId, version);
    }

    /** 命中的吊销记录（拿原因展示用），没有返回 null */
    public StoreIndex.Revocation revocationOf(String pluginId, String version) {
        StoreIndex cached = cache.current();
        return cached == null ? null : cached.revocationOf(pluginId, version);
    }

    private void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refresher.submit(() -> {
            try {
                fetchNow();
            } catch (Exception e) {
                log.debug("后台刷新商店索引失败: {}", e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** 下载包体：限 50MB，边读边计数，超了立即断开。 */
    public Package download(String pluginId, String version) throws StoreUnavailableException {
        String path = "/api/v1/download/" + encode(pluginId) + "/" + encode(version);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/zip")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                throw new StoreUnavailableException("下载失败，商店返回 " + res.statusCode());
            }
            try (InputStream in = res.body()) {
                byte[] bytes = readLimited(in, MAX_PACKAGE_BYTES);
                String sha = sha256(bytes);
                String header = res.headers().firstValue("X-Checksum-Sha256").orElse(null);
                if (header != null && !header.equalsIgnoreCase(sha)) {
                    throw new StoreUnavailableException("包体校验失败：sha256 与商店声明不一致");
                }
                return new Package(bytes, sha);
            }
        } catch (StoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreUnavailableException("下载失败: " + e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new StoreUnavailableException("包体超过 50MB，已中断");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
