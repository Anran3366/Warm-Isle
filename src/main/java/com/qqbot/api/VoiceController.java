package com.qqbot.api;

import com.qqbot.bot.OneBotClient;
import com.qqbot.memory.MessageStore;
import com.qqbot.voice.AudioAnalysis;
import com.qqbot.voice.VoiceCloneService;
import com.qqbot.voice.VoiceProfile;
import com.qqbot.voice.VoiceService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 语音：配置、试听（合成后返回可直接播放的 URL，同时发到 QQ）、音色库 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final List<String> VOICES = List.of(
            "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean");

    /** 学习完成后立刻用新音色念这一句，让用户马上听到效果 */
    private static final String PREVIEW_TEXT = "你好呀，我是你的新音色，以后就用这个声音和你说话啦。";

    private final VoiceService voice;
    private final com.qqbot.bot.OneBotRegistry registry;
    private final UserResolver users;
    private final MessageStore messages;
    private final VoiceCloneService clone;
    private final com.qqbot.config.BotProperties props;
    private final com.qqbot.memory.SharedSettings settingsRef;

    public VoiceController(VoiceService voice, com.qqbot.bot.OneBotRegistry registry, UserResolver users,
                           MessageStore messages, VoiceCloneService clone,
                           com.qqbot.config.BotProperties props,
                           com.qqbot.memory.SharedSettings settingsRef) {
        this.voice = voice;
        this.registry = registry;
        this.users = users;
        this.messages = messages;
        this.clone = clone;
        this.props = props;
        this.settingsRef = settingsRef;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "mimo");
        body.put("configured", voice.configured());
        body.put("enabled", voice.enabled());
        body.put("voice", voice.voice());
        body.put("speed", voice.speed());
        body.put("model", voice.model());
        body.put("format", "wav");
        body.put("voices", VOICES);
        body.put("chatProbability", props.getVoice().getChatProbability());
        body.put("dailyLimit", props.getVoice().getDailyLimit());
        body.put("styleLlm", Boolean.parseBoolean(settingsRef.get("voice.styleLlm", "true")));

        VoiceProfile active = clone.active();
        Map<String, Object> cloneInfo = new LinkedHashMap<>();
        cloneInfo.put("enabled", clone.enabled());
        cloneInfo.put("hasRef", clone.hasRef());
        cloneInfo.put("name", clone.name());
        cloneInfo.put("activeId", active == null ? null : active.id());
        cloneInfo.put("profiles", profileViews(clone.list()));
        body.put("clone", cloneInfo);
        return body;
    }

    @PutMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        voice.update(body);
        if (body.get("cloneEnabled") instanceof Boolean b) {
            clone.setEnabled(b);
        }
        if (body.get("dailyLimit") instanceof Number n) {
            int limit = Math.max(0, Math.min(50, n.intValue()));
            props.getVoice().setDailyLimit(limit);
            settingsRef.set("voice.dailyLimit", String.valueOf(limit));
        }
        if (body.get("styleLlm") instanceof Boolean b) {
            settingsRef.set("voice.styleLlm", b.toString());
        }
        if (body.get("chatProbability") instanceof Number n) {
            double p = Math.max(0, Math.min(1, n.doubleValue()));
            props.getVoice().setChatProbability(p);
            settingsRef.set("voice.chatProbability", String.valueOf(p));
        }
        return config();
    }

    /** 合成 → 返回 fileUrl 供页面播放 → 同时发一条语音到你 QQ */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        String text = body != null && body.get("text") != null
                ? String.valueOf(body.get("text"))
                : "你好呀，这是一条语音测试，能听清吗？";
        if (!voice.configured()) {
            return Map.of("code", "TTS_NOT_CONFIGURED", "message", "未配置 MIMO_API_KEY");
        }
        try {
            byte[] wav = voice.synthesizeStyled(text, "用真人发语音的自然口语语气说这句话，带一点情绪，不要播音腔。");
            String file = voice.saveAudio(wav);
            boolean sent = false;
            long uid = users.resolve(null);
            var c = registry.current();
            if (c != null && c.isConnected()) {
                c.sendPrivateRecord(uid, wav);
                sent = true;
                messages.add(uid, "assistant", "（语音测试）" + text, "voice-test");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("fileUrl", "/api/voice/audio/" + file);
            result.put("bytes", wav.length);
            result.put("text", text);
            result.put("sentTo", sent ? uid : null);
            return result;
        } catch (IllegalStateException e) {
            return Map.of("code", "TTS_FAILED", "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("code", "TTS_FAILED", "message", String.valueOf(e.getMessage()));
        }
    }

    /** 调试：把一段文字用语音发到 QQ（模拟聊天里发语音） */
    @PostMapping("/chat-test")
    public Map<String, Object> chatTest(@RequestBody(required = false) Map<String, Object> body) {
        String text = body != null && body.get("text") != null
                ? String.valueOf(body.get("text"))
                : "刚忙完，看到你消息啦，今天怎么样？";
        if (!voice.configured()) {
            return Map.of("code", "TTS_NOT_CONFIGURED", "message", "未配置 MIMO_API_KEY");
        }
        try {
            byte[] wav = voice.synthesizeStyled(text, "用真人发语音的自然口语语气说这句话，带一点情绪，不要播音腔。");
            long uid = users.resolve(null);
            var c2 = registry.current();
            if (c2 != null) { c2.sendPrivateRecord(uid, wav); }
            messages.add(uid, "assistant", "（语音测试）" + text, "voice-test");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("sentTo", uid);
            out.put("text", text);
            out.put("bytes", wav.length);
            return out;
        } catch (Exception e) {
            return Map.of("code", "TTS_FAILED", "message", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 上传音色样本：转码 → 质检 → 入库 → 立刻用新音色合成一句试听。
     * 质检不通过时返回 ok=false + code=AUDIO_REJECTED，前端据此展示原因。
     */
    @PostMapping("/clone")
    public Map<String, Object> uploadClone(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) String name) {
        VoiceCloneService.SaveResult result;
        try {
            result = clone.register(file, name);
        } catch (IllegalArgumentException e) {
            return Map.of("code", "INVALID_AUDIO", "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("code", "CLONE_FAILED", "message", String.valueOf(e.getMessage()));
        }

        AudioAnalysis analysis = result.analysis();
        if (result.profile() == null) {
            String message = analysis.issues().isEmpty() ? "音频不合格" : analysis.issues().get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "AUDIO_REJECTED");
            out.put("message", message);
            out.put("analysis", analysis);
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("profile", profileView(result.profile()));
        out.put("analysis", analysis);

        String previewUrl = null;
        String previewError = null;
        if (!voice.configured()) {
            previewError = "未配置 MIMO_API_KEY，暂不能试听";
        } else {
            try {
                String ref = clone.refDataUrl(result.profile().id());
                if (ref == null) {
                    throw new IllegalStateException("样本文件不存在");
                }
                byte[] wav = voice.synthesize(PREVIEW_TEXT, ref);
                previewUrl = "/api/voice/audio/" + voice.saveAudio(wav);
            } catch (Exception e) {
                previewError = "试听生成失败：" + e.getMessage();
            }
        }
        out.put("previewUrl", previewUrl);
        out.put("previewError", previewError);
        return out;
    }

    /** 音色库列表 */
    @GetMapping("/clones")
    public Map<String, Object> clones() {
        VoiceProfile active = clone.active();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("activeId", active == null ? null : active.id());
        out.put("profiles", profileViews(clone.list()));
        return out;
    }

    /** 切换当前音色 */
    @PutMapping("/clones/{id}/activate")
    public Map<String, Object> activateClone(@PathVariable String id) {
        if (!clone.activate(id)) {
            return Map.of("code", "PROFILE_NOT_FOUND", "message", "音色不存在");
        }
        return Map.of("ok", true, "activeId", id);
    }

    /** 试听指定音色 */
    @PostMapping("/clones/{id}/preview")
    public Map<String, Object> previewClone(@PathVariable String id) {
        if (clone.find(id) == null) {
            return Map.of("code", "PROFILE_NOT_FOUND", "message", "音色不存在");
        }
        if (!voice.configured()) {
            return Map.of("code", "TTS_NOT_CONFIGURED", "message", "未配置 MIMO_API_KEY");
        }
        try {
            String ref = clone.refDataUrl(id);
            if (ref == null) {
                return Map.of("code", "SAMPLE_MISSING", "message", "音色样本文件不存在，请重新上传");
            }
            byte[] wav = voice.synthesize(PREVIEW_TEXT, ref);
            return Map.of("ok", true, "fileUrl", "/api/voice/audio/" + voice.saveAudio(wav));
        } catch (Exception e) {
            return Map.of("code", "TTS_FAILED", "message", String.valueOf(e.getMessage()));
        }
    }

    /** 删除指定音色 */
    @DeleteMapping("/clones/{id}")
    public Map<String, Object> deleteClone(@PathVariable String id) {
        if (!clone.delete(id)) {
            return Map.of("code", "PROFILE_NOT_FOUND", "message", "音色不存在");
        }
        VoiceProfile active = clone.active();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("activeId", active == null ? null : active.id());
        out.put("profiles", profileViews(clone.list()));
        return out;
    }

    /** 兼容旧前端：删掉当前音色 */
    @DeleteMapping("/clone")
    public Map<String, Object> clearClone() {
        clone.clear();
        return Map.of("ok", true);
    }

    @GetMapping("/audio/{name}")
    public ResponseEntity<byte[]> audio(@PathVariable String name) {
        if (!name.matches("[a-f0-9]{32}\\.wav")) {
            return ResponseEntity.badRequest().build();
        }
        Path path = Path.of("data/voice").toAbsolutePath().resolve(name);
        try {
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .body(Files.readAllBytes(path));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 只暴露展示需要的字段，绝对路径不下发前端 */
    private List<Map<String, Object>> profileViews(List<VoiceProfile> profiles) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (VoiceProfile p : profiles) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", p.id());
            view.put("name", p.name());
            view.put("seconds", p.seconds());
            view.put("sampleRate", p.sampleRate());
            view.put("verdict", p.verdict());
            view.put("issues", p.issues());
            view.put("transcript", p.transcript());
            view.put("createdAt", p.createdAt());
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> profileView(VoiceProfile p) {
        return profileViews(List.of(p)).get(0);
    }
}
