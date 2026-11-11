package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.pkg.PackageVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：{@code isXxx()} 派生方法会被 Jackson 当成属性，与同名 manifest 字段撞车。
 *
 * <p>曾经 {@code isHosted()} / {@code isLocal()} / {@code isPrompt()} 加 {@code @JsonIgnore}
 * 之后，反序列化时把整段 {@code hosted} / {@code local} / {@code prompt} 丢掉，
 * 结果是 hosted、local 插件装上后 manifest 里对应段恒为 null，永远调不通。
 */
class ManifestRuntimeSectionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PluginManifest parse(String json) throws Exception {
        return MAPPER.readValue(json.getBytes(StandardCharsets.UTF_8), PluginManifest.class);
    }

    @Test
    void hostedSectionSurvivesParsing() throws Exception {
        PluginManifest m = parse("""
                {"schemaVersion":2,"id":"a.b","name":"甲","version":"1.0.0","apiVersion":1,
                 "runtime":"hosted","hosted":{"endpoint":"https://api.example.com/v1",
                 "health":"/health","auth":"Bearer x"},
                 "permissions":{"network":{"outbound":["api.example.com"]}}}
                """);
        assertTrue(m.usesHosted());
        assertNotNull(m.hosted(), "hosted 段被丢了");
        assertEquals("https://api.example.com/v1", m.hosted().endpoint());
        assertEquals("/health", m.hosted().health());
        assertTrue(m.outboundHosts().contains("api.example.com"));
    }

    @Test
    void localSectionSurvivesParsing() throws Exception {
        PluginManifest m = parse("""
                {"schemaVersion":2,"id":"c.d","name":"乙","version":"1.0.0","apiVersion":1,
                 "runtime":"local","local":{"endpointHint":"http://127.0.0.1:9000",
                 "health":"/health","invoke":"/v1/invoke","setup":"SETUP.md"},
                 "permissions":{"localService":{"allow":true,"reason":"自建"}}}
                """);
        assertTrue(m.usesLocal());
        assertNotNull(m.local(), "local 段被丢了");
        assertEquals("SETUP.md", m.local().setup());
        assertTrue(m.permissions().localService().allow());
    }

    @Test
    void promptSectionSurvivesParsing() throws Exception {
        PluginManifest m = parse("""
                {"schemaVersion":2,"id":"e.f","name":"丙","version":"1.0.0","apiVersion":1,
                 "runtime":"prompt","prompt":{"system":"你是诗人","maxTokens":200,"temperature":0.8}}
                """);
        assertTrue(m.usesPrompt());
        assertNotNull(m.prompt(), "prompt 段被丢了");
        assertEquals("你是诗人", m.prompt().system());
    }

    @Test
    void serializedFormHasNoDerivedProperties() throws Exception {
        PluginManifest m = parse("""
                {"schemaVersion":2,"id":"g.h","name":"丁","version":"1.0.0","apiVersion":1,
                 "runtime":"hosted","hosted":{"endpoint":"https://x.example.com","health":"/h"}}
                """);
        String json = MAPPER.writeValueAsString(m);
        assertFalse(json.contains("\"usesHosted\""), "派生方法不能写进库: " + json);
        assertFalse(json.contains("\"returnsAudio\""));
        PluginManifest back = MAPPER.readValue(json, PluginManifest.class);
        assertNotNull(back.hosted(), "写库再读回来 hosted 不能丢");
    }

    @Test
    void realLocalPackageInstallsThroughValidator() throws Exception {
        // 端到端：真实 zip → 结构校验 → 语义校验，必须 0 error
        String manifest = """
                {"schemaVersion":2,"id":"demo.echo","name":"回声","version":"1.0.0","apiVersion":1,
                 "runtime":"local","description":"测试","readme":"README.md","license":"MIT",
                 "tags":["测试"],"compat":{"minHostVersion":"1.0.0"},
                 "intent":{"id":"demo.echo","examples":["回声测试一下","复读这句话","回声插件测试",
                   "测试回声效果","试一下回声"]},
                 "actions":[{"name":"echo","params":{"text":{"type":"string","required":true}},
                   "returns":["text"],"timeoutMs":10000}],
                 "local":{"endpointHint":"http://127.0.0.1:3600","health":"/health",
                   "invoke":"/v1/invoke","setup":"SETUP.md"},
                 "permissions":{"localService":{"allow":true,"reason":"测试"}}}
                """;
        byte[] zip = zipOf(manifest);
        PackageVerifier.Result pkg = PackageVerifier.inspect(zip);
        assertTrue(pkg.ok(), "结构校验失败: " + pkg.issues());
        ManifestParser.Result parsed = ManifestParser.parse(MAPPER, pkg.manifestJson());
        assertTrue(parsed.ok(), "语义校验失败: " + parsed.issues());
        assertNotNull(parsed.manifest().local());
        assertEquals("SETUP.md", parsed.manifest().local().setup());
        assertTrue(parsed.manifest().usesLocal());
    }

    private static byte[] zipOf(String manifestJson) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zip.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("README.md"));
            zip.write("# 读我".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("SETUP.md"));
            zip.write("# 部署".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
