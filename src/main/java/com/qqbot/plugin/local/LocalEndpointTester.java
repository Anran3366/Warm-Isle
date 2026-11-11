package com.qqbot.plugin.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * local 型「自托管服务」的地址检查（设计文档 §7.6）。
 *
 * <p>宿主只做 HTTP 调用，不启停任何进程；保存地址前必须先连通一次，避免把错的地址
 * 存进库里、等聊天时才炸。私网地址是允许的（部署者自己起在宿主机/兄弟容器里），
 * 但云元数据地址一律拒绝。
 */
@Component
public class LocalEndpointTester {

    private static final Logger log = LoggerFactory.getLogger(LocalEndpointTester.class);

    public record Result(boolean ok, int status, long latencyMs, String message) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Result test(String baseUrl, String healthPath) {
        URI base;
        try {
            base = URI.create(baseUrl.trim());
        } catch (Exception e) {
            return new Result(false, 0, 0, "地址格式不对");
        }
        String rejected = reject(base);
        if (rejected != null) {
            return new Result(false, 0, 0, rejected);
        }

        String path = healthPath == null || healthPath.isBlank() ? "/health" : healthPath;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI target = URI.create(base.getScheme() + "://" + base.getAuthority() + path);

        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            long cost = System.currentTimeMillis() - t0;
            if (res.statusCode() >= 300 && res.statusCode() < 400) {
                return new Result(false, res.statusCode(), cost, "地址发生了重定向，请填最终地址");
            }
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            return new Result(ok, res.statusCode(), cost,
                    ok ? "连接正常" : "服务返回 " + res.statusCode());
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            log.debug("local 端点测试失败 {}: {}", target, e.getMessage());
            return new Result(false, 0, cost, "连不上：" + friendly(e));
        }
    }

    /** 只允许 http/https；拒绝云元数据与管理端口。 */
    public static String reject(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return "只支持 http:// 或 https://";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "地址里没有主机名";
        }
        String lower = host.toLowerCase();
        if (lower.equals("metadata.google.internal") || lower.equals("metadata")) {
            return "不允许访问云元数据地址";
        }
        if (lower.equals("169.254.169.254") || lower.equals("100.100.100.200")) {
            return "不允许访问云元数据地址";
        }
        int port = uri.getPort();
        boolean loopback = lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1");
        if (loopback && (port == 8080 || port == 8081)) {
            return "不允许指向宿主管理端口 " + port;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLinkLocalAddress()) {
                return "不允许访问链路本地地址";
            }
        } catch (Exception ignored) {
            // 解析失败交给后面的 connect 报错，这里不拦
        }
        return null;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Connection refused")) {
            return "服务没在跑，或端口不对";
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return "超时（5 秒）";
        }
        return m;
    }
}
