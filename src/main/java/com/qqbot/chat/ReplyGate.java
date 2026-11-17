package com.qqbot.chat;

import com.qqbot.config.BotProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 回复门控：不是每条消息都值得回。
 *
 * <p>单字、纯表情、纯语气词（嗯/哦/哈哈）大概率「已读不回」或只回很短一句；
 * 提问、带问号、连发补充、图片/语音一律正常回。目的是去掉「每条都秒回」的客服感。</p>
 */
@Component
public class ReplyGate {

    public enum Decision { REPLY, SHORT, SKIP }

    /** 纯语气词/应答：嗯、哦、好、哈哈、…… */
    private static final Pattern FILLER = Pattern.compile("[嗯哦噢喔好行啊哈嘿呵呀吧嘛呢了\\s~～.。!！…]+");
    /** 纯表情/符号 */
    private static final Pattern EMOJI_ONLY = Pattern.compile("[\\p{So}\\p{Cn}\\s]+");
    /** 带这些字眼的短句按提问处理，必须回 */
    private static final String[] QUESTION_WORDS = {
            "吗", "呢", "么", "啥", "哪", "谁", "怎么", "为什么", "多少", "几点", "在不在"
    };

    private final BotProperties props;

    public ReplyGate(BotProperties props) {
        this.props = props;
    }

    public Decision decide(String text, boolean hasImage, boolean hasVoice, int batchCount) {
        BotProperties.Reply cfg = props.getReply();
        if (!cfg.isGate() || text == null || text.isBlank()) {
            return Decision.REPLY;
        }
        if (hasImage || hasVoice) {
            return Decision.REPLY;
        }
        String t = text.trim();
        if (t.contains("?") || t.contains("？") || looksLikeQuestion(t)) {
            return Decision.REPLY;
        }
        int len = t.codePointCount(0, t.length());
        boolean filler = FILLER.matcher(t).matches();
        boolean emojiOnly = EMOJI_ONLY.matcher(t).matches();
        if (len <= 1 || emojiOnly || (len <= 4 && filler)) {
            if (batchCount >= 2) {
                // 连发说明还在补充，别装死
                return Decision.REPLY;
            }
            double skip = Math.max(0, Math.min(1.0, cfg.getGateSkip()));
            return ThreadLocalRandom.current().nextDouble() < skip ? Decision.SKIP : Decision.SHORT;
        }
        return Decision.REPLY;
    }

    private static boolean looksLikeQuestion(String t) {
        for (String word : QUESTION_WORDS) {
            if (t.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
