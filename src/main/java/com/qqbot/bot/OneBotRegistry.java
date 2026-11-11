package com.qqbot.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.GlobalDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理多个机器人账号的 OneBot 连接。
 * 启动时读取 bot_accounts；表为空则用 NAPCAT_WS_URL 播种第一个账号。
 */
@Component
public class OneBotRegistry {

    private static final Logger log = LoggerFactory.getLogger(OneBotRegistry.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GlobalDb globalDb;
    private final BotProperties props;
    private final org.springframework.beans.factory.ObjectProvider<MessageDispatcher> dispatcherProvider;
    private final ObjectMapper mapper;
    private final Map<Long, OneBotClient> clients = new ConcurrentHashMap<>();

    public OneBotRegistry(GlobalDb globalDb, BotProperties props,
                          org.springframework.beans.factory.ObjectProvider<MessageDispatcher> dispatcherProvider,
                          ObjectMapper mapper) {
        this.globalDb = globalDb;
        this.props = props;
        this.dispatcherProvider = dispatcherProvider;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        try {
            for (Object[] a : loadAccounts()) {
                long id = ((Number) a[0]).longValue();
                String ws = String.valueOf(a[1]);
                start(id, ws);
            }
            log.info("已启动 {} 个机器人连接", clients.size());
        } catch (Exception e) {
            log.error("启动机器人连接失败", e);
        }
    }

    private List<Object[]> loadAccounts() throws Exception {
        List<Object[]> list = new ArrayList<>();
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, ws_url FROM bot_accounts WHERE enabled=1 ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Object[]{rs.getLong("id"), rs.getString("ws_url")});
            }
        }
        return list;
    }

    public void start(long botId, String wsUrl) {
        clients.computeIfAbsent(botId, id -> {
            OneBotClient client = new OneBotClient(id, wsUrl,
                    props.getNapcat().getAccessToken(), dispatcherProvider.getObject(), mapper);
            client.start();
            log.info("启动机器人连接 qq={} ws={}", id, wsUrl);
            return client;
        });
    }

    public void stop(long botId) {
        OneBotClient c = clients.remove(botId);
        if (c != null) {
            c.shutdown();
            log.info("已停止机器人连接 qq={}", botId);
        }
    }

    public OneBotClient get(long botId) {
        return clients.get(botId);
    }

    /** 当前上下文的账号；没有上下文直接报错，不再猜默认账号 */
    public OneBotClient requireCurrent() {
        Long id = BotContext.get();
        if (id == null) {
            throw new IllegalStateException("缺少机器人账号上下文（X-Bot-Id）");
        }
        OneBotClient c = clients.get(id);
        if (c == null || !c.isConnected()) {
            throw new IllegalStateException("机器人账号未连接: " + id);
        }
        return c;
    }

    /** 自愈：启用中的账号如果没有客户端，自动补上（每 30 秒） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void ensureEnabledClients() {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, ws_url FROM bot_accounts WHERE enabled=1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                if (!clients.containsKey(id)) {
                    String ws = rs.getString("ws_url");
                    log.info("发现未连接的账号，自动重连 qq={} ws={}", id, ws);
                    start(id, ws);
                }
            }
        } catch (Exception e) {
            log.warn("账号连接自愈失败: {}", e.getMessage());
        }
    }

    /** 当前上下文的在线客户端；没有上下文或未连接返回 null */
    public OneBotClient current() {
        Long id = BotContext.get();
        if (id == null) {
            return null;
        }
        OneBotClient c = clients.get(id);
        return c != null && c.isConnected() ? c : null;
    }

    /** 当前上下文的客户端，不论是否登录（状态展示用，可以区分"没连上"和"连上但没登录"） */
    public OneBotClient contextClient() {
        Long id = BotContext.get();
        return id == null ? null : clients.get(id);
    }

    /** 指定的在线客户端；未连接返回 null */
    public OneBotClient connected(long botId) {
        OneBotClient c = clients.get(botId);
        return c != null && c.isConnected() ? c : null;
    }

    public Collection<OneBotClient> all() {
        return clients.values();
    }

    public List<Map<String, Object>> statusAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (OneBotClient c : clients.values()) {
            list.add(c.status());
        }
        return list;
    }
}
