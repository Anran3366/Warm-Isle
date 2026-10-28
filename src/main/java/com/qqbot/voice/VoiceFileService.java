package com.qqbot.voice;

import com.qqbot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 聊天语音落盘（data/voices），后台可回放 */
@Service
public class VoiceFileService {

    private static final Logger log = LoggerFactory.getLogger(VoiceFileService.class);

    private final Path dir;

    public VoiceFileService(BotProperties props) {
        Path base = Path.of(props.getDb().getPath()).toAbsolutePath().getParent();
        this.dir = base.resolve("voices");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("创建语音目录失败: {}", e.getMessage());
        }
    }

    /** @return 文件名 */
    public String save(byte[] bytes, String ext) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Files.write(dir.resolve(name), bytes);
            return name;
        } catch (Exception e) {
            log.warn("保存语音失败: {}", e.getMessage());
            return null;
        }
    }

    public byte[] read(String name) throws Exception {
        if (name == null || !name.matches("[a-f0-9]{32}\\.(mp3|wav)")) {
            throw new IllegalArgumentException("非法文件名");
        }
        return Files.readAllBytes(dir.resolve(name));
    }

    public String contentType(String name) {
        return name != null && name.endsWith(".wav") ? "audio/wav" : "audio/mpeg";
    }
}
