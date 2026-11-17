package com.qqbot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 称呼纠正守卫：用户说「我不叫X」「别叫我X」时，立刻把 X 从事实卡和记忆里停用，
 * 避免下一次回复或主动消息还继续叫错。
 *
 * <p>之前的问题：纠正只被夜间整理写成一条新记忆（key=称呼），而旧的
 * 昵称=X 仍然 active；主动消息的 prompt 会把所有 active 事实都塞进去，
 * 于是第二天照旧叫错。这里做的是「实时失效」，不依赖 LLM 抽取。</p>
 */
@Service
public class CorrectionGuard {

    private static final Logger log = LoggerFactory.getLogger(CorrectionGuard.class);

    /**
     * 「我不叫超威」「我不是超威」「别叫我超威」「不要喊我超威」「以后别叫超威」…
     * 只取一个候选名字，是否真的处理由「是否存在同名事实/记忆」决定，
     * 所以「我不叫外卖」这类句子不会误伤。
     */
    private static final Pattern DENY = Pattern.compile(
            "(?:我(?:也)?不叫|我不是|我不姓|别(?:再)?叫我|别喊我|不要叫我|不要喊我|别再叫|以后别叫|不叫)"
                    + "\\s*(?:我)?\\s*[「“\\\"']?([\\u4e00-\\u9fa5A-Za-z0-9·]{1,8})");

    private static final Pattern TAIL = Pattern.compile("(?:了|啦|呀|啊|哦|嘛|的)+$");

    private final FactStore facts;
    private final MemoryStore memories;

    public CorrectionGuard(FactStore facts, MemoryStore memories) {
        this.facts = facts;
        this.memories = memories;
    }


    /** 称呼类 key：只有这些 key 的「否认」才会触发失效，避免误伤其他事实。 */
    public static boolean isNameKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.trim();
        return k.equals("昵称") || k.equals("称呼") || k.equals("名字");
    }

    /**
     * 判断原话里是否真的出现过「我叫X / 叫我X / 我的名字是X」这类明确自报。
     *
     * <p>用来挡住「我超威，昨天喝多了」被模型脑补成昵称=超威——那只是语气/笔误，
     * 不是自我介绍。抽取器在写入称呼类事实前都要过这一关。</p>
     */
    public static boolean explicitlyIntroduced(String transcript, String value) {
        if (transcript == null || transcript.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile(
                "(?:我叫|(?<!别)(?<!要)叫我|称呼我(?:为)?|我的名字(?:是|叫)|名字(?:是|叫)|我名字叫)"
                        + "\\s*[「“\\\"']?" + Pattern.quote(value.trim()));
        return p.matcher(transcript).find();
    }

    /** 每条用户消息进入模型前调用；没有命中就只做一次正则匹配，开销极小。 */
    public void observe(long qqId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher m = DENY.matcher(text);
        while (m.find()) {
            String name = TAIL.matcher(m.group(1).trim()).replaceFirst("");
            if (name.length() < 1 || name.length() > 8) {
                continue;
            }
            int factHits = facts.deactivateValue(qqId, name);
            int memoryHits = memories.archiveByValue(qqId, name);
            if (factHits + memoryHits == 0) {
                // 没有同名记录：可能只是「我不叫外卖」这类日常表达，不处理
                continue;
            }
            memories.upsert(qqId, new MemoryStore.Draft(
                    "profile", "user", "称呼", "不要叫「" + name + "」", "correction",
                    0.85, 0.95, 0.1, null, null,
                    "对方明确否认过这个称呼；不确定名字时先问，或直接用「你」"));
            log.info("称呼纠正 qqId={} 否认={} 停用事实={} 条、记忆={} 条", qqId, name, factHits, memoryHits);
        }
    }
}
