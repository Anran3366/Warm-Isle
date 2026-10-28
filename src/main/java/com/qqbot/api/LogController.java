package com.qqbot.api;

import com.qqbot.logs.LogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 后台日志查看 */
@RestController
@RequestMapping("/api")
public class LogController {

    private final LogService logs;

    public LogController(LogService logs) {
        this.logs = logs;
    }

    @GetMapping("/logs")
    public List<Map<String, String>> logs(@RequestParam(defaultValue = "bot") String source,
                                          @RequestParam(required = false) String level,
                                          @RequestParam(defaultValue = "200") int tail) {
        return logs.tail(source, level, Math.min(1000, Math.max(1, tail)));
    }
}
