package com.qqbot.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 判断一条说说该给谁看：
 * public  所有好友（或所有人，取决于配置）
 * targeted 只给影响心情的那个用户（QQ 空间“部分好友可见”）
 * private 仅自己可见
 * skip    不发
 */
@Service
public class QzoneAudienceDecider {

    private static final Logger log = LoggerFactory.getLogger(QzoneAudienceDecider.class);

    private final ExpressionConfig config;
    private final DeepSeekClient llm;
    private final ObjectMapper mapper;

    public QzoneAudienceDecider(ExpressionConfig config, DeepSeekClient llm, ObjectMapper mapper) {
        this.config = config;
        this.llm = llm;
        this.mapper = mapper;
    }

    public QzoneDecision decide(String content, long qqId, boolean hasTarget, String mood) {
        String mode = config.audienceMode();
        if ("public".equals(mode)) {
            return publicDecision("配置指定公开");
        }
        if ("private".equals(mode)) {
            return privateDecision("配置指定仅自己");
        }
        if ("targeted".equals(mode)) {
            return hasTarget
                    ? targetedDecision(qqId, "配置指定只给该用户")
                    : privateDecision("配置要求定向，但没有目标用户");
        }

        QzoneDecision generated = decideByModel(content, qqId, hasTarget, mood);
        if (generated != null) {
            return generated;
        }
        return heuristic(content, qqId, hasTarget, mood);
    }

    private QzoneDecision decideByModel(String content, long qqId, boolean hasTarget, String mood) {
        try {
            String prompt = """
                    你是一个 QQ 空间说说发布助手。请判断下面这条说说适合给谁看。
                    
                    可选：
                    - public：所有好友都能看，适合生活分享、天气、兴趣、没有指向性的心情。
                    - targeted：只给某一个好友看，适合这条内容明显和某个人有关、或只想让 TA 看到。
                    - private：仅自己可见，适合太私人、太低落、可能让人担心或引起误会的内容。
                    - skip：不适合发。
                    
                    只输出 JSON，不要解释：
                    {"audience":"public|targeted|private|skip","reason":"不超过30字"}
                    
                    说说内容：
                    %s
                    
                    背景：这条说说由用户 %d 的聊天/心情触发；是否有明确目标用户：%s；当前心情：%s。
                    不要根据背景编造内容里没有的隐私细节。
                    """.formatted(content, qqId, hasTarget ? "是" : "否", mood == null ? "平静" : mood);
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content", "你只输出合法 JSON。"),
                    Map.of("role", "user", "content", prompt)
            ), 0.2, 120);
            String raw = result.content() == null ? "" : result.content().trim();
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = mapper.readTree(raw.substring(start, end + 1));
            String audience = node.path("audience").asText("").toLowerCase();
            String reason = node.path("reason").asText("模型判断");
            return switch (audience) {
                case "public" -> publicDecision(reason);
                case "targeted" -> hasTarget ? targetedDecision(qqId, reason) : privateDecision(reason);
                case "private" -> privateDecision(reason);
                case "skip" -> new QzoneDecision(QzoneAudience.SKIP, 0, List.of(), reason);
                default -> null;
            };
        } catch (Exception e) {
            log.debug("说说受众模型判断失败，回退规则: {}", e.getMessage());
            return null;
        }
    }

    private QzoneDecision heuristic(String content, long qqId, boolean hasTarget, String mood) {
        if (content == null || content.isBlank()) {
            return new QzoneDecision(QzoneAudience.SKIP, 0, List.of(), "内容为空");
        }
        boolean personal = content.contains("你") || content.contains("我们")
                || content.contains("昨天") || content.contains("她") || content.contains("他");
        boolean low = mood != null && (mood.contains("低落") || mood.contains("sulky") || mood.contains("难过"));
        if (low) {
            return hasTarget ? targetedDecision(qqId, "情绪低落，先只给相关用户看")
                    : privateDecision("情绪低落，先仅自己可见");
        }
        if (personal && hasTarget) {
            return targetedDecision(qqId, "内容有明确指向，只给相关用户看");
        }
        return publicDecision("普通生活分享");
    }

    private QzoneDecision publicDecision(String reason) {
        return new QzoneDecision(QzoneAudience.PUBLIC, config.publicUgcRight(), List.of(), reason);
    }

    private QzoneDecision targetedDecision(long qqId, String reason) {
        List<Long> targets = new ArrayList<>();
        if (qqId > 0) {
            targets.add(qqId);
        }
        if (targets.isEmpty()) {
            return privateDecision("没有有效的目标用户");
        }
        return new QzoneDecision(QzoneAudience.TARGETED, 16, targets, reason);
    }

    private QzoneDecision privateDecision(String reason) {
        return new QzoneDecision(QzoneAudience.PRIVATE, 64, List.of(), reason);
    }

    public enum QzoneAudience {
        PUBLIC, TARGETED, PRIVATE, SKIP
    }

    public record QzoneDecision(QzoneAudience audience, int ugcRight,
                                List<Long> targetUins, String reason) {
    }
}
