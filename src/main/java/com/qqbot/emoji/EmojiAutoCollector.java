package com.qqbot.emoji;

import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * 用户发来的图片如果像表情包，就自动收进素材库（保留原格式，gif 不会变静图）。
 * 判定：消息里没有文字 + 图片不大（长边 ≤ 480）+ 构图偏方。
 */
@Service
public class EmojiAutoCollector {

    private static final Logger log = LoggerFactory.getLogger(EmojiAutoCollector.class);

    private final EmojiService emojiService;
    private final SettingStore settings;
    private final EmojiTagger tagger;

    public EmojiAutoCollector(EmojiService emojiService, SettingStore settings, EmojiTagger tagger) {
        this.emojiService = emojiService;
        this.settings = settings;
        this.tagger = tagger;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.get("emoji.autoCollect", "true"));
    }

    /**
     * @param raw       原始图片字节（未压缩，保留动图）
     * @param ext       真实扩展名（gif/png/webp/jpg）
     * @param textEmpty 这条消息是否只有图片、没有文字
     * @return 收进素材库的表情 id，未收集返回 0
     */
    public long maybeCollect(byte[] raw, String ext, boolean textEmpty, String description) {
        if (!enabled() || !textEmpty || raw == null) {
            return 0;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                return 0;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int maxEdge = Math.max(w, h);
            double ratio = (double) w / h;
            boolean squareish = ratio > 0.7 && ratio < 1.45;
            if (maxEdge > 480 || !squareish) {
                return 0;
            }
            EmojiTagger.Tags tags = tagger.tag(description);
            String structured = "画面：" + (description == null ? "表情包" : description)
                    + "；情绪：" + tags.emo() + "；场景：" + tags.scene();
            long id = emojiService.saveBytes(raw, ext, tags.emo(), tags.scene(), structured, "pending");
            log.info("表情包自动入库 id={} ext={} size={}x{} tags={}/{}", id, ext, w, h, tags.emo(), tags.scene());
            return id;
        } catch (Exception e) {
            log.warn("表情自动入库失败: {}", e.getMessage());
            return 0;
        }
    }
}
