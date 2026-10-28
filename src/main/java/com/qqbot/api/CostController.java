package com.qqbot.api;

import com.qqbot.memory.UsageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cost")
public class CostController {
    private final UsageStore usage;

    public CostController(UsageStore usage) { this.usage = usage; }

    @GetMapping
    public Map<String, Object> summary(@RequestParam(defaultValue = "7") int days) {
        LocalDate today = LocalDate.now();
        String from = today.minusDays(Math.max(1, Math.min(30, days)) - 1).toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("todayCost", usage.todayCost());
        body.put("todayTokens", usage.todayTokens());
        body.put("daily", usage.daily(from, today.toString()));
        body.put("perUser", usage.perUser(from + " 00:00:00"));
        return body;
    }
}