package com.qqbot.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识检索：用户提问时，从知识库 content 切片里找相关资料。
 * 只取命中分数够高的少量片段，避免把无关内容塞进 prompt。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);
    private static final int TOP_K = 3;
    private static final int MIN_SCORE = 2;
    private static final int MAX_CHARS = 900;

    private final KnowledgeStore store;
    private final com.qqbot.memory.EmbeddingClient embeddings;

    public KnowledgeRetrievalService(KnowledgeStore store,
                                     com.qqbot.memory.EmbeddingClient embeddings) {
        this.store = store;
        this.embeddings = embeddings;
    }

    /**
     * @return 供 prompt 使用的参考资料块；无相关内容时返回空串
     */
    public String contextFor(String userText) {
        if (userText == null || userText.isBlank() || userText.length() < 2) {
            return "";
        }
        try {
            List<Map<String, Object>> hits = store.search(userText, null, TOP_K * 2);
            List<String> candidates = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                if (!"content".equals(hit.get("kind"))) {
                    continue;
                }
                int score = ((Number) hit.get("score")).intValue();
                if (score < MIN_SCORE) {
                    continue;
                }
                String text = String.valueOf(hit.get("text")).trim();
                if (!text.isEmpty()) {
                    candidates.add(text);
                }
            }
            if (candidates.isEmpty()) {
                return "";
            }
            // 语义重排：关键词只看字面，「宠物死了」会匹到「烦死了吧」，余弦能纠正
            List<String> ranked = rerankByVector(userText, candidates);
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (String text : ranked) {
                if (sb.length() + text.length() > MAX_CHARS) {
                    text = text.substring(0, Math.max(0, MAX_CHARS - sb.length()));
                }
                if (text.isEmpty()) {
                    continue;
                }
                sb.append("- ").append(text).append('\n');
                if (++used >= TOP_K) {
                    break;
                }
            }
            if (used == 0) {
                return "";
            }
            log.info("知识检索命中 {} 条（问题长度 {}）", used, userText.length());
            return "下面是知识库里可能相关的资料。只在确实相关时参考，不要照搬原文，也不要提及资料的存在：\n" + sb;
        } catch (Exception e) {
            log.warn("知识检索失败: {}", e.getMessage());
            return "";
        }
    }

    /** 用向量对关键词候选重排；向量服务不可用时保持原顺序 */
    private List<String> rerankByVector(String query, List<String> candidates) {
        try {
            List<String> texts = new ArrayList<>();
            texts.add(query);
            texts.addAll(candidates);
            List<float[]> vecs = embeddings.embed(texts);
            if (vecs.size() != texts.size()) {
                return candidates;
            }
            float[] q = vecs.get(0);
            List<int[]> order = new ArrayList<>();
            for (int i = 1; i < vecs.size(); i++) {
                double cos = com.qqbot.memory.MemoryVectorService.cosine(q, vecs.get(i));
                if (cos >= 0.40) {
                    order.add(new int[]{i - 1, (int) Math.round(cos * 1000)});
                }
            }
            if (order.isEmpty()) {
                return candidates;
            }
            order.sort((a, b) -> Integer.compare(b[1], a[1]));
            List<String> out = new ArrayList<>();
            for (int[] o : order) {
                out.add(candidates.get(o[0]));
            }
            return out;
        } catch (Exception e) {
            return candidates;
        }
    }
}
