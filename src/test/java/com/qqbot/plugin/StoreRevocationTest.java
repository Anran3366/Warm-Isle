package com.qqbot.plugin;

import com.qqbot.plugin.store.StoreIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 吊销名单匹配：整插件拉黑 / 单版本拉黑 / 未命中（§6.8，签名前只提示不阻断） */
class StoreRevocationTest {

    private StoreIndex index() {
        return new StoreIndex(1, "2026-11-10", 0, List.of(), List.of(
                new StoreIndex.Revocation("bad.plugin", null, "偷数据", "2026-11-10"),
                new StoreIndex.Revocation("demo.tarot", "1.0.0", "这一版有问题", "2026-11-10")));
    }

    @Test
    void wholePluginRevoked() {
        assertTrue(index().revoked("bad.plugin", "1.0.0"));
        assertTrue(index().revoked("bad.plugin", "9.9.9"));
    }

    @Test
    void singleVersionRevoked() {
        assertTrue(index().revoked("demo.tarot", "1.0.0"));
        assertFalse(index().revoked("demo.tarot", "1.0.1"));
    }

    @Test
    void unknownPluginNotRevoked() {
        assertFalse(index().revoked("menran.sing", "1.0.0"));
        assertFalse(index().revoked(null, "1.0.0"));
    }

    @Test
    void emptyIndexRevokesNothing() {
        assertFalse(StoreIndex.empty().revoked("bad.plugin", "1.0.0"));
        assertNull(StoreIndex.empty().revocationOf("bad.plugin", "1.0.0"));
    }

    @Test
    void legacyCacheWithoutRevocationsIsSafe() {
        // 旧版缓存文件没有 revocations 字段：反序列化为 null 时必须按空处理
        StoreIndex legacy = new StoreIndex(1, "x", 0, List.of(), null);
        assertFalse(legacy.revoked("bad.plugin", "1.0.0"));
        assertTrue(legacy.revocationsOrEmpty().isEmpty());
    }

    @Test
    void revocationCarriesReason() {
        StoreIndex.Revocation r = index().revocationOf("bad.plugin", "3.0.0");
        assertNotNull(r);
        assertEquals("偷数据", r.reason());
    }
}
