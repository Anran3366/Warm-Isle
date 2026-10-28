package com.qqbot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 容器与进程资源占用（关于页系统状态用） */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private static final String SOCKET = "/var/run/docker.sock";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GetMapping("/containers")
    public Map<String, Object> containers() {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            String json = curl("/containers/json?all=1");
            JsonNode arr = MAPPER.readTree(json);
            for (JsonNode c : arr) {
                String id = c.path("Id").asText("");
                String state = c.path("State").asText("");
                String name = c.path("Names").path(0).asText("").replaceFirst("^/", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("image", c.path("Image").asText(""));
                m.put("state", state);
                if ("running".equals(state) && !id.isBlank()) {
                    try {
                        JsonNode stats = MAPPER.readTree(curl("/containers/" + id + "/stats?stream=false"));
                        long used = stats.path("memory_stats").path("usage").asLong(0);
                        long limit = stats.path("memory_stats").path("limit").asLong(0);
                        m.put("memUsedMb", Math.round(used / 1048576.0 * 10) / 10.0);
                        m.put("memLimitMb", Math.round(limit / 1048576.0 * 10) / 10.0);
                        m.put("memPercent", limit > 0 ? Math.round(used * 1000.0 / limit) / 10.0 : 0);
                    } catch (Exception ignored) {
                        // 没有 stats 就只显示状态
                    }
                }
                items.add(m);
            }
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("usedMb", Math.round((rt.totalMemory() - rt.freeMemory()) / 1048576.0 * 10) / 10.0);
        jvm.put("maxMb", Math.round(rt.maxMemory() / 1048576.0 * 10) / 10.0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("jvm", jvm);
        return body;
    }

    private String curl(String path) throws Exception {
        Process process = new ProcessBuilder("curl", "-s", "--max-time", "10",
                "--unix-socket", SOCKET, "http://localhost" + path).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        process.waitFor();
        return out.toString(StandardCharsets.UTF_8);
    }
}
