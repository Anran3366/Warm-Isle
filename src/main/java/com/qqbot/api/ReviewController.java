package com.qqbot.api;

import com.qqbot.review.DailyReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 每日复盘：查看当天聊天问题与改进建议 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DailyReviewService service;

    public ReviewController(DailyReviewService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String day,
                                    @RequestParam(defaultValue = "50") int limit) {
        String d = day == null || day.isBlank() ? LocalDate.now().minusDays(1).format(DAY) : day;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("day", d);
        body.put("items", service.list(d, limit));
        return body;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(required = false) String day) {
        String d = day == null || day.isBlank() ? LocalDate.now().minusDays(1).format(DAY) : day;
        try {
            int count = service.run(d);
            return Map.of("ok", true, "day", d, "count", count);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    @PostMapping("/{id}/apply")
    public Map<String, Object> apply(@PathVariable long id) {
        return service.apply(id);
    }
}
