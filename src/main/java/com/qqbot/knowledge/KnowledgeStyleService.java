package com.qqbot.knowledge;

import com.qqbot.memory.SettingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 说话风格轮换。
 * 每 6 小时从知识库里随机挑几条台词样本写入 settings，
 * Prompt 直接读这份固定内容 —— 同一时段前缀稳定，能命中缓存。
 */
@Service
public class KnowledgeStyleService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStyleService.class);
    private static final String KEY = "style.rotation";

    private final KnowledgeStore store;
    private final SettingStore settings;

    public KnowledgeStyleService(KnowledgeStore store, SettingStore settings) {
        this.store = store;
        this.settings = settings;
    }

    @PostConstruct
    public void init() {
        rotate();
    }

    /** 每 6 小时换一批风格样本 */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000L, initialDelay = 10 * 60 * 1000L)
    public void rotate() {
        try {
            List<String> samples = store.randomStyleSamples(null, 3);
            if (samples.isEmpty()) {
                return;
            }
            String joined = String.join("\n---\n", samples);
            String old = settings.get(KEY, "");
            if (!joined.equals(old)) {
                settings.set(KEY, joined);
                log.info("风格样本已轮换: {} 条", samples.size());
            }
        } catch (Exception e) {
            log.warn("轮换风格样本失败: {}", e.getMessage());
        }
    }

    public String current() {
        return settings.get(KEY, "");
    }
}
