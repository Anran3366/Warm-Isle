package com.qqbot.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 向量与关键词的融合打分 */
class MemoryVectorTest {

    @Test
    void cosineOfNormalizedVectors() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0.6f, 0.8f, 0f};
        assertEquals(0.6, MemoryVectorService.cosine(a, b), 0.001);
        assertEquals(1.0, MemoryVectorService.cosine(a, a), 0.001);
    }

    @Test
    void cosineHandlesMismatchAndNull() {
        assertEquals(0, MemoryVectorService.cosine(null, new float[]{1f}));
        assertEquals(0, MemoryVectorService.cosine(new float[]{1f}, new float[]{1f, 2f}));
        assertEquals(0, MemoryVectorService.cosine(new float[0], new float[0]));
    }

    @Test
    void blendKeepsKeywordWhenVectorIsNoise() {
        // 0.3 低于阈值 0.45，按无贡献处理
        assertEquals(0.2, MemoryRecall.blend(0.2, 0.3), 0.001);
    }

    @Test
    void blendRewardsSemanticHit() {
        // 关键词 0、语义 0.8 → 0.4，能进候选
        assertEquals(0.4, MemoryRecall.blend(0, 0.8), 0.001);
        // 关键词 0.6、语义 0.8 → 0.7
        assertEquals(0.7, MemoryRecall.blend(0.6, 0.8), 0.001);
    }

    @Test
    void blendNeverLowersKeyword() {
        assertEquals(0.9, MemoryRecall.blend(0.9, 0.5), 0.001);
    }
}
