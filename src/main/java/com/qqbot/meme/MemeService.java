package com.qqbot.meme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.OneBotClient;
import com.qqbot.emoji.EmojiService;
import com.qqbot.emoji.EmojiTagger;
import com.qqbot.image.ImageService;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 梗学习与表情搜索：每天热梗入库；用户点名要表情包时，先理解梗，再搜图、审核、入库、发送。
 */
@Service
public class MemeService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(MemeService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SearxClient searx;
    private final DeepSeekClient llm;
    private final Db db;
    private final ObjectMapper mapper;
    private final ImageService images;
    private final EmojiService emojis;
    private final EmojiTagger tagger;
    private final MessageStore messages;

    public MemeService(SearxClient searx, DeepSeekClient llm, Db db, ObjectMapper mapper,
                       ImageService images, EmojiService emojis, EmojiTagger tagger,
                       MessageStore messages) {
        this.searx = searx;
        this.llm = llm;
        this.db = db;
        this.mapper = mapper;
        this.images = images;
        this.emojis = emojis;
        this.tagger = tagger;
        this.messages = messages;
    }

    @Scheduled(cron = "0 30 8 * * *")
    public void dailyLearn() {
        accountRunner.forEach(id -> {
            try {
                StringBuilder material = new StringBuilder();
                for (String q : new String[]{"今日热梗", "最近流行的梗", "网络热梗 解释"}) {
                    for (SearxClient.Result r : searx.search(q, false)) {
                        material.append(r.title()).append("：").append(r.content()).append('\n');
                        if (material.length() > 6000) {
                            break;
                        }
                    }
                }
                if (material.length() < 50) {
                    return;
                }
                String prompt = "根据下面的资料，提炼今天值得知道的网络梗，输出 JSON 数组，最多 6 条。"
                        + "每条：{\"name\":\"梗名\",\"aliases\":[\"别名\"],\"summary\":\"一句话解释\","
                        + "\"usage\":\"怎么用\"}。只输出 JSON。\n\n" + material;
                var r = llm.chat(List.of(
                        Map.of("role", "system", "content", "你只输出合法 JSON 数组。"),
                        Map.of("role", "user", "content", prompt)), 0.3, 1200);
                String out = r.content() == null ? "" : r.content().trim();
                int s = out.indexOf('[');
                int e = out.lastIndexOf(']');
                if (s < 0 || e <= s) {
                    return;
                }
                JsonNode arr = mapper.readTree(out.substring(s, e + 1));
                int n = 0;
                for (JsonNode node : arr) {
                    String name = node.path("name").asText("");
                    String summary = node.path("summary").asText("");
                    if (name.isBlank() || summary.isBlank()) {
                        continue;
                    }
                    String aliases = node.path("aliases").isArray()
                            ? mapper.writeValueAsString(node.path("aliases")) : "[]";
                    upsert(name, aliases, summary, node.path("usage").asText(""), 50, "daily");
                    n++;
                }
                log.info("今日梗学习完成 条数={}", n);
            } catch (Exception e) {
                log.warn("每日梗学习失败: {}", e.getMessage());
            }
    
        });
    }

    /** 用户要表情包；处理了返回 true */
    public boolean tryHandle(long userId, String text, OneBotClient client) {
        if (text == null || !text.contains("表情包") || client == null) {
            return false;
        }
        if (!(text.contains("来") || text.contains("发") || text.contains("给") || text.contains("要"))) {
            return false;
        }
        String entity = extractEntity(text);
        if (entity == null || entity.length() < 2) {
            return false;
        }
        try {
            byte[] image = findImage(userId, entity);
            if (image != null) {
                client.sendPrivateImage(userId, image);
                // 找到图就直接给，别解释这个梗是什么——真人只会说「找到啦，给你」
                String line = pickLine(entity);
                client.sendPrivate(userId, line);
                recordExchange(userId, text, "（我发了一张表情包：" + entity + "）", line);
                return true;
            }
            String fallback = "这个我还没找到图，等我翻翻再给你";
            client.sendPrivate(userId, fallback);
            recordExchange(userId, text, fallback, null);
            return true;
        } catch (Exception e) {
            log.warn("表情包搜索失败: {}", e.getMessage());
            return false;
        }
    }

    /** 落库并触发 SSE（BotContext 由调用方设置）；失败不影响发送 */
    private void recordExchange(long userId, String text, String reply, String spokenLine) {
        try {
            messages.add(userId, "user", text, "text");
            messages.add(userId, "assistant", reply, "meme");
            if (spokenLine != null && !spokenLine.isBlank()) {
                // 用户实际看到的是一句话 + 一张图，历史里也要有这句话
                messages.add(userId, "assistant", spokenLine, "text");
            }
        } catch (Exception e) {
            log.warn("表情包对话落库失败: {}", e.getMessage());
        }
    }

    /**
     * 找到图之后随口说的一句。
     * 不解释梗、不报菜名式描述画面，真人只会说「找到啦，给你」。
     */
    static String pickLine(String entity) {
        String e = entity == null ? "" : entity.trim();
        String[] pool = {
                "找到啦，给你！",
                "喏，" + e + "的图，给你",
                "给你找了张" + e + "，喜欢吗？",
                "刚翻到的" + e + "，拿去吧",
                "这张" + e + "我觉得挺像你的哈哈",
        };
        int i = java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.length);
        String line = pool[i];
        return line.length() > 30 ? line.substring(0, 30) : line;
    }

    static String extractEntity(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replace("表情包", "").replace("表情", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("(帮我|给我|麻烦|我想要|我想|想要|想看|看看|一张|一个|张|个|的|吧|嘛|呀|啊|来|发|要|想|能|不能|有|你)", "")
                .replaceAll("[\\p{Punct}\\s]", "")
                .trim();
        return s.length() < 2 ? null : s;
    }

    private String explain(String entity) {
        String cached = lookup(entity);
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder();
        String plain = entity.replace("是", " ").replace("的", " ").trim();
        for (String q : new String[]{entity + " 是什么梗", entity + " 出处", plain + " 梗 解释"}) {
            for (SearxClient.Result r : searx.search(q, false)) {
                sb.append(r.title()).append("：").append(r.content()).append('\n');
                if (sb.length() > 3500) {
                    break;
                }
            }
            if (sb.length() > 800) {
                break;
            }
        }
        if (sb.length() < 50) {
            return null;
        }
        try {
            var r = llm.chat(List.of(
                    Map.of("role", "system", "content",
                            "先弄清这个人/事是谁，再解释这个梗为什么好笑。输出一句话，不超过60字，口语化。"),
                    Map.of("role", "user", "content", sb.toString())), 0.3, 120);
            String text = r.content() == null ? "" : r.content().trim();
            if (!text.isBlank()) {
                upsert(entity, "[]", text, "", 30, "search");
                return text;
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    private byte[] findImage(long userId, String entity) {
        java.util.List<SearxClient.Result> results = new java.util.ArrayList<>();
        String plain = entity.replace("是", " ").replace("的", " ").trim();
        for (String q : new String[]{entity + " 表情包", plain + " 表情包"}) {
            results.addAll(searx.search(q, true));
            if (results.size() >= 8) {
                break;
            }
        }
        int tried = 0;
        for (SearxClient.Result r : results) {
            if (tried++ >= 5) {
                break;
            }
            String img = r.imgSrc();
            if (img == null || img.isBlank() || !img.startsWith("http")) {
                continue;
            }
            byte[] raw = searx.download(img);
            if (raw == null || raw.length < 2000) {
                continue;
            }
            try {
                String base64 = Base64.getEncoder().encodeToString(raw);
                String desc = images.describeBase64(base64);
                if (desc == null || desc.isBlank() || "[SENSITIVE]".equals(desc.trim())) {
                    continue;
                }
                String ext = desc != null && desc.contains("gif") ? "gif" : "jpg";
                String tags = tagger.tag(desc).emo();
                emojis.saveBytes(raw, ext, tags, tagger.tag(desc).scene(),
                        "画面：" + desc + "；情绪：" + tags, "approved");
                return raw;
            } catch (Exception e) {
                log.debug("候选图不可用: {}", e.getMessage());
            }
        }
        return null;
    }

    private String lookup(String name) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT summary FROM meme_card WHERE name=? OR aliases LIKE ? LIMIT 1")) {
            ps.setString(1, name);
            ps.setString(2, "%" + name + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void upsert(String name, String aliases, String summary, String usage, int heat, String source) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO meme_card(name, aliases, summary, usage, heat, source, created_at, updated_at) "
                             + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
                             + "summary=excluded.summary, usage=excluded.usage, updated_at=excluded.updated_at")) {
            String now = LocalDateTime.now().format(TS);
            ps.setString(1, name);
            ps.setString(2, aliases == null ? "[]" : aliases);
            ps.setString(3, summary);
            ps.setString(4, usage == null ? "" : usage);
            ps.setInt(5, heat);
            ps.setString(6, source);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("保存梗卡片失败: {}", e.getMessage());
        }
    }
}
