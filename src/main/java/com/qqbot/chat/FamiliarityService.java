package com.qqbot.chat;

import com.qqbot.memory.MessageStore;
import org.springframework.stereotype.Service;

/**
 * 熟悉度：按聊天总量和认识天数给一个「关系阶段」，注入提示词影响语气。
 */
@Service
public class FamiliarityService {

    private final MessageStore messages;

    public FamiliarityService(MessageStore messages) {
        this.messages = messages;
    }

    /** 后台展示：等级、消息数、认识天数、进度 */
    public java.util.Map<String, Object> levelInfo(long qqId) {
        int count = messages.countUser(qqId);
        int days = messages.daysSinceFirst(qqId);
        int active = messages.activeDays(qqId);
        int score = count + active * 30;
        int level;
        String label;
        if (score <= 10) {
            level = 1; label = "刚认识";
        } else if (score < 60) {
            level = 2; label = "聊过几次";
        } else if (score < 300) {
            level = 3; label = "比较熟";
        } else {
            level = 4; label = "很熟";
        }
        int progress = Math.min(100, (int) Math.round(Math.min(1.0, count / 300.0) * 100));
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("level", level);
        m.put("label", label);
        m.put("messageCount", count);
        m.put("days", days);
        m.put("progress", progress);
        m.put("hint", hint(qqId));
        return m;
    }

    /** 给提示词用的一句话 */
    public String hint(long qqId) {
        int count = messages.countUser(qqId);
        int days = messages.daysSinceFirst(qqId);
        int active = messages.activeDays(qqId);
        int score = count + active * 30;
        if (score <= 10) {
            return "关系阶段：刚认识。语气客气一点，别太自来熟，不随便开玩笑。";
        }
        if (score < 60) {
            return "关系阶段：聊过几次。可以放松一点，开始有一点玩笑，但不要过分亲昵。";
        }
        if (score < 300) {
            return "关系阶段：比较熟。可以互怼、起外号、说话更短更直接，不用每句都客气。";
        }
        return "关系阶段：很熟。说话随意，会拆台、会怼人、也会直接关心；不用解释太多。";
    }
}
