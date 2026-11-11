package com.qqbot.plugin.remote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SSRF 与产物格式校验（设计文档 §10.4 六条规则里的 1/2/6） */
class RemoteFetchTest {

    @Test
    void hostMustMatchWhitelistExactly() {
        List<String> allowed = List.of("api.example.com", "cdn.example.org");
        assertTrue(RemoteFetch.hostAllowed("api.example.com", allowed));
        assertTrue(RemoteFetch.hostAllowed("API.Example.com", allowed), "大小写不敏感");
        assertFalse(RemoteFetch.hostAllowed("evil.com", allowed));
        assertFalse(RemoteFetch.hostAllowed("api.example.com.evil.com", allowed), "后缀拼接不能过");
        assertFalse(RemoteFetch.hostAllowed("example.com", allowed), "父域不能自动放行");
    }

    @Test
    void wildcardEntryCoversSubdomainsOnly() {
        List<String> allowed = List.of("*.menran.cn");
        assertTrue(RemoteFetch.hostAllowed("cdn.menran.cn", allowed));
        assertTrue(RemoteFetch.hostAllowed("menran.cn", allowed));
        assertFalse(RemoteFetch.hostAllowed("menran.cn.evil.com", allowed));
        assertFalse(RemoteFetch.hostAllowed("evilmenran.cn", allowed));
    }

    @Test
    void whitelistEntryWithSchemeAndPortIsNormalized() {
        List<String> allowed = List.of("https://api.example.com:443/path");
        assertTrue(RemoteFetch.hostAllowed("api.example.com", allowed));
    }

    @Test
    void emptyWhitelistBlocksEverything() {
        assertFalse(RemoteFetch.hostAllowed("api.example.com", List.of()));
        assertFalse(RemoteFetch.hostAllowed("api.example.com", null));
    }

    @Test
    void privateAndLoopbackTargetsAreRejected() {
        assertNotNull(RemoteFetch.publicOnly("127.0.0.1"));
        assertNotNull(RemoteFetch.publicOnly("localhost"));
        assertNotNull(RemoteFetch.publicOnly("169.254.169.254"), "云元数据必须拦");
        assertNotNull(RemoteFetch.publicOnly("10.0.0.1"));
        assertNotNull(RemoteFetch.publicOnly("192.168.1.1"));
        assertNull(RemoteFetch.publicOnly("8.8.8.8"));
    }

    @Test
    void metadataIsRejectedEvenForLocal() {
        assertNotNull(RemoteFetch.localReject("169.254.169.254"));
        assertNotNull(RemoteFetch.localReject("metadata.google.internal"));
        // 普通私网是允许的：部署者自己的服务就跑在内网
        assertNull(RemoteFetch.localReject("127.0.0.1"));
        assertNull(RemoteFetch.localReject("192.168.1.10"));
    }

    @Test
    void hostedInvokeEndpointMustBePublicHttps443() {
        assertNotNull(RemoteFetch.checkEndpoint("https://127.0.0.1", false, List.of("127.0.0.1")));
        assertNotNull(RemoteFetch.checkEndpoint("http://api.example.com", false, List.of("api.example.com")));
        assertNotNull(RemoteFetch.checkEndpoint("https://api.example.com:8443", false, List.of("api.example.com")));
        assertNotNull(RemoteFetch.checkEndpoint("https://169.254.169.254", false, List.of("169.254.169.254")));
    }

    @Test
    void localInvokeAllowsPrivateButNotMetadata() {
        assertNull(RemoteFetch.checkEndpoint("http://127.0.0.1:8080", true, List.of()));
        assertNull(RemoteFetch.checkEndpoint("http://192.168.1.10:9000", true, List.of()));
        assertNotNull(RemoteFetch.checkEndpoint("http://169.254.169.254", true, List.of()));
    }

    @Test
    void formatSniffing() {
        assertTrue(RemoteFetch.looksLike("audio", "ID3\u0004something".getBytes()));
        assertTrue(RemoteFetch.looksLike("audio", "RIFF....WAVEfmt ".getBytes()), "wav");
        assertTrue(RemoteFetch.looksLike("image", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertTrue(RemoteFetch.looksLike("image", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertFalse(RemoteFetch.looksLike("audio", "<html>nope".getBytes()), "HTML 不能当音频");
        assertTrue(RemoteFetch.looksLike("text", "唱了《小星星》".getBytes()));
        assertFalse(RemoteFetch.looksLike("text", new byte[]{'a', 0, 'b', 'c'}), "含 NUL 视为二进制");
    }
}
