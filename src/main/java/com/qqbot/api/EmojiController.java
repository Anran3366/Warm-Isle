package com.qqbot.api;

import com.qqbot.bot.OneBotClient;
import com.qqbot.emoji.EmojiService;
import com.qqbot.memory.Emoji;
import com.qqbot.memory.EmojiStore;
import com.qqbot.memory.MessageStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M3a 第 5 批（其一）：表情包素材库 */
@RestController
@RequestMapping("/api/emoji")
public class EmojiController {

    private final EmojiStore store;
    private final EmojiService service;
    private final com.qqbot.bot.OneBotRegistry registry;
    private final UserResolver users;
    private final MessageStore messages;
    private final com.qqbot.emoji.ChatEmojiReplier chatReplier;
    private final com.qqbot.memory.SettingStore settingsRef;
    private final com.qqbot.emoji.EmojiTagger tagger;
    private final com.qqbot.emoji.EmojiAnalyzer analyzer;
    private final com.qqbot.image.VisionClient vision;

    public EmojiController(EmojiStore store, EmojiService service, com.qqbot.bot.OneBotRegistry registry,
                           UserResolver users, MessageStore messages,
                           com.qqbot.emoji.ChatEmojiReplier chatReplier,
                           com.qqbot.memory.SettingStore settingsRef,
                           com.qqbot.emoji.EmojiTagger tagger,
                           com.qqbot.emoji.EmojiAnalyzer analyzer,
                           com.qqbot.image.VisionClient vision) {
        this.store = store;
        this.service = service;
        this.registry = registry;
        this.users = users;
        this.messages = messages;
        this.chatReplier = chatReplier;
        this.settingsRef = settingsRef;
        this.tagger = tagger;
        this.analyzer = analyzer;
        this.vision = vision;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Boolean enabled,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "24") int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        EmojiStore.Page result = store.page(q, enabled, status, (p - 1) * s, s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(this::toJson).toList());
        body.put("total", result.total());
        body.put("tags", store.tagStats());
        return body;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String emoTags,
                                      @RequestParam(required = false) String sceneTags,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(defaultValue = "true") boolean autoAnalyze) {
        try {
            EmojiService.Saved saved = service.save(file, emoTags, sceneTags, description, null);
            if (autoAnalyze) {
                try {
                    com.qqbot.emoji.EmojiAnalyzer.Analysis analysis = analyzer.analyze(file.getBytes());
                    if (analysis != null) {
                        store.update(saved.id(), analysis.emoTags(), analysis.sceneTags(), analysis.description(), null);
                    }
                } catch (Exception ignored) {
                    // 识别失败不影响上传
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("id", String.valueOf(saved.id()));
            Emoji emoji = store.byId(saved.id());
            if (emoji != null) {
                body.put("item", toJson(emoji));
            }
            return body;
        } catch (IllegalArgumentException e) {
            return Map.of("code", "INVALID_FILE", "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("code", "UPLOAD_FAILED", "message", e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Emoji existing = store.byId(id);
        if (existing == null) {
            return notFound();
        }
        String emoTags = body.containsKey("emoTags") ? String.valueOf(body.get("emoTags")) : null;
        String sceneTags = body.containsKey("sceneTags") ? String.valueOf(body.get("sceneTags")) : null;
        String description = body.containsKey("description") ? String.valueOf(body.get("description")) : null;
        Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
        store.update(id, emoTags, sceneTags, description, enabled);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Emoji existing = store.byId(id);
        if (existing == null) {
            return notFound();
        }
        store.delete(id);
        service.deleteFile(existing);
        return Map.of("ok", true);
    }

    /** 图片二进制，给前端 <img> 直接用（同源带 cookie） */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        Emoji emoji = store.byId(id);
        if (emoji == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = service.read(emoji);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(service.contentType(emoji)))
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 通过 NapCat 真的发一张到你 QQ 上 */
    @PostMapping("/{id}/test-send")
    public Map<String, Object> testSend(@PathVariable long id) {
        Emoji emoji = store.byId(id);
        if (emoji == null) {
            return notFound();
        }
        var c = registry.current();
        if (c == null || !c.isConnected()) {
            return Map.of("code", "NAPCAT_OFFLINE", "message", "NapCat 未连接");
        }
        try {
            byte[] bytes = service.read(emoji);
            long uid = users.resolve(null);
            c.sendPrivateImage(uid, bytes);
            messages.add(uid, "assistant", "（表情包测试）" + (emoji.emoTags() == null ? "无标签" : emoji.emoTags()), "emoji-test");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("sentTo", uid);
            return body;
        } catch (Exception e) {
            return Map.of("code", "SEND_FAILED", "message", e.getMessage());
        }
    }

    private Map<String, Object> toJson(Emoji e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.id()));
        m.put("description", e.description());
        m.put("status", e.status() == null ? "approved" : e.status());
        m.put("sentCount", e.sentCount());
        m.put("lastSentAt", e.lastSentAt());
        m.put("emoTags", e.emoTags());
        m.put("sceneTags", e.sceneTags());
        m.put("enabled", e.enabled());
        m.put("createdAt", e.createdAt());
        m.put("url", "/api/emoji/" + e.id() + "/file");
        return m;
    }

    /** 调试：给一段图片描述打标签 */
    @PostMapping("/retag")
    public Map<String, Object> retag(@RequestBody Map<String, Object> body) {
        String desc = body != null && body.get("description") != null ? String.valueOf(body.get("description")) : "";
        com.qqbot.emoji.EmojiTagger.Tags tags = tagger.tag(desc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emo", tags.emo());
        out.put("scene", tags.scene());
        return out;
    }

    /** 调试：立刻走一次「聊天中发表情」的判断 */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id) {
        store.setStatus(id, "approved");
        return Map.of("ok", true, "status", "approved");
    }

    /** 审核拒绝 = 直接删掉，不再保留「已拒绝」记录 */
    /** 批量重识别旧表情，补全「文字」描述，每次最多 6 张 */
    @PostMapping("/redescribe")
    public Map<String, Object> redescribe() {
        int done = 0;
        int failed = 0;
        for (Emoji e : store.all()) {
            if (done + failed >= 6) {
                break;
            }
            String desc = e.description() == null ? "" : e.description();
            if (desc.contains("文字")) {
                continue;
            }
            try {
                byte[] bytes = service.read(e);
                String mime = e.path().endsWith(".png") ? "image/png"
                        : e.path().endsWith(".gif") ? "image/gif" : "image/jpeg";
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String newDesc = vision.describe(base64, mime);
                if (newDesc == null || newDesc.isBlank() || "[SENSITIVE]".equals(newDesc.trim())) {
                    failed++;
                    continue;
                }
                String tags = tagger.tag(newDesc).emo();
                String full = "画面：" + newDesc.trim() + "；情绪：" + tags;
                store.updateDescription(e.id(), full.length() > 300 ? full.substring(0, 300) : full);
                done++;
            } catch (Exception ex) {
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("updated", done);
        out.put("failed", failed);
        return out;
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id) {
        Emoji existing = store.byId(id);
        if (existing == null) {
            return notFound();
        }
        store.delete(id);
        service.deleteFile(existing);
        return Map.of("ok", true, "deleted", true);
    }

    @PostMapping("/chat-test")
    public Map<String, Object> chatTest(@RequestBody(required = false) Map<String, Object> body) {
        String text = body != null && body.get("text") != null ? String.valueOf(body.get("text")) : "好开心呀";
        long uid = users.resolve(null);
        settingsRef.set("emoji.sinceLast", "7");
        chatReplier.maybeSend(uid, text, registry.current());
        return Map.of("ok", true, "sentTo", uid, "text", text);
    }

    private Map<String, Object> notFound() {
        return Map.of("code", "EMOJI_NOT_FOUND", "message", "表情不存在");
    }
}