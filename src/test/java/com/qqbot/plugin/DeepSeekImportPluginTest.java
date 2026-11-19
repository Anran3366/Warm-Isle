package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.imports.ChatTranscriptParser;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.ManifestIssue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @Test
    void markdownHtmlAndZipAlsoParse() throws Exception {
        var parser = new ChatTranscriptParser();
        var md = parser.parse("chat.md", """
                ## User
                在吗
                ## Assistant
                在的
                """.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, md.messages().size());
        assertEquals("user", md.messages().get(0).role());

        var html = parser.parse("chat.html", """
                <html><body><p>用户：今天好累</p><p>助手：怎么啦</p></body></html>
                """.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, html.messages().size());

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("a.json"));
            zip.write("""
                    {"title":"压缩包","messages":[{"role":"user","content":"你好"},{"role":"assistant","content":"你好呀"}]}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var zip = parser.parse("chat.zip", zipBytes.toByteArray());
        assertEquals("压缩包", zip.title());
        assertEquals(2, zip.messages().size());
    }
}
