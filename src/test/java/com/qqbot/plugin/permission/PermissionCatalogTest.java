package com.qqbot.plugin.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.model.PluginManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 权限说明书：说清后果、摘要只跟权限走（§7.7） */
class PermissionCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PluginManifest parse(String json) throws Exception {
        return MAPPER.readValue(json, PluginManifest.class);
    }

    @Test
    void hostedPluginDeclaresNetworkAndMemory() throws Exception {
        PluginManifest m = parse("""
                {
                  "id": "x.hosted", "name": "测", "version": "1.0.0", "runtime": "hosted",
                  "permissions": {"network": {"outbound": ["API.Example.com"], "purpose": "查天气"},
                                  "memory": {"write": true}},
                  "memory": {"policy": "episodic", "type": "event"},
                  "quota": {"perUserPerDay": 3}
                }
                """);
        List<PermissionCatalog.Item> items = PermissionCatalog.describe(m);
        assertEquals(2, items.size());

        PermissionCatalog.Item net = items.get(0);
        assertEquals("network.outbound", net.key());
        assertEquals("install+enable", net.phase());
        // 域名要原样展示（小写归一），用户才知道数据发给谁
        assertTrue(net.detail().contains("api.example.com"), net.detail());
        assertTrue(net.detail().contains("不发送聊天内容"), net.detail());

        PermissionCatalog.Item mem = items.get(1);
        assertEquals("memory.write", mem.key());
        assertTrue(mem.detail().contains("每天最多 5 条"), mem.detail());
    }

    @Test
    void localPluginIsHighRiskAndPromptCountsTokens() throws Exception {
        PluginManifest local = parse("""
                {"id": "x.local", "name": "测", "version": "1.0.0", "runtime": "local",
                 "permissions": {"localService": {"allow": true, "reason": "自建 TTS"}}}
                """);
        List<PermissionCatalog.Item> items = PermissionCatalog.describe(local);
        assertEquals(1, items.size());
        assertEquals("localService", items.get(0).key());
        assertEquals("high", items.get(0).level());
        assertTrue(items.get(0).detail().contains("自建 TTS"), items.get(0).detail());

        PluginManifest prompt = parse("""
                {"id": "x.prompt", "name": "测", "version": "1.0.0", "runtime": "prompt",
                 "prompt": {"system": "你是诗人", "maxTokens": 850}}
                """);
        List<PermissionCatalog.Item> pitems = PermissionCatalog.describe(prompt);
        assertEquals(1, pitems.size());
        assertEquals("llm.use", pitems.get(0).key());
        assertEquals("enable", pitems.get(0).phase());
        assertTrue(pitems.get(0).detail().contains("850"), pitems.get(0).detail());
    }

    @Test
    void dataPluginHasNoPermissionButStillHasDigest() throws Exception {
        PluginManifest m = parse("""
                {"id": "x.data", "name": "测", "version": "1.0.0", "runtime": "data",
                 "actions": [{"name": "do", "returns": ["audio"]}]}
                """);
        assertTrue(PermissionCatalog.describe(m).isEmpty());
        assertEquals("none", PermissionCatalog.digest(m));
        assertFalse(PermissionCatalog.blockedInPrivacyMode(m));
        assertTrue(PermissionCatalog.blockedInPrivacyMode(
                parse("{\"id\":\"a\",\"runtime\":\"local\"}")));
    }

    @Test
    void digestTracksPermissionsNotProse() throws Exception {
        String base = """
                {"id": "x", "name": "测", "version": "1.0.0", "runtime": "hosted",
                 "description": "%s",
                 "permissions": {"network": {"outbound": [%s]}}}
                """;
        String a = PermissionCatalog.digest(parse(base.formatted("查天气", "\"a.example.com\"")));
        // 只改描述：不该让所有用户重新确认
        String b = PermissionCatalog.digest(parse(base.formatted("查天气和穿衣建议", "\"a.example.com\"")));
        assertEquals(a, b);

        // 多要一个域名：必须重新确认
        String c = PermissionCatalog.digest(parse(base.formatted("查天气", "\"a.example.com\",\"b.example.com\"")));
        assertNotEquals(a, c);
    }
}
