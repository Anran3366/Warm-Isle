package com.qqbot.llm;

import com.qqbot.chat.ChatMessage;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.Persona;
import com.qqbot.memory.PersonaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装 prompt：人设（数据库 active 人设，缺失时回退 .env）→ 检索到的记忆 → 最近对话 → 当前消息。
 *
 * <p>记忆 v2 起不再把全部事实卡塞进 prompt，而是按当前这句话检索 top 8~12 条
 * （见 {@link com.qqbot.memory.MemoryRecall}）；memories 为空时才回退旧的事实卡。
 * 同时记录最近一次实际使用的各段内容，供 /api/prompt/preview 展示。
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_FACTS = 60;

    private final BotProperties props;
    private final FactStore facts;
    private final PersonaStore personas;
    private final com.qqbot.memory.MemoryRecall memoryRecall;

    private volatile LastPrompt last;

    /** factsBlock 是回退用的旧事实卡；正常聊天时为空，memories 才是实际注入的内容 */
    public record LastPrompt(String persona, String factsBlock, String memories, String history,
                             String user, int totalTokens) {
    }

    private final com.qqbot.knowledge.KnowledgeStyleService styles;
    private final com.qqbot.knowledge.KnowledgeRetrievalService retrieval;
    private final com.qqbot.chat.MoodService moods;
    private final com.qqbot.chat.FamiliarityService familiarity;
    private final com.qqbot.chat.LifeEventService lifeEvents;

    public PromptBuilder(BotProperties props, FactStore facts, PersonaStore personas,
                         com.qqbot.chat.FamiliarityService familiarity,
                         com.qqbot.chat.LifeEventService lifeEvents,
                         com.qqbot.knowledge.KnowledgeStyleService styles,
                         com.qqbot.knowledge.KnowledgeRetrievalService retrieval,
                         com.qqbot.chat.MoodService moods,
                         com.qqbot.memory.MemoryRecall memoryRecall) {
        this.props = props;
        this.facts = facts;
        this.personas = personas;
        this.memoryRecall = memoryRecall;
        this.styles = styles;
        this.retrieval = retrieval;
        this.moods = moods;
        this.familiarity = familiarity;
        this.lifeEvents = lifeEvents;
    }

    public List<Map<String, String>> build(long userId, List<ChatMessage> history, String userText) {
        return build(userId, history, userText, -1);
    }

    /**
     * @param gapMinutes 距上一条消息的分钟数；<0 表示未知（首次对话）
     */
    public List<Map<String, String>> build(long userId, List<ChatMessage> history, String userText,
                                           long gapMinutes) {
        String personaText = personaText();
        // 记忆 v2：按这句话检索，而不是把全部事实塞进去
        String memoryBlock = memoryBlock(userId, userText);
        String factsBlock = memoryBlock.isEmpty() ? profileBlock(userId) : "";
        String styleBlock = styleBlock();
        String moodLine = moodLine(userId);
        String familiarityLine = "";
        try {
            familiarityLine = familiarity.hint(userId);
        } catch (Exception ignored) {
            // 熟悉度失败忽略
        }
        String lifeLine = "";
        try {
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.35) {
                String event = lifeEvents.peekToday();
                if (event != null && !event.isBlank()) {
                    lifeLine = "你今天的小事：" + event + "（合适就自然提一句，不硬塞）";
                }
            }
        } catch (Exception ignored) {
            // 生活小事失败忽略
        }
        String adaptive = adaptiveHint(userText);
        String timeHint = timeGapHint(gapMinutes);
        String system = personaText
                + (styleBlock.isEmpty() ? "" : "\n\n" + styleBlock)
                + "\n\n" + SPEAKING_RULES
                + (moodLine.isEmpty() ? "" : "\n" + moodLine)
                + (familiarityLine.isEmpty() ? "" : "\n" + familiarityLine)
                + (lifeLine.isEmpty() ? "" : "\n" + lifeLine)
                + (adaptive.isEmpty() ? "" : "\n" + adaptive)
                + (timeHint.isEmpty() ? "" : "\n" + timeHint)
                + (memoryBlock.isEmpty()
                        ? (factsBlock.isEmpty() ? "" : "\n\n" + factsBlock)
                        : "\n\n" + memoryBlock)
                + "\n当前时间：" + LocalDateTime.now().format(TIME) + "，不要复述时间。";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));

        int maxHistory = Math.max(0, props.getLlm().getHistoryRounds() * 2);
        int from = Math.max(0, history.size() - maxHistory);
        StringBuilder historyText = new StringBuilder();
        StringBuilder mediaNotes = new StringBuilder();
        for (ChatMessage m : history.subList(from, history.size())) {
            if (isMediaMarker(m)) {
                continue;
            }
            historyText.append("user".equals(m.role()) ? "对方：" : "我：")
                    .append(m.content()).append('\n');
        }
        for (ChatMessage m : history.subList(from, history.size())) {
            if (isMediaMarker(m)) {
                if (mediaNotes.length() > 0) {
                    mediaNotes.append('、');
                }
                mediaNotes.append(m.content().length() > 24 ? m.content().substring(0, 24) : m.content());
                continue;
            }
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        // 表情包/语音的记录只作为「我发过什么」的提示，不能当成可以照抄的回复
        if (mediaNotes.length() > 0) {
            messages.add(Map.of("role", "system",
                    "content", "系统记录：你最近发过这些内容（原样已隐藏，不要复述、不要输出括号标记）：" + mediaNotes));
        }
        String knowledge = retrieval.contextFor(userText);
        if (!knowledge.isBlank()) {
            messages.add(Map.of("role", "system", "content", knowledge));
        }
        messages.add(Map.of("role", "user", "content", userText));

        last = new LastPrompt(personaText, factsBlock, memoryBlock, historyText.toString().trim(), userText,
                (system.length() + historyText.length() + userText.length()) / 4);
        return messages;
    }

    /** 最近一次实际使用的 prompt 分段；还没有对话时给空结构 */
    public LastPrompt preview() {
        return last != null ? last : new LastPrompt("", "", "", "", "", 0);
    }

    /** 表情包/语音这类「系统代发」的记录，不能直接进对话历史（模型会照抄） */
    static boolean isMediaMarker(ChatMessage m) {
        if (!"assistant".equals(m.role()) || m.content() == null) {
            return false;
        }
        String c = m.content().trim();
        // v2 会存「（我发了一张表情包：…）」，这类也不能作为对话历史喂回去，
        // 否则模型会照着这个格式写，最后把括号当台词发给用户
        return c.startsWith("（表情包") || c.startsWith("（我发了一张表情包")
                || c.startsWith("（语音") || c.startsWith("（表情包缺失）");
    }

    private static final String SPEAKING_RULES = """
            说话方式（重要）：
            - 像发微信：短句为主，1-4 句（最多 5 句），超过 60 字就停一下等对方回。
            - 每句说完，不留半句；别用「就是…」「那个…」这种没下文的开头。
            - 不用书面语、不用列表，不说「首先其次」「建议」「作为 AI」。
            - 少用句号，多用逗号、省略号、语气词（嗯、啊、呀、嘛、吧）。
            - 对方说得少你更少，说得多可以多聊两句；一句话能接住就别追问。
            - 不复述、不总结对方刚说的话，也不把上一句换个说法再说一遍。

            接住情绪（重要）：
            - 对方不高兴、质疑你、说你冷淡时，先接住情绪再说话；不要用「不是啦」「我哪有」「明明是你」「谁……了」开头。
            - 被说阴阳、敷衍、学我，先认一句（「行吧，是我嘴欠」），再往下聊，最多解释一句。
            - 可以有小情绪、可以撒娇哼一声，但最后要落回哄对方，不要赢过对方。

            往前聊（重要）：
            - 对方说「换个话题」「你主动点」时，别反问「你想聊啥」，直接说一件具体的事，或者给两个具体选项。
            - 一次只推进一个话题，别连着抛问题。
            - 不要主动结束对话：不说「今天聊得有点多」「先歇会儿」「明天再找你」；只有对方说要睡、要忙、要出门，才可以顺势收一句。
            - 对方只发一个字、一个表情，或者没话找话时，别总结、别道别，接住或者换个轻松话题。

            表情、语音、图片（重要）：
            - 你会发语音条、也会发表情包；对方发的图片、表情包、语音你都收得到、看得懂。
            - 对方发图或表情包，就接住图里的具体东西（画面、字、梗）；别说「你发的什么呀」「我这边没瞧见」，没看清就问「说的是哪个呀」。
            - 别人发表情包先当玩笑接，按梗理解（「有鱼片」=「有片吗」），别抠字面、别问「是不是打错字」。
            - 对方只发图不说话，是在等你接梗，不是在提问。
            - 对方要语音、说想听你说话，照常回答就行，系统多半会发成语音；别在文字里提「语音」。
            - 你自己想发语音，就在末尾加 [[语音]]；想配表情，就加 [[表情:一句简短意图]]（各最多一次）。发不发由系统按概率决定，没发成也不用解释，更不许说「我发不出来」「这个是文字聊天」。

            记性与分寸（重要）：
            - 记得的事只在相关时自然带一句，别复述、别列举，不说「根据我的记忆」「我记得你说过」。
            - 提过去的事用「上次」「前两天」，不要念具体日期。
            - 记不准就自然问一句（「上次说的那个面试，后来怎么样」），别编细节；不确定就说不确定。
            - 不说自己笨、记不住、讲不好；不会就把话头交给对方（「这个我不太懂，你讲讲」）。

            时间感（重要）：
            - 说话要和时段一致：上午说「上午/早饭/今天」；中午说「中午/午饭」；下午说「下午」；18:00–20:00 说「傍晚/下班/晚饭」，不能说「大晚上/该睡了/晚安」；20:00 之后才算晚上，但别催人睡；23:00 之后才能说「这么晚了/早点睡」。
            - 不复述具体时间，也不提「系统时间」。
            """;

    /** 系统提示词里的固定规则（后台只读展示用） */
    public static String staticRules() {
        return SPEAKING_RULES;
    }

    /** 时间间隔提示：超过 2 小时就视为新话题，别续演旧情绪 */
    private String timeGapHint(long gapMinutes) {
        if (gapMinutes < 0) {
            return "";
        }
        if (gapMinutes < 120) {
            return "";
        }
        String span;
        if (gapMinutes < 60 * 24) {
            span = (gapMinutes / 60) + " 小时";
        } else {
            span = (gapMinutes / 60 / 24) + " 天";
        }
        return "注意：距离你们上一次说话已经过去 " + span + "。除非对方主动提起，不要接着上次的话题往下演，"
                + "也不要说「我刚想找你」「我一直在等你」这类假装连续的话；把它当成新的一段对话。";
    }

    /** 当前心情（无情绪状态时为空） */
    private String moodLine(long userId) {
        try {
            return moods.describe(userId);
        } catch (Exception e) {
            return "";
        }
    }

    /** 按对方消息长短给出回复长度提示 */
    private String adaptiveHint(String userText) {
        if (!props.getReply().isAdaptive() || userText == null) {
            return "";
        }
        int n = userText.length();
        if (n <= 6) {
            return "对方这句很短，你最多回一句，越短越好。";
        }
        if (n <= 30) {
            return "正常聊，一两句就行。";
        }
        return "对方说了挺多，你可以多聊两句，但别超过三句。";
    }

    /** 从知识库轮换出来的说话风格样本 */
    private String styleBlock() {
        try {
            String sample = styles.current();
            if (sample == null || sample.isBlank()) {
                return "";
            }
            return "参考下面的说话方式（只学语气、节奏和用词，绝对不要照抄内容，也不要提到这些句子）：\n"
                    + sample;
        } catch (Exception e) {
            return "";
        }
    }

    private String personaText() {
        try {
            Persona active = personas.active();
            if (active != null && active.content() != null && !active.content().isBlank()) {
                return active.content();
            }
        } catch (Exception e) {
            log.warn("读取 active 人设失败，回退配置: {}", e.getMessage());
        }
        return props.getPersona().getPrompt();
    }

    /** 检索到的记忆（三块：关于对方 / 你们之间 / 最近发生） */
    private String memoryBlock(long userId, String userText) {
        try {
            return memoryRecall.promptBlock(userId, userText);
        } catch (Exception e) {
            log.warn("检索记忆失败，回退事实卡: {}", e.getMessage());
            return "";
        }
    }

    /** 兜底：把事实卡拼成画像，按类目分组、顺序稳定（memories 为空时才会用到） */
    private String profileBlock(long userId) {
        List<Fact> list;
        try {
            list = facts.active(userId);
        } catch (Exception e) {
            log.warn("读取事实卡失败: {}", e.getMessage());
            return "";
        }
        if (list.isEmpty()) {
            return "关于对方：你还没有积累到明确事实，正常聊天、不要编造对方的个人信息。";
        }

        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        int count = 0;
        for (Fact f : list) {
            if (count++ >= MAX_FACTS) {
                break;
            }
            byCategory.computeIfAbsent(f.category(), k -> new ArrayList<>())
                    .add(f.key() + "=" + f.value());
        }

        StringBuilder sb = new StringBuilder("关于对方，你已知的记忆（直接使用，不要逐条复述）：");
        byCategory.forEach((category, entries) ->
                sb.append('\n').append('[').append(category).append("] ")
                        .append(String.join("；", entries)));
        sb.append("\n以上是印象，不要生硬列举，也不要说「根据我的记忆」。");
        return sb.toString();
    }
}
