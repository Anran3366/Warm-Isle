package com.qqbot.emoji;

import com.qqbot.image.VisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 手动上传表情时，也走一遍视觉模型 + 标签模型：
 * 生成一句话描述（画面/情绪/场景）和情绪/场景标签，失败就留空，不阻塞上传。
 */
@Service
public class EmojiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(EmojiAnalyzer.class);

    public record Analysis(String emoTags, String sceneTags, String description) {
    }

    private final VisionClient vision;
    private final EmojiTagger tagger;

    public EmojiAnalyzer(VisionClient vision, EmojiTagger tagger) {
        this.vision = vision;
        this.tagger = tagger;
    }

    public Analysis analyze(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            byte[] jpeg = toJpeg(raw);
            if (jpeg == null) {
                return null;
            }
            String desc = vision.describe(Base64.getEncoder().encodeToString(jpeg), "image/jpeg");
            if (desc == null || desc.isBlank() || desc.contains(VisionClient.SENSITIVE)) {
                return null;
            }
            EmojiTagger.Tags tags = tagger.tag(desc);
            String structured = "画面：" + desc + "；情绪：" + tags.emo() + "；场景：" + tags.scene();
            return new Analysis(tags.emo(), tags.scene(), structured);
        } catch (Exception e) {
            log.warn("表情自动识别失败: {}", e.getMessage());
            return null;
        }
    }

    private byte[] toJpeg(byte[] raw) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                return null;
            }
            int max = 1024;
            int w = img.getWidth();
            int h = img.getHeight();
            double scale = Math.min(1.0, (double) max / Math.max(w, h));
            BufferedImage out = img;
            if (scale < 1.0) {
                int nw = Math.max(1, (int) (w * scale));
                int nh = Math.max(1, (int) (h * scale));
                out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                out.getGraphics().drawImage(img.getScaledInstance(nw, nh, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
