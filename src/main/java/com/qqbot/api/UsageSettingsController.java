package com.qqbot.api;

import com.qqbot.memory.UsageStore;
import com.qqbot.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 用量曲线与运行时设置（M3a 第 2 批） */
@RestController
@RequestMapping("/api")
public class UsageSettingsController {

    private final UsageStore usage;
    private final SettingsService settings;

    public UsageSettingsController(UsageStore usage, SettingsService settings) {
        this.usage = usage;
        this.settings = settings;
    }

    /** GET /api/usage?from=2026-10-16&to=2026-10-22 */
    @GetMapping("/usage")
    public List<Map<String, Object>> usage(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate start = from == null || from.isBlank() ? end.minusDays(6) : LocalDate.parse(from);
        return usage.daily(start.toString(), end.toString());
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settings.current();
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body) {
        return settings.update(body);
    }
}
