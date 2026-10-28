package com.qqbot.api;

import com.qqbot.voice.VoiceFileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 聊天语音回放 */
@RestController
@RequestMapping("/api/voices")
public class VoiceFileController {

    private final VoiceFileService files;

    public VoiceFileController(VoiceFileService files) {
        this.files = files;
    }

    @GetMapping("/{name}")
    public ResponseEntity<byte[]> get(@PathVariable String name) {
        try {
            byte[] bytes = files.read(name);
            return ResponseEntity.ok()
                    .header("Content-Type", files.contentType(name))
                    .header("Cache-Control", "private, max-age=86400")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
