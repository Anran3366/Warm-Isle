package com.qqbot.api;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 谁能和机器人聊天（QQ 白名单），改完立即生效，不用重启 */
@RestController
@RequestMapping("/api/whitelist")
public class WhitelistController {

    private static final String KEY = "whitelist.ids";

    private final BotProperties props;
    private final SettingStore settings;

    public WhitelistController(BotProperties props, SettingStore settings) {
        this.props = props;
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Long> ids = props.getWhitelist();
        body.put("ids", ids == null ? List.of() : ids);
        body.put("allAllowed", ids == null || ids.isEmpty());
        return body;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                ids.addAll(parse(String.valueOf(o)));
            }
        } else if (raw != null) {
            ids.addAll(parse(String.valueOf(raw)));
        }
        List<Long> dedup = new ArrayList<>();
        for (Long id : ids) {
            if (!dedup.contains(id)) {
                dedup.add(id);
            }
        }
        props.setWhitelist(dedup);
        settings.set(KEY, join(dedup));
        return list();
    }

    /** 支持 "123456, 789012" 或单个号 */
    private List<Long> parse(String text) {
        List<Long> out = new ArrayList<>();
        for (String part : text.split("[,，\\s]+")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(t);
                if (id > 10000) {
                    out.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 忽略非法输入
            }
        }
        return out;
    }

    private String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
