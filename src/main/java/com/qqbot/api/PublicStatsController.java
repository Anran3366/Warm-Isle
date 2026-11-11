package com.qqbot.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公开只读接口：给官网/个人主页的星图演示取实时数量用。
 *
 * <p>只返回聚合数字，不含记忆内容；被 {@code AuthInterceptor} 白名单放行，
 * 并在 {@code WebConfig} 里给 /api/public/** 配了 CORS。
 */
@RestController
@RequestMapping("/api/public")
public class PublicStatsController {

    private final PublicStatsService stats;

    public PublicStatsController(PublicStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/memory-stats")
    public Map<String, Object> memoryStats() {
        return stats.stats();
    }
}
