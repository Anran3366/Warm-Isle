package com.qqbot.voice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 音色库持久化：整体存在 settings 表的一个 JSON 键里，避免为几段元数据单开表。
 * 兼容历史单样本配置（voice.cloneRef / voice.cloneName）：首次读取时自动迁成一条 legacy 记录。
 */
@Component
public class VoiceProfileStore {

    private static final Logger log = LoggerFactory.getLogger(VoiceProfileStore.class);
    private static final String PROFILES_KEY = "voice.profiles";
    private static final String ACTIVE_KEY = "voice.activeProfileId";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SettingStore settings;
    private final ObjectMapper mapper;

    public VoiceProfileStore(SettingStore settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public synchronized List<VoiceProfile> list() {
        migrateLegacy();
        return load();
    }

    public synchronized VoiceProfile active() {
        String id = settings.get(ACTIVE_KEY, null);
        if (id == null || id.isBlank()) {
            return null;
        }
        return list().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized VoiceProfile find(String id) {
        if (id == null) {
            return null;
        }
        return list().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized void add(VoiceProfile profile) {
        List<VoiceProfile> all = new ArrayList<>(list());
        all.removeIf(p -> p.id().equals(profile.id()));
        all.add(0, profile);
        save(all);
    }

    public synchronized boolean activate(String id) {
        if (find(id) == null) {
            return false;
        }
        settings.set(ACTIVE_KEY, id);
        return true;
    }

    public synchronized VoiceProfile remove(String id) {
        List<VoiceProfile> all = new ArrayList<>(list());
        VoiceProfile removed = all.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (removed == null) {
            return null;
        }
        all.removeIf(p -> p.id().equals(id));
        save(all);
        String active = settings.get(ACTIVE_KEY, null);
        if (id.equals(active)) {
            if (all.isEmpty()) {
                settings.set(ACTIVE_KEY, "");
            } else {
                settings.set(ACTIVE_KEY, all.get(0).id());
            }
        }
        return removed;
    }

    private List<VoiceProfile> load() {
        String json = settings.get(PROFILES_KEY, null);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<VoiceProfile>>() {
            });
        } catch (Exception e) {
            log.warn("音色库解析失败，按空库处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void save(List<VoiceProfile> profiles) {
        try {
            settings.set(PROFILES_KEY, mapper.writeValueAsString(profiles));
        } catch (Exception e) {
            throw new IllegalStateException("保存音色库失败: " + e.getMessage(), e);
        }
    }

    /** 旧版本只有一条 voice.cloneRef，升级后不丢样本 */
    private void migrateLegacy() {
        if (settings.get(PROFILES_KEY, null) != null) {
            return;
        }
        String ref = settings.get("voice.cloneRef", null);
        if (ref == null || ref.isBlank()) {
            return;
        }
        Path path = Path.of(ref);
        if (!Files.exists(path)) {
            return;
        }
        double seconds = 0;
        try {
            seconds = Math.round(Files.size(path) / 48000.0 * 10) / 10.0;
        } catch (Exception ignored) {
            // 大小读不到就留 0
        }
        String name = settings.get("voice.cloneName", "我的音色");
        VoiceProfile legacy = new VoiceProfile("legacy", name, path.toString(), seconds, 24000,
                "ok", List.of(), null, LocalDateTime.now().format(TS));
        try {
            save(List.of(legacy));
            settings.set(ACTIVE_KEY, "legacy");
            log.info("已将旧版单音色样本迁移为音色库记录: {}", path.getFileName());
        } catch (Exception e) {
            log.warn("旧音色迁移失败: {}", e.getMessage());
        }
    }
}
