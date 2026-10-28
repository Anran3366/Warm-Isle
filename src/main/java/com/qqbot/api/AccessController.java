package com.qqbot.api;

import com.qqbot.settings.AccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 试运行一键放通开关 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

    private final AccessService access;

    public AccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open", access.open());
        return body;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody(required = false) Map<String, Object> body) {
        boolean open = body != null && Boolean.parseBoolean(String.valueOf(body.get("open")));
        access.setOpen(open);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("open", access.open());
        return result;
    }
}
