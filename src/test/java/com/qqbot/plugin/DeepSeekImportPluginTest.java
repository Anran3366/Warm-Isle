package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.imports.ChatTranscriptParser;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.ManifestIssue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekImportPluginTest {

    @Test
    void manifestAndParserWork() throws Exception {
        byte[] json = Files.readAllBytes(Path.of("packs/menran.deepseek-import/manifest.json"));
        ManifestParser.Result r = ManifestParser.parse(new ObjectMapper(), json);
        List<ManifestIssue> errors = r.issues().stream().filter(ManifestIssue::isError).toList();
        assertTrue(r.ok(), "导入插件 manifest 校验失败: " + errors);
        assertTrue(r.manifest().importsOrEmpty().get(0).perUser());
        Path dir = Path.of("packs/menran.deepseek-import");
        var scan = com.qqbot.plugin.pkg.PromptTemplateScanner.scan(r.manifest(), dir);
        scan.addAll(com.qqbot.plugin.pkg.PromptTemplateScanner.scanImports(r.manifest(), dir));
        assertTrue(scan.isEmpty(), "导入插件 prompt 扫描失败: " + scan);

        String sample = """
                {
                  "title": "测试对话",
                  "messages": [
                    {"role":"user","content":"以后你叫林深，是我大学朋友"},
                    {"role":"assistant","content":"好，我是林深。"},
                    {"role":"user","content":"我生日是 8 月 12 日，喜欢吃辣"}
                  ]
                }
                """;
        var transcript = new ChatTranscriptParser().parse("chat.json",
                sample.getBytes(StandardCharsets.UTF_8));
        assertEquals("测试对话", transcript.title());
        assertEquals(3, transcript.messages().size());
        assertEquals("assistant", transcript.messages().get(1).role());
        assertTrue(transcript.messages().get(0).content().contains("林深"));
    }
}
