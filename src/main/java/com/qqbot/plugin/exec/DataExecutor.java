package com.qqbot.plugin.exec;

import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * data 型执行器：查表 → 读文件 → 交给宿主发送。
 *
 * <p>包里没有任何可执行物，是最安全的形态；文件路径必须落在插件目录内（防穿越）。
 */
@Component
public class DataExecutor {

    private static final Logger log = LoggerFactory.getLogger(DataExecutor.class);
    private static final long MAX_AUDIO = 1024 * 1024;
    private static final long MAX_IMAGE = 5 * 1024 * 1024;

    /** 执行失败：code 用协议错误码，userHint 直接发给用户（作者写了就用作者的） */
    public static class PluginException extends RuntimeException {
        private final String code;
        private final String userHint;

        public PluginException(String code, String message) {
            this(code, message, null);
        }

        public PluginException(String code, String message, String userHint) {
            super(message);
            this.code = code;
            this.userHint = userHint;
        }

        public String code() {
            return code;
        }

        /** @return 作者提供的用户可见提示，可能为 null（走宿主默认文案） */
        public String userHint() {
            return userHint;
        }
    }

    public PluginOutput execute(InstalledPlugin plugin, String actionName, Map<String, String> params) {
        PluginManifest manifest = plugin.manifest();
        PluginManifest.Catalog catalog = manifest.catalog();
        if (catalog == null || catalog.entries() == null || catalog.entries().isEmpty()) {
            throw new PluginException("BAD_PARAMS", "插件没有可用的素材表");
        }
        String key = catalog.key() == null ? "item" : catalog.key();
        String wanted = params.get(key);
        PluginManifest.Entry entry = pick(catalog, wanted);
        if (entry == null) {
            throw new PluginException("NOT_FOUND", "没有找到「" + wanted + "」");
        }
        if (entry.file() == null || entry.file().isBlank()) {
            throw new PluginException("INTERNAL", "素材缺少文件");
        }
        Path file = plugin.dir().resolve(entry.file()).normalize();
        if (!file.startsWith(plugin.dir())) {
            throw new PluginException("INTERNAL", "非法的素材路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new PluginException("NOT_FOUND", "素材文件不存在");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Exception e) {
            throw new PluginException("INTERNAL", "读取素材失败: " + e.getMessage());
        }
        String kind = kindOf(entry.file());
        long limit = "audio".equals(kind) ? MAX_AUDIO : MAX_IMAGE;
        if (bytes.length > limit) {
            throw new PluginException("OUTPUT_TOO_LARGE",
                    "素材超过上限（" + (limit / 1024 / 1024) + "MB）");
        }
        Map<String, Object> vars = Map.of(
                key, entry.value() == null ? "" : entry.value(),
                "duration", String.valueOf((entry.durationMs() == null ? 0 : entry.durationMs()) / 1000));
        if ("text".equals(kind)) {
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return PluginOutput.text(text, text, vars);
        }
        return new PluginOutput(kind, mimeOf(entry.file()), bytes,
                "text".equals(kind) ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : null,
                entry.durationMs() == null ? 0 : entry.durationMs(), null, vars);
    }

    private PluginManifest.Entry pick(PluginManifest.Catalog catalog, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            List<PluginManifest.Entry> entries = catalog.entries();
            return entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        }
        for (PluginManifest.Entry e : catalog.entries()) {
            if (e == null) {
                continue;
            }
            if (wanted.equals(e.value())) {
                return e;
            }
            if (e.aliases() != null && e.aliases().contains(wanted)) {
                return e;
            }
        }
        for (PluginManifest.Entry e : catalog.entries()) {
            if (e != null && e.value() != null && e.value().contains(wanted)) {
                return e;
            }
        }
        return null;
    }

    private String kindOf(String file) {
        String ext = ext(file);
        return switch (ext) {
            case "mp3", "m4a", "amr", "wav", "ogg", "silk", "flac" -> "audio";
            case "png", "jpg", "jpeg", "gif", "webp" -> "image";
            default -> "text";
        };
    }

    private String mimeOf(String file) {
        return switch (ext(file)) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "amr" -> "audio/amr";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "silk" -> "audio/silk";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "text/plain";
        };
    }

    private String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
