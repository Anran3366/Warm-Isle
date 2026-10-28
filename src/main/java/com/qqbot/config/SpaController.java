package com.qqbot.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端是 history 路由（/m/:token、/account 等），直接刷新这些地址时
 * Spring 静态资源找不到文件会 404，这里统一转回 index.html 交给 Vue Router。
 * 只列出前端路由，避免把 /api/** 和静态资源也吞掉。
 */
@Controller
public class SpaController {

    @GetMapping({
            "/", "/login", "/m/**", "/dashboard", "/chat", "/persona", "/account",
            "/knowledge", "/memory", "/emoji", "/voice", "/push", "/topic-block",
            "/settings", "/logs", "/usage"
    })
    public String index() {
        return "forward:/index.html";
    }
}
