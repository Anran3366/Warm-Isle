package com.qqbot.api;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 关于页：服务器系统状态与运行实例 */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final OneBotRegistry registry;

    public SystemController(OneBotRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memory", memory());
        body.put("jvm", jvm());
        body.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        body.put("java", System.getProperty("java.version"));
        body.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));

        List<Map<String, Object>> instances = new ArrayList<>();
        for (OneBotClient c : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("botId", c.getBotId());
            m.put("qq", c.getSelfId());
            m.put("online", c.isConnected());
            m.put("connectedAt", c.getConnectedAt());
            instances.add(m);
        }
        body.put("instances", instances);
        body.put("onlineCount", instances.stream().filter(i -> Boolean.TRUE.equals(i.get("online"))).count());
        return body;
    }

    private Map<String, Object> memory() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long total = 0, available = 0;
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    total = parseKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    available = parseKb(line);
                }
            }
            m.put("totalMb", total / 1024);
            m.put("usedMb", (total - available) / 1024);
            m.put("availableMb", available / 1024);
            m.put("percent", total == 0 ? 0 : Math.round((total - available) * 100.0 / total));
        } catch (Exception e) {
            m.put("totalMb", 0);
            m.put("usedMb", 0);
            m.put("availableMb", 0);
            m.put("percent", 0);
        }
        return m;
    }

    private Map<String, Object> jvm() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxMb", rt.maxMemory() / 1024 / 1024);
        m.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        return m;
    }

    private long parseKb(String line) {
        String digits = line.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
}
