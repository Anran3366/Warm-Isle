package com.qqbot.settings;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 可聊天的 QQ（白名单）。按当前 BotContext 读取该机器人账号自己的配置。
 * 空列表 = 不限制（安全起见建议至少配置一个）。
 */
@Service
public class WhitelistService {

    private static final String KEY = "whitelist.ids";

    private final SettingStore settings;
    private final BotProperties props;

    public WhitelistService(SettingStore settings, BotProperties props) {
        this.settings = settings;
        this.props = props;
    }

    public List<Long> ids() {
        String raw = settings.get(KEY, null);
        if (raw == null || raw.isBlank()) {
            List<Long> fallback = props.getWhitelist();
            return fallback == null ? List.of() : fallback;
        }
        List<Long> out = new ArrayList<>();
        for (String s : raw.split("[,\\s]+")) {
            if (s.isBlank()) {
                continue;
            }
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // 跳过非法项
            }
        }
        return out;
    }

    public void set(List<Long> ids) {
        String joined = ids == null ? "" : String.join(",", ids.stream().map(String::valueOf).toList());
        settings.set(KEY, joined);
    }

    public boolean allows(long qqId) {
        List<Long> ids = ids();
        return ids.isEmpty() || ids.contains(qqId);
    }
}
