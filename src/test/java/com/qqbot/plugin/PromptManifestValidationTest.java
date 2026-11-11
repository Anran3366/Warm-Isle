package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.manifest.ManifestValidator;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** prompt 型的 manifest 语义校验（§5.3 / §10.6） */
class PromptManifestValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PluginManifest realFixture() throws Exception {
        return mapper.readValue(
                Files.readAllBytes(Path.of("packs/demo.tarot/manifest.json")), PluginManifest.class);
    }

    private PluginManifest parse(String json) throws Exception {
        return mapper.readValue(json, PluginManifest.class);
    }

    @Test
    void realTarotFixturePasses() throws Exception {
        List<ManifestIssue> issues = ManifestValidator.validate(realFixture());
        assertTrue(issues.isEmpty(), "官方夹具不该有校验问题: " + issues);
    }

    @Test
    void missingTemplateIsRejected() throws Exception {
        PluginManifest m = parse("""
                {"schemaVersion":2,"id":"t.p","name":"测测","version":"1.0.0","apiVersion":1,
                 "runtime":"prompt","description":"测试","license":"MIT","tags":["测试"],
                 "compat":{"minHostVersion":"1.0.0"},
                 "intent":{"id":"t.p","examples":["测试一句话","再来一句话","还有一句话",
                   "继续一句话","最后一句测试话"]},
                 "actions":[{"name":"go","returns":["text"]}]}
                """);
        assertTrue(ManifestValidator.validate(m).stream()
                .anyMatch(i -> "PROMPT_TEMPLATE_MISSING".equals(i.code())));
    }

    @Test
    void badTemplatePathIsRejected() throws Exception {
        PluginManifest m = parse(base("""
                "prompt":{"system":"../escape.md","maxTokens":600}
                """, ""));
        assertTrue(ManifestValidator.validate(m).stream()
                .anyMatch(i -> "BAD_TEMPLATE_PATH".equals(i.code())));
    }

    @Test
    void maxTokensOver1000IsRejected() throws Exception {
        PluginManifest m = parse(base("""
                "prompt":{"system":"prompts/t.md","maxTokens":5000}
                """, ""));
        assertTrue(ManifestValidator.validate(m).stream()
                .anyMatch(i -> "BAD_MAX_TOKENS".equals(i.code())));
    }

    @Test
    void variableOutsideWhitelistIsRejected() throws Exception {
        PluginManifest m = parse(base("""
                "prompt":{"system":"prompts/t.md","maxTokens":600}
                """, """
                ,"variables":["date","admin_password"]
                """));
        assertTrue(ManifestValidator.validate(m).stream()
                .anyMatch(i -> "VARIABLE_NOT_ALLOWED".equals(i.code())));
    }

    @Test
    void dataKeyAndParamAreAllowedVariables() throws Exception {
        // 夹具里 variables=[date,deck,topic]：deck 来自 data，topic 来自 action 参数
        List<ManifestIssue> issues = ManifestValidator.validate(realFixture());
        assertTrue(issues.stream().noneMatch(i -> i.code().startsWith("VARIABLE")),
                "data 键与 action 参数应该合法: " + issues);
    }

    @Test
    void badDataPathIsRejected() throws Exception {
        PluginManifest m = parse(base("""
                "prompt":{"system":"prompts/t.md","maxTokens":600}
                """, """
                ,"data":{"deck":"../etc/passwd"}
                """));
        assertTrue(ManifestValidator.validate(m).stream()
                .anyMatch(i -> "BAD_DATA_PATH".equals(i.code())));
    }

    private String base(String promptBlock, String extra) {
        return """
                {"schemaVersion":2,"id":"t.p","name":"测测","version":"1.0.0","apiVersion":1,
                 "runtime":"prompt","description":"测试","license":"MIT","tags":["测试"],
                 "compat":{"minHostVersion":"1.0.0"},
                 "intent":{"id":"t.p","examples":["测试一句话","再来一句话","还有一句话",
                   "继续一句话","最后一句测试话"]},
                 "actions":[{"name":"go","params":{"topic":{"type":"string"}},"returns":["text"]}],
                """ + promptBlock + extra + "}";
    }
}
