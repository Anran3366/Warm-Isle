package com.qqbot.emoji;

import com.qqbot.memory.Emoji;
import com.qqbot.memory.EmojiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 表情包文件的落盘与读取（data/emoji，随 data 一起被 gitignore） */
@Service
public class EmojiService {

    private static final Logger log = LoggerFactory.getLogger(EmojiService.class);
    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final EmojiStore store;
    private final Path dir;

    public EmojiService(EmojiStore store) {
        this.store = store;
        this.dir = Path.of(System.getenv().getOrDefault("EMOJI_DIR", "data/emoji")).toAbsolutePath();
    }

    public record Saved(long id, String path) {
    }

    public Saved save(MultipartFile file, String emoTags, String sceneTags, String description, String status) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 10MB");
        }
        String ext = extension(file.getOriginalFilename(), file.getContentType());
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("只支持 png/jpg/gif/webp");
        }
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(name);
        file.transferTo(target);
        long id = store.insert(target.toString(), emoTags, sceneTags, description, true, status, null, null);
        log.info("新增表情 id={} file={} size={}", id, name, file.getSize());
        return new Saved(id, target.toString());
    }

    /** 直接保存字节（聊天中自动收集表情用）；重复图片返回已有 id */
    public long saveBytes(byte[] image, String ext, String emoTags, String sceneTags, String description, String status) throws IOException {
        String hash = sha256(image);
        String ahash = aHash(image);
        long existing = store.findByHash(hash);
        if (existing <= 0) {
            existing = store.findByAhash(ahash);
        }
        if (existing > 0) {
            log.info("表情重复，跳过收集 id={}", existing);
            return existing;
        }
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(name);
        Files.write(target, image);
        long id = store.insert(target.toString(), emoTags, sceneTags, description, true, status, hash, ahash);
        log.info("自动收集表情 id={} file={} size={}", id, name, image.length);
        return id;
    }

    private String sha256(byte[] bytes) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 启动时补齐哈希，并把已经重复的表情删掉 */
    @jakarta.annotation.PostConstruct
    public void dedupeExisting() {
        try {
            java.util.Map<String, Long> seen = new java.util.HashMap<>();
            for (Emoji e : store.all()) {
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(e.path());
                    if (!java.nio.file.Files.exists(p)) {
                        continue;
                    }
                    String hash = sha256(java.nio.file.Files.readAllBytes(p));
                    Long first = seen.get(hash);
                    if (first != null) {
                        store.delete(e.id());
                        Files.deleteIfExists(p);
                        log.info("删除重复表情 id={} 与 id={} 相同", e.id(), first);
                    } else {
                        seen.put(hash, e.id());
                        store.setHash(e.id(), hash);
                        store.setAhash(e.id(), aHash(java.nio.file.Files.readAllBytes(p)));
                    }
                } catch (Exception ignored) {
                    // 单张失败跳过
                }
            }
        } catch (Exception e) {
            log.warn("表情去重失败: {}", e.getMessage());
        }
    }

    /** 平均哈希：8x8 灰度，返回 16 位十六进制字符串 */
    private String aHash(byte[] bytes) {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (src == null) {
                return null;
            }
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(
                    8, 8, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = small.createGraphics();
            g.drawImage(src, 0, 0, 8, 8, null);
            g.dispose();
            long bits = 0;
            int sum = 0;
            int[] px = new int[64];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int v = small.getRGB(x, y) & 0xff;
                    px[y * 8 + x] = v;
                    sum += v;
                }
            }
            int avg = sum / 64;
            for (int i = 0; i < 64; i++) {
                if (px[i] >= avg) {
                    bits |= 1L << i;
                }
            }
            return String.format("%016x", bits);
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] read(Emoji emoji) throws IOException {
        return Files.readAllBytes(Path.of(emoji.path()));
    }

    public void deleteFile(Emoji emoji) {
        try {
            Files.deleteIfExists(Path.of(emoji.path()));
        } catch (Exception e) {
            log.warn("删除表情文件失败: {}", e.getMessage());
        }
    }

    public String contentType(Emoji emoji) {
        String ext = extension(emoji.path(), null);
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private String extension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        if (contentType != null && contentType.contains("/")) {
            String sub = contentType.substring(contentType.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            return "jpeg".equals(sub) ? "jpg" : sub;
        }
        return "";
    }
}
