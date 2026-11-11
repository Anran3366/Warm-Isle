package com.qqbot.plugin.remote;

import com.qqbot.plugin.exec.DataExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 插件产物的「回取」通道（设计文档 §10.4 SSRF 规则）。
 *
 * <p>插件返回的 {@code outputs[].url} 由宿主下载。只校验 {@code https://} 挡不住任何东西，
 * 所以这里逐条落实：
 * <ol>
 *   <li>host 必须命中 manifest 的 {@code permissions.network.outbound} 白名单；</li>
 *   <li>解析出的<b>所有</b> IP 都必须是公网地址（hosted 型），私网/环回/链路本地一律拒；</li>
 *   <li>禁止重定向（{@link HttpClient.Redirect#NEVER}）；</li>
 *   <li>只允许 443 端口；</li>
 *   <li>硬超时 15 秒，边下边计数，超限立刻断；</li>
 *   <li>下载后再过一遍魔数校验，认不出格式的丢弃。</li>
 * </ol>
 *
 * <p>已知取舍：Java 的 {@code HttpClient} 不能「按解析结果直连 IP 再带 Host 头」，
 * 因此 DNS rebinding 只能靠「请求前把域名解析出的每个 IP 全查一遍 + 域名必须在白名单里」
 * 收敛，做不到逐连接绑定。攻击者需要先控制一个已审核白名单内的域名，门槛已经很高。
 *
 * <p>{@code local} 型（部署者自己的服务）允许私网地址，但云元数据、链路本地仍然拒绝。
 */
@Component
public class RemoteFetch {

    private static final Logger log = LoggerFactory.getLogger(RemoteFetch.class);

    public static final long MAX_AUDIO = 1024 * 1024;
    public static final long MAX_IMAGE = 5 * 1024 * 1024;
    private static final long MAX_TEXT = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * @param url          插件给的地址
     * @param allowedHosts manifest 声明的出站域名（可为空只在 local 下允许）
     * @param allowPrivate true = local 型（部署者自己的内网服务）
     * @param kind         audio / image / text
     */
    public byte[] fetch(String url, List<String> allowedHosts, boolean allowPrivate, String kind) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件返回的地址格式不对");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPrivate && scheme.equals("http"))) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物地址必须是 https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物地址缺少主机名");
        }
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port != -1 && port != 443 && !allowPrivate) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物只允许 443 端口");
        }
        if (!allowPrivate && !hostAllowed(host, allowedHosts)) {
            throw new DataExecutor.PluginException("BAD_OUTPUT",
                    "插件产物域名 " + host + " 不在 manifest 声明的出站白名单里");
        }
        if (allowPrivate) {
            String reason = localReject(host);
            if (reason != null) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", reason);
            }
        } else {
            String reason = publicOnly(host);
            if (reason != null) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", reason);
            }
        }

        long limit = limitOf(kind);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(READ_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物地址发生重定向，已拒绝");
            }
            if (code < 200 || code >= 300) {
                throw new DataExecutor.PluginException("UNAVAILABLE", "回取插件产物失败：HTTP " + code);
            }
            return readLimited(res.body(), limit, kind);
        } catch (DataExecutor.PluginException e) {
            throw e;
        } catch (Exception e) {
            log.debug("回取插件产物失败 {}: {}", uri, e.getMessage());
            throw new DataExecutor.PluginException("UNAVAILABLE", "回取插件产物失败");
        }
    }

    private byte[] readLimited(InputStream in, long limit, String kind) {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new DataExecutor.PluginException("OUTPUT_TOO_LARGE",
                            "插件产物超过上限（" + (limit / 1024 / 1024) + "MB）");
                }
                out.write(buf, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (!looksLike(kind, bytes)) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物格式校验未通过");
            }
            return bytes;
        } catch (DataExecutor.PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new DataExecutor.PluginException("UNAVAILABLE", "读取插件产物失败");
        }
    }

    /**
     * hosted/local 的「调用入口」校验：输出 URL 有 fetch 的白名单/公网校验，
     * 但 invoke 的 endpoint 过去完全绕过了这一层。每次调用前都要过这里。
     *
     * @return 错误文案；null 表示可以请求
     */
    public static String checkEndpoint(String base, boolean allowPrivate, List<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(base == null ? "" : base.trim());
        } catch (Exception e) {
            return "插件服务地址格式不对";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (allowPrivate) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "本地插件地址必须是 http(s)";
            }
        } else if (!scheme.equals("https")) {
            return "hosted 插件地址必须是 https";
        }
        if (uri.getUserInfo() != null) {
            return "插件服务地址不允许带用户名密码";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "插件服务地址缺少主机名";
        }
        if (!allowPrivate) {
            int port = uri.getPort();
            if (port != -1 && port != 443) {
                return "hosted 插件入口只允许 443 端口";
            }
            if (!hostAllowed(host, allowedHosts)) {
                return "hosted 插件入口域名不在 manifest 出站白名单里";
            }
            String reason = publicOnly(host);
            if (reason != null) {
                return reason;
            }
        } else {
            String reason = localReject(host);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /** 域名白名单：精确匹配，或 {@code *.example.com} / {@code .example.com} 后缀匹配 */
    public static boolean hostAllowed(String rawHost, List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty() || rawHost == null) {
            return false;
        }
        String host = rawHost.toLowerCase(Locale.ROOT);
        for (String raw : allowedHosts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String rule = raw.trim().toLowerCase(Locale.ROOT);
            int slash = rule.indexOf("//");
            if (slash >= 0) {
                rule = rule.substring(slash + 2);
            }
            int path = rule.indexOf('/');
            if (path > 0) {
                rule = rule.substring(0, path);
            }
            int colon = rule.indexOf(':');
            if (colon > 0) {
                rule = rule.substring(0, colon);
            }
            if (rule.startsWith("*.")) {
                rule = rule.substring(1);
            }
            if (rule.startsWith(".")) {
                if (host.endsWith(rule) || host.equals(rule.substring(1))) {
                    return true;
                }
            } else if (host.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    /** 公网校验：任何一条解析结果落在内网/环回/链路本地/组播都不放行 */
    public static String publicOnly(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "域名解析失败：" + host;
        }
        if (addrs.length == 0) {
            return "域名解析为空：" + host;
        }
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                return "插件产物地址指向内网，已拒绝";
            }
            if (isUniqueLocalV6(a)) {
                return "插件产物地址指向内网，已拒绝";
            }
            if (isMetadata(a)) {
                return "插件产物地址指向云元数据服务，已拒绝";
            }
        }
        return null;
    }

    /** local 型：允许私网，但绝不允许云元数据 / 链路本地 */
    public static String localReject(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("metadata") || lower.equals("metadata.google.internal")) {
            return "不允许访问云元数据地址";
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "域名解析失败：" + host;
        }
        for (InetAddress a : addrs) {
            if (a.isLinkLocalAddress() || isMetadata(a)) {
                return "不允许访问云元数据或链路本地地址";
            }
        }
        return null;
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isMetadata(InetAddress a) {
        String ip = a.getHostAddress();
        return "169.254.169.254".equals(ip) || "100.100.100.200".equals(ip)
                || "fd00:ec2::254".equalsIgnoreCase(ip);
    }

    static long limitOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case "audio" -> MAX_AUDIO;
            case "image" -> MAX_IMAGE;
            default -> MAX_TEXT;
        };
    }

    /** 魔数校验：宿主只认 audio / image / text，认不出就当插件坏了 */
    static boolean looksLike(String kind, byte[] b) {
        if (b == null) {
            return false;
        }
        if ("audio".equals(kind)) {
            if (b.length < 4) {
                return false;
            }
            return startsWith(b, "ID3")
                    || startsWith(b, "fLaC")
                    || startsWith(b, "OggS")
                    || startsWith(b, "#!AMR")
                    || startsWith(b, "RIFF")
                    || (u(b[0]) == 0xFF && (u(b[1]) & 0xE0) == 0xE0)
                    || startsWith(b, "\u0000\u0000\u0000"); // m4a/mp4 的 ftyp 盒子
        }
        if ("image".equals(kind)) {
            if (b.length < 4) {
                return false;
            }
            return startsWith(b, "\u0089PNG")
                    || (u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF)
                    || startsWith(b, "GIF8")
                    || (startsWith(b, "RIFF") && b.length > 12
                        && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"));
        }
        // text：必须是合法 UTF-8 且不含控制字符乱码
        return !containsBinaryNoise(b);
    }

    private static boolean containsBinaryNoise(byte[] b) {
        int check = Math.min(b.length, 4096);
        for (int i = 0; i < check; i++) {
            int v = u(b[i]);
            if (v == 0) {
                return true;
            }
            if (v < 0x09) {
                return true;
            }
        }
        return false;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    private static boolean startsWith(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        if (b.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (b[i] != p[i]) {
                return false;
            }
        }
        return true;
    }
}
