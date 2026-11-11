package com.qqbot.account;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已注册的协议端。新增一种接入方式只需实现 {@link ProtocolBackend} 并声明成 Bean。
 */
@Component
public class ProtocolBackends {

    private final Map<String, ProtocolBackend> byKey = new LinkedHashMap<>();

    public ProtocolBackends(List<ProtocolBackend> backends) {
        backends.forEach(b -> byKey.put(b.key(), b));
    }

    /** 按 key 取；未知 key 退回 NapCat（老数据没有 backend 字段时的行为） */
    public ProtocolBackend get(String key) {
        if (key == null || key.isBlank()) {
            return defaultBackend();
        }
        ProtocolBackend b = byKey.get(key.trim().toLowerCase());
        return b != null ? b : defaultBackend();
    }

    public ProtocolBackend defaultBackend() {
        ProtocolBackend b = byKey.get(NapCatBackend.KEY);
        if (b != null) {
            return b;
        }
        return byKey.values().iterator().next();
    }

    /** 给前端下拉用 */
    public List<Map<String, Object>> describe() {
        // 默认协议端排第一，前端下拉的第一项就是它
        String def = defaultBackend().key();
        return byKey.values().stream()
                .sorted(java.util.Comparator.comparing(b -> !b.key().equals(def)))
                .map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b.key());
            m.put("name", b.displayName());
            m.put("scanHint", b.scanHint(b.wsPortBase() + 1, b.uiPortBase() + 1));
                    return m;
                }).toList();
    }

    public boolean known(String key) {
        return key != null && byKey.containsKey(key.trim().toLowerCase());
    }
}
