package com.qqbot.voice;

import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音色克隆：上传样本 → ffmpeg 转 24k 单声道 WAV → 质检（时长/音量/静音/ASR 人声校验）
 * → 入库成可命名的音色 → 合成时把该样本作为 DataURL 传给 MiMo 的 voiceclone 模型。
 * 支持多音色库，同一时刻只有一个"当前音色"。
 */
@Service
public class VoiceCloneService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloneService.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 入库结果：被拒时 profile 为 null，analysis 里带原因 */
    public record SaveResult(VoiceProfile profile, AudioAnalysis analysis) {
    }

    private record CachedRef(String file, long modified, long size, String dataUrl) {
    }

    private final SettingStore settings;
    private final VoiceProfileStore store;
    private final AudioAnalyzer analyzer;
    private final Map<String, CachedRef> refCache = new ConcurrentHashMap<>();

    public VoiceCloneService(SettingStore settings, VoiceProfileStore store, AudioAnalyzer analyzer) {
        this.settings = settings;
        this.store = store;
        this.analyzer = analyzer;
    }

    public List<VoiceProfile> list() {
        return store.list();
    }

    public VoiceProfile active() {
        return store.active();
    }

    public VoiceProfile find(String id) {
        return store.find(id);
    }

    public boolean hasRef() {
        VoiceProfile p = store.active();
        return p != null && Files.exists(Path.of(p.file()));
    }

    public String name() {
        VoiceProfile p = store.active();
        return p == null ? "我的音色" : p.name();
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.get("voice.cloneEnabled", "true"));
    }

    public void setEnabled(boolean enabled) {
        settings.set("voice.cloneEnabled", String.valueOf(enabled));
    }

    /** 合成用的 DataURL；没有音色返回 null */
    public String refDataUrl() {
        VoiceProfile p = store.active();
        return p == null ? null : refDataUrl(p.id());
    }

    public String refDataUrl(String id) {
        VoiceProfile p = store.find(id);
        if (p == null) {
            return null;
        }
        Path path = Path.of(p.file());
        try {
            if (!Files.exists(path)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            CachedRef cached = refCache.get(p.id());
            if (cached != null && cached.modified() == modified && cached.size() == size
                    && cached.file().equals(p.file())) {
                return cached.dataUrl();
            }
            byte[] bytes = Files.readAllBytes(path);
            String dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes);
            refCache.put(p.id(), new CachedRef(p.file(), modified, size, dataUrl));
            return dataUrl;
        } catch (Exception e) {
            log.warn("读取音色样本失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 上传 + 转码 + 质检 + 入库。质检不通过时不会入库，返回带 reject 结论的分析结果。
     */
    public SaveResult register(MultipartFile file, String cloneName) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("没有收到音频文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("音频不能超过 10MB");
        }
        Path dir = Path.of("data/voice/ref").toAbsolutePath();
        Files.createDirectories(dir);
        String key = UUID.randomUUID().toString().replace("-", "");
        Path raw = dir.resolve(key + ".upload");
        file.transferTo(raw);

        // 先看时长：1 小时的录音转成 WAV 会撑爆磁盘，也没必要
        double rawSeconds = analyzer.probeSeconds(raw);
        if (rawSeconds > 60) {
            Files.deleteIfExists(raw);
            AudioAnalysis tooLong = new AudioAnalysis(rawSeconds, 0, 0, "", 0, 0, 0, null, "reject",
                    List.of(String.format("音频 %.0f 秒，太长，请截取 5–15 秒（每次合成都要重传，越长越慢）", rawSeconds)));
            return new SaveResult(null, tooLong);
        }

        Path wav = dir.resolve(key + ".wav");
        boolean ok = convertToWav(raw, wav);
        Files.deleteIfExists(raw);
        if (!ok) {
            Files.deleteIfExists(wav);
            throw new IllegalArgumentException("音频转码失败，请上传 wav/mp3/m4a 格式");
        }

        AudioAnalysis analysis;
        try {
            analysis = analyzer.analyze(wav);
        } catch (Exception e) {
            Files.deleteIfExists(wav);
            throw new IllegalStateException("音频质检失败: " + e.getMessage(), e);
        }

        if (analysis.rejected()) {
            Files.deleteIfExists(wav);
            log.info("音色样本被拒绝: {}", analysis.issues());
            return new SaveResult(null, analysis);
        }

        VoiceProfile profile = new VoiceProfile(
                key.substring(0, 16),
                cloneName == null || cloneName.isBlank() ? "我的音色" : cloneName.trim(),
                wav.toString(),
                Math.round(analysis.seconds() * 10) / 10.0,
                analysis.sampleRate(),
                analysis.verdict(),
                analysis.issues(),
                analysis.transcript(),
                LocalDateTime.now().format(TS));

        store.add(profile);
        store.activate(profile.id());
        settings.set("voice.cloneEnabled", "true");
        log.info("音色已入库 {} ({} 秒, {})", profile.name(), profile.seconds(), analysis.verdict());
        return new SaveResult(profile, analysis);
    }

    public boolean activate(String id) {
        return store.activate(id);
    }

    public boolean delete(String id) {
        VoiceProfile removed = store.remove(id);
        if (removed == null) {
            return false;
        }
        refCache.remove(id);
        try {
            Files.deleteIfExists(Path.of(removed.file()));
        } catch (Exception e) {
            log.warn("删除样本文件失败: {}", e.getMessage());
        }
        if (store.list().isEmpty()) {
            settings.set("voice.cloneEnabled", "false");
        }
        return true;
    }

    /** 兼容旧接口：删掉当前音色 */
    public void clear() {
        VoiceProfile p = store.active();
        if (p != null) {
            delete(p.id());
        }
        settings.set("voice.cloneEnabled", "false");
    }

    private boolean convertToWav(Path in, Path out) {
        try {
            Ffmpeg.Exec exec = Ffmpeg.run(60, Ffmpeg.ffmpeg(), "-y", "-i", in.toString(),
                    "-ac", "1", "-ar", "24000", "-acodec", "pcm_s16le", out.toString());
            if (!exec.ok()) {
                log.warn("ffmpeg 转码失败: {}", tail(exec.output()));
            }
            return exec.ok() && Files.exists(out) && Files.size(out) > 1000;
        } catch (Exception e) {
            log.warn("ffmpeg 转码异常: {}", e.getMessage());
            return false;
        }
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(s.length() - 300);
    }
}
