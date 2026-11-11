package com.qqbot.api;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.MessageStore;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 大多数接口不传 qqId，按这个顺序解析「当前查看的用户」：
 * 1. 显式传入的 qqId
 * 2. 请求头 X-QQ-Id（后台右上角切换的用户）
 * 3. 白名单第一个号
 * 4. 数据库里第一个有消息的号
 */
@Component
public class UserResolver {

    private final BotProperties props;
    private final MessageStore messages;

    public UserResolver(BotProperties props, MessageStore messages) {
        this.props = props;
        this.messages = messages;
    }

    public long resolve(Long qqId) {
        if (qqId != null && qqId > 0) {
            return qqId;
        }
        Long fromHeader = headerUser();
        if (fromHeader != null) {
            return fromHeader;
        }
        List<Long> whitelist = props.getWhitelist();
        if (whitelist != null && !whitelist.isEmpty()) {
            return whitelist.get(0);
        }
        List<Long> users = messages.users();
        return users.isEmpty() ? 0L : users.get(0);
    }

    private Long headerUser() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        String raw = servlet.getRequest().getHeader("X-QQ-Id");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
