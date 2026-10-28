package com.qqbot.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private static final int MIN_SCORE = 6;
    private static final int MAX_CHARS = 900;

    private final KnowledgeStore store;

    public KnowledgeRetrievalService(KnowledgeStore store) {
        this.store = store;
    }

    /**
     * @return 供 prompt 使用的参考资料块；无相关内容时返回空串
     */
    public String contextFor(String userText) {
        if (userText == null || userText.isBlank() || userText.length() < 4) {
            return "";
        }
        try {
            List<Map<String, Object>> hits = store.search(userText, null, TOP_K * 2);
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (Map<String, Object> hit : hits) {
                if (!"content".equals(hit.get("kind"))) {
                    continue;
                }
                int score = ((Number) hit.get("score")).intValue();
                if (score < MIN_SCORE) {
                    continue;
                }
                String text = String.valueOf(hit.get("text")).trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (sb.length() + text.length() > MAX_CHARS) {
                    text = text.substring(0, Math.max(0, MAX_CHARS - sb.length()));
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
}
