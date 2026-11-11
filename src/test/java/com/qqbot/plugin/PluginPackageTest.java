package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.pkg.PackageVerifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 包安全校验 + 官方唱歌包自检（作者照着写的第一份包必须能过自己的体检） */
class PluginPackageTest {

    private static final Path PACK = Path.of("packs/menran.sing");

    @Test
    void officialSingPackPassesItsOwnVerification() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ManifestParser.Result parsed = ManifestParser.parse(mapper,
                Files.readAllBytes(PACK.resolve("manifest.json")));
        assertTrue(parsed.ok(), () -> "官方包体检失败: " + parsed.issues());
        PluginManifest m = parsed.manifest();
        assertEquals("menran.sing", m.id());
        assertEquals("data", m.runtime());
        assertTrue(m.returnsAudio());
        assertEquals("audio", m.voice().strategy());
        assertFalse(m.catalog().entries().isEmpty());
        // 示例句规则：≥3 字（v2.2 N1）
        for (String e : m.intent().examples()) {
            assertTrue(e.trim().length() >= 3, "示例句过短: " + e);
        }
    }

    @Test
    void officialPackExtractsAllEntries() throws Exception {
        byte[] zip = Files.readAllBytes(Path.of("packs/menran.sing-1.0.0.zip"));
        Path target = Files.createTempDirectory("pkg-extract");
        com.qqbot.plugin.pkg.PackageVerifier.extract(zip, target);
        assertTrue(Files.isRegularFile(target.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(target.resolve("README.md")));
        // 关键：流不能被提前关掉，三个音频必须都在
        try (var walk = Files.walk(target)) {
            long wav = walk.filter(p -> p.toString().endsWith(".wav")).count();
            assertEquals(3, wav, "音频文件没有全部解压出来");
        }
    }

    @Test
    void rejectsExecutableInsidePackage() throws Exception {
        byte[] zip = zip(new String[][]{
                {"manifest.json", "{}"},
                {"README.md", "# x"},
                {"run.sh", "echo hi"}
        });
        PackageVerifier.Result r = PackageVerifier.inspect(zip);
        assertFalse(r.ok());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("FORBIDDEN_FILE_TYPE")));
    }

    @Test
    void rejectsPathTraversalAndMissingReadme() throws Exception {
        byte[] zip = zip(new String[][]{
                {"manifest.json", "{}"},
                {"../evil.txt", "x"}
        });
        PackageVerifier.Result r = PackageVerifier.inspect(zip);
        assertFalse(r.ok());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("BAD_ENTRY_PATH")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("README_MISSING")));
    }

    @Test
    void rejectsForbiddenManifestCombinations() {
        String json = """
                {
                  "schemaVersion": 2, "id": "someone.test", "name": "测试", "version": "1.0.0",
                  "apiVersion": 1, "runtime": "data", "description": "测试插件",
                  "license": "MIT", "tags": ["测试"],
                  "compat": {"minHostVersion": "1.0.0"},
                  "intent": {"id": "test.x", "examples": ["测试一下", "测试一下", "试试看", "试一下", "测测"]},
                  "actions": [{"name": "do", "returns": ["audio"], "timeoutMs": 5000}],
                  "permissions": {"memory": {"write": false}},
                  "memory": {"policy": "episodic", "type": "relation",
                             "keyTemplate": "唱过:song", "valueTemplate": "x"},
                  "voice": {"strategy": "audio"}
                }
                """;
        ManifestParser.Result r = ManifestParser.parse(new ObjectMapper(),
                json.getBytes(StandardCharsets.UTF_8));
        assertFalse(r.ok());
        // 音频缺 voice 之外的两个硬伤：memory.policy 非 none 却没声明写权限；key 没有实体占位符
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("MEMORY_WRITE_NOT_DECLARED")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("KEY_NO_ENTITY")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("EXAMPLE_DUPLICATE")));
    }

    private static byte[] zip(String[][] entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
