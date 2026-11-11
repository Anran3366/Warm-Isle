package com.qqbot.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.SharedDb;
import com.qqbot.plugin.model.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * hosted / local 型插件的「服务端版本」巡检。
 *
 * <p>store 只能看到 manifest 版本；hosted 的代码在作者服务器上，local 的代码在部署者机器上。
 * 这里定期请求插件的 /health，如果对方返回 {@code version}，就和本地 manifest 版本比对：
 * 不一致时后台标「服务端版本不一致」，提醒 B 去处理（hosted 找作者升版本，local 按 SETUP 更新）。
 *
 * <p>不阻断触发，只做可见性——插件服务本身可能故意先灰度，不能因为版本号不同就停掉。
 */
@Component
public class PluginHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(PluginHealthMonitor.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SharedDb shared;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public PluginHealthMonitor(SharedDb shared, ObjectMapper mapper) {
        this.shared = shared;
        this.mapper = mapper;
    }

    public record Status(String remoteVersion, String status, String checkedAt) {
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void scheduled() {
        try {
            checkNow();
        } catch (Exception e) {
            log.debug("插件健康巡检失败: {}", e.getMessage());
        }
    }

    /** 立即巡检一轮，返回 {pluginId: 状态}；后台「检查服务版本」也调它。 */
    public synchronized Map<String, Status> checkNow() {
        Map<String, Status> out = new LinkedHashMap<>();
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plugin_id, version, runtime, manifest, endpoint FROM installed_plugins "
                             + "WHERE status='installed' AND runtime IN ('hosted','local')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                String version = rs.getString(2);
                String runtime = rs.getString(3);
                String manifestJson = rs.getString(4);
                String endpoint = rs.getString(5);
                Status st = checkOne(runtime, version, manifestJson, endpoint);
                out.put(id, st);
                persist(c, id, st);
            }
        } catch (Exception e) {
            log.warn("插件健康巡检失败: {}", e.getMessage());
        }
        return out;
    }

    /** 读上次巡检结果，供后台列表展示（不联网）。 */
    public Map<String, Status> statuses() {
        Map<String, Status> out = new LinkedHashMap<>();
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plugin_id, remote_version, remote_status, remote_checked_at FROM installed_plugins");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), new Status(rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (Exception e) {
            log.debug("读取插件健康状态失败: {}", e.getMessage());
        }
        return out;
    }

    private Status checkOne(String runtime, String version, String manifestJson, String endpoint) {
        String now = LocalDateTime.now().format(TS);
        String base = null;
        String health = "/health";
        PluginManifest m = null;
        try {
            m = mapper.readValue(manifestJson, PluginManifest.class);
            if (m.usesLocal()) {
                base = endpoint;
                if (m.local() != null && m.local().health() != null) health = m.local().health();
            } else if (m.hosted() != null) {
                base = m.hosted().endpoint();
                if (m.hosted().health() != null) health = m.hosted().health();
            }
        } catch (Exception e) {
            return new Status(null, "unknown", now);
        }
        if (m == null) {
            return new Status(null, "unknown", now);
        }
        if (base == null || base.isBlank()) {
            return new Status(null, "unconfigured", now);
        }
        String reason = com.qqbot.plugin.remote.RemoteFetch.checkEndpoint(
                base, m.usesLocal(), m.outboundHosts());
        if (reason != null) {
            return new Status(null, "unreachable", now);
        }
        String url = base.replaceAll("/+$", "") + (health.startsWith("/") ? health : "/" + health);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                return new Status(null, "unreachable", now);
            }
            JsonNode root = mapper.readTree(res.body() == null ? "{}" : res.body());
            String remote = root.path("version").asText("").trim();
            if (remote.isEmpty()) {
                return new Status(null, "unknown", now);
            }
            if (remote.startsWith("v") || remote.startsWith("V")) {
                remote = remote.substring(1);
            }
            return new Status(remote, remote.equals(version) ? "ok" : "outdated", now);
        } catch (Exception e) {
            return new Status(null, "unreachable", now);
        }
    }

    private void persist(Connection c, String id, Status st) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE installed_plugins SET remote_version=?, remote_status=?, remote_checked_at=? WHERE plugin_id=?")) {
            ps.setString(1, st.remoteVersion());
            ps.setString(2, st.status());
            ps.setString(3, st.checkedAt());
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("写插件健康状态失败 {}: {}", id, e.getMessage());
        }
    }
}
