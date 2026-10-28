package com.qqbot.image;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * 图片理解链路：下载 → 解码 → 压缩 → 视觉模型 → 文本描述。
 *
 * 安全策略：图片不落盘、不存原图；敏感判定交给视觉模型（提示词里要求只回 [SENSITIVE]），
 * 命中后整条消息按「不看」处理。计划中的本地 CLIP 预筛需要 GPU 环境，暂未接入。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    public record Result(String description, boolean blocked, String reason) {
        public static Result empty() {
            return new Result("", false, null);
        }
    }

    private final BotProperties props;
    private final VisionClient vision;
    private final SettingStore settings;
    private final ImageGuardClient guard;
    private final com.qqbot.emoji.EmojiAutoCollector autoCollector;
    private final com.qqbot.budget.BudgetGuard budget;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ImageService(BotProperties props, VisionClient vision, SettingStore settings,
                        ImageGuardClient guard,
                        com.qqbot.emoji.EmojiAutoCollector autoCollector,
                        com.qqbot.budget.BudgetGuard budget) {
        this.props = props;
        this.vision = vision;
        this.settings = settings;
        this.guard = guard;
        this.autoCollector = autoCollector;
        this.budget = budget;
    }

    /**
     * @param urls NapCat 消息段里给出的图片地址（http/https 或本地路径）
     */
    public Result describe(long qqId, List<String> urls) {
        return describe(qqId, urls, false);
    }

    public Result describe(long qqId, List<String> urls, boolean textEmpty) {
        if (!props.getImage().isEnabled() || urls == null || urls.isEmpty()) {
            return Result.empty();
        }

        if (budget.exceeded()) {
            log.info("预算用尽，跳过图片解析");
            return new Result("", false, "budget");
        }

        int used = quotaUsed(qqId);
        if (used >= props.getImage().getDailyLimit()) {
            log.info("qqId={} 今日图片额度已用完（{}）", qqId, used);
            return new Result("", false, "quota");
        }

        int limit = Math.min(props.getImage().getMaxPerMessage(), urls.size());
        List<String> descriptions = new ArrayList<>();
        int processed = 0;

        for (int i = 0; i < limit; i++) {
            if (used + processed >= props.getImage().getDailyLimit()) {
                break;
            }
            String url = urls.get(i);
            byte[] raw = download(url);
            if (raw == null) {
                continue;
            }
            byte[] jpeg = toJpeg(raw);
            if (jpeg == null) {
                continue;
            }
            // 先过本地预筛：命中则整条消息按「不看」处理，图片不会上传云端
            ImageGuardClient.Decision decision = guard.check(jpeg);
            if (decision.sensitive()) {
                log.info("qqId={} 本地预筛拦截: {}", qqId, decision.reason());
                return new Result("", true, "guard:" + decision.reason());
            }
            String desc = vision.describe(Base64.getEncoder().encodeToString(jpeg), "image/jpeg");
            processed++;
            if (desc == null) {
                descriptions.add("（这张图没看清）");
                continue;
            }
            if (desc.contains(VisionClient.SENSITIVE)) {
                addQuota(qqId, processed);
                log.info("qqId={} 图片判定为敏感，已拦截", qqId);
                return new Result("", true, "sensitive");
            }
            descriptions.add(desc);
            autoCollector.maybeCollect(raw, detectExt(raw), textEmpty, desc);
        }

        addQuota(qqId, processed);
        if (descriptions.isEmpty()) {
            return Result.empty();
        }
        return new Result("（用户发来图片：" + String.join("；", descriptions) + "）", false, null);
    }

    // ---------- 内部 ----------

    /** 按文件头判断真实扩展名，保证 gif 动图不被压成静图 */
    private String detectExt(byte[] b) {
        if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "gif";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            return "webp";
        }
        return "jpg";
    }

    /** 下载图片原始字节（换头像用） */
    /** 外部图片安全预筛：转 jpeg 后走 imageguard；异常时按安全处理 */
    public boolean safe(byte[] raw) {
        try {
            byte[] jpeg = toJpeg(raw);
            var decision = guard.check(jpeg);
            return decision == null || !decision.sensitive();
        } catch (Exception e) {
            // guard 不可用时按安全处理，避免搜图整体失败
            return true;
        }
    }

    /** 直接描述 base64 图片（外部搜图审核用） */
    public String describeBase64(String base64Jpeg) {
        try {
            return vision.describe(base64Jpeg, "image/jpeg");
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] fetchRaw(String url) {
        return download(url);
    }

    private byte[] download(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    log.warn("下载图片失败 HTTP {}: {}", response.statusCode(), abbreviate(url));
                    return null;
                }
                byte[] body = response.body();
                if (body.length > props.getImage().getMaxBytes()) {
                    log.warn("图片超过大小上限 {} 字节，忽略", body.length);
                    return null;
                }
                return body;
            }
            java.nio.file.Path path = java.nio.file.Path.of(url.replace("file://", ""));
            if (!java.nio.file.Files.exists(path)) {
                return null;
            }
            if (java.nio.file.Files.size(path) > props.getImage().getMaxBytes()) {
                return null;
            }
            return java.nio.file.Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("读取图片失败: {}", e.getMessage());
            return null;
        }
    }

    /** 等比缩到长边 ≤ maxEdge，再压成 JPEG */
    private byte[] toJpeg(byte[] raw) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                log.warn("无法识别图片格式，忽略");
                return null;
            }
            int maxEdge = props.getImage().getMaxEdge();
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));

            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return null;
            }
            ImageWriter writer = writers.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality((float) props.getImage().getJpegQuality());
                writer.write(null, new IIOImage(scaled, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("图片压缩失败: {}", e.getMessage());
            return null;
        }
    }

    private String quotaKey(long qqId) {
        return "image_quota:" + qqId + ":" + LocalDate.now();
    }

    private int quotaUsed(long qqId) {
        try {
            return Integer.parseInt(settings.get(quotaKey(qqId), "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private void addQuota(long qqId, int count) {
        if (count <= 0) {
            return;
        }
        try {
            settings.set(quotaKey(qqId), String.valueOf(quotaUsed(qqId) + count));
        } catch (Exception e) {
            log.debug("更新图片额度失败: {}", e.getMessage());
        }
    }

    private String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
