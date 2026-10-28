package com.qqbot.api;

import com.qqbot.bot.OneBotClient;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.Persona;
import com.qqbot.memory.PersonaStore;
import com.qqbot.memory.SettingStore;
import com.qqbot.memory.UsageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/status，字段与 docs/api-contract.md 及前端 types.ts 对齐：
 * { bot:{online,napcat,uptimeSec}, today:{messages,cost,tokens},
 *   persona:{id,name}, memory:{facts,pending,lastExtractedAt} }
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final com.qqbot.bot.OneBotRegistry registry;
    private final MessageStore messages;
    private final UsageStore usage;
    private final FactStore facts;
    private final SettingStore settings;
    private final UserResolver users;
    private final PersonaStore personas;
    private final BotProperties props;
    private final Instant startedAt = Instant.now();

    public StatusController(com.qqbot.bot.OneBotRegistry registry, MessageStore messages, UsageStore usage,
                            FactStore facts, SettingStore settings, UserResolver users,
                            PersonaStore personas, BotProperties props) {
        this.registry = registry;
        this.messages = messages;
        this.usage = usage;
        this.facts = facts;
        this.settings = settings;
        this.users = users;
        this.personas = personas;
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        long uid = users.resolve(null);
        Map<String, Object> body = new LinkedHashMap<>();

        Map<String, Object> bot = new LinkedHashMap<>();
        var c = registry.contextClient();
        // online = QQ 真的登录了；napcat = WebSocket 通（等扫码时 napcat=true 而 online=false）
        boolean loggedIn = c != null && c.isLoggedIn();
        bot.put("online", loggedIn);
        bot.put("loggedIn", loggedIn);
        bot.put("napcat", c != null && c.socketUp());
        bot.put("uptimeSec", Duration.between(startedAt, Instant.now()).toSeconds());
        body.put("bot", bot);

        Map<String, Object> today = new LinkedHashMap<>();
        today.put("messages", messages.countToday());
        today.put("cost", Math.round(usage.todayCost() * 10000.0) / 10000.0);
        today.put("tokens", usage.todayTokens());
        body.put("today", today);

        Map<String, Object> persona = new LinkedHashMap<>();
        Persona active = personas.active();
        persona.put("id", active == null ? "p0" : String.valueOf(active.id()));
        persona.put("name", active == null ? props.getPersona().getName() : active.name());
        body.put("persona", persona);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("facts", facts.countAllActive());
        memory.put("pending", facts.pendingAll().size());
        memory.put("lastExtractedAt", lastExtractedAt(uid));
        body.put("memory", memory);

        return body;
    }

    private String lastExtractedAt(long uid) {
        String raw = settings.get("last_extracted_at:" + uid, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, TS)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("pong", true);
    }
}
