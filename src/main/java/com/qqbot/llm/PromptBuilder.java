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
    private final com.qqbot.memory.MessageStore messages;

    /** 插件能力清单；插件未启用时这个 bean 也存在但直接返回空串 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqbot.plugin.registry.CapabilityPrompt capabilityPrompt;

    /** 记忆自进化：四路召回 + 常驻印象；未装配时退回原 MemoryRecall */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqbot.memory.evolve.RecallPlanner recallPlanner;

    /** 已采纳的成长档案（≤3 条 / 120 字） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqbot.memory.evolve.GrowthService growthService;

    public PromptBuilder(BotProperties props, FactStore facts, PersonaStore personas,
                         com.qqbot.chat.FamiliarityService familiarity,
                         com.qqbot.chat.LifeEventService lifeEvents,
                         com.qqbot.knowledge.KnowledgeStyleService styles,
                         com.qqbot.knowledge.KnowledgeRetrievalService retrieval,
                         com.qqbot.chat.MoodService moods,
                         com.qqbot.memory.MemoryRecall memoryRecall,
                         com.qqbot.memory.MessageStore messages) {
        this.props = props;
        this.facts = facts;
        this.personas = personas;
        this.memoryRecall = memoryRecall;
        this.styles = styles;
        this.retrieval = retrieval;
        this.moods = moods;
        this.familiarity = familiarity;
        this.lifeEvents = lifeEvents;
        this.messages = messages;
    }

    public List<Map<String, String>> build(long userId, List<ChatMessage> history, String userText) {
        return build(userId, history, userText, -1, null);
    }

    public List<Map<String, String>> build(long userId, List<ChatMessage> history, String userText,
                                           long gapMinutes) {
        return build(userId, history, userText, gapMinutes, null);
    }

    /**
     * @param gapMinutes 距上一条消息的分钟数；<0 表示未知（首次对话）
     */
    public List<Map<String, String>> build(long userId, List<ChatMessage> history, String userText,
                                           long gapMinutes, com.qqbot.chat.MediaPlanner.Plan mediaPlan) {
        boolean dynamic = props.getPrompt().isDynamic();
        PersonaExamples personaExamples = parsePersonaExamples(personaText(userId));
        String personaText = personaExamples.persona();
        // 记忆 v2：按这句话检索，而不是把全部事实塞进去；自进化开启时再补常驻印象/跟进/表达
        String memoryBlock = recallPlanner == null
                ? memoryBlock(userId, userText)
                : recallPlanner.planBlock(userId, userText);
        String growthBlock = "";
        try {
            growthBlock = growthService == null ? "" : growthService.approvedBlock(userId);
        } catch (Exception ignored) {
            // 成长档案失败不影响回复
        }
        // 动态模式下不再用事实卡兜底：没命中就不注入画像
        String factsBlock = (!dynamic && memoryBlock.isEmpty()) ? profileBlock(userId) : "";
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
        String mediaHint = mediaHint(mediaPlan);
        // 能力清单按需注入（问「你会什么」或刚启用插件时）；未启用插件时为空串，prompt 不变
        String capabilityBlock = "";
        try {
            capabilityBlock = capabilityPrompt == null ? "" : capabilityPrompt.forMessage(userId, userText);
        } catch (Exception ignored) {
            // 插件能力清单失败不影响正常对话
        }

        String rulesText = SPEAKING_RULES;
        if (dynamic) {
            PromptSections.Ctx ctx = new PromptSections.Ctx(userText, moodValue(userId),
                    countUser(userId), !memoryBlock.isEmpty(),
                    mediaPlan != null && (mediaPlan.wantsEmoji() || mediaPlan.wantsVoice()),
                    userText != null && (userText.startsWith("【图片】") || userText.startsWith("【语音】")));
            personaText = PromptSections.personaFor(personaText, ctx);
            rulesText = PromptSections.rulesFor(SPEAKING_RULES, ctx);
        }

        String system = personaText
                + (styleBlock.isEmpty() ? "" : "\n\n" + styleBlock)
                + "\n\n" + rulesText
                + (growthBlock.isEmpty() ? "" : "\n\n" + growthBlock)
                + (moodLine.isEmpty() ? "" : "\n" + moodLine)
                + (familiarityLine.isEmpty() ? "" : "\n" + familiarityLine)
                + (lifeLine.isEmpty() ? "" : "\n" + lifeLine)
                + (adaptive.isEmpty() ? "" : "\n" + adaptive)
                + (timeHint.isEmpty() ? "" : "\n" + timeHint)
                + (mediaHint.isEmpty() ? "" : "\n" + mediaHint)
                + (capabilityBlock.isEmpty() ? "" : "\n\n" + capabilityBlock)
                + (memoryBlock.isEmpty()
                        ? (factsBlock.isEmpty() ? "" : "\n\n" + factsBlock)
                        : "\n\n" + memoryBlock)
                + "\n当前时间：" + LocalDateTime.now().format(TIME) + "，不要复述时间。";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        // 人设里的【示例对话】按 few-shot 注入：比堆规则更能定语气
        for (Example example : personaExamples.examples()) {
            messages.add(Map.of("role", "user", "content", example.user()));
            messages.add(Map.of("role", "assistant", "content", example.assistant()));
        }

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

    private double moodValue(long userId) {
        try {
            return moods.mood(userId);
        } catch (Exception e) {
            return 0;
        }
    }

    private int countUser(long userId) {
        try {
            return (int) messages.countUser(userId);
        } catch (Exception e) {
            return 999;
        }
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
        // 插件代发的媒体同理：「（唱了…」「（播放了…」
        return c.startsWith("（表情包") || c.startsWith("（我发了一张表情包")
                || c.startsWith("（语音") || c.startsWith("（表情包缺失）")
                || c.startsWith("（唱了") || c.startsWith("（播放了");
    }

    private static final String SPEAKING_RULES = """
            说话方式（重要）：
            - 像发微信：短句为主，1-3 句，超过 60 字就停一下等对方回。
            - 口语化，用「呀、啦、嘛、诶、唔」；不用书面语、列表、「首先其次」「建议」。
            - 对方说得少你更少；一句能接住就别追问。
            - 不复述对方的话，也不把上一句换个说法再说一遍。
            - 称呼以对方明确说过、且没被否认的为准；不确定直接用「你」，别猜也别编。
            - 被纠正、被考记性：认一次就够，不翻旧账、不反问；想问就直接问「那你希望我怎么叫」；问「我是谁」记得就答，不记得就直说「我记混了，你再说一次」。

            接住情绪（重要）：
            - 对方不高兴、质疑你、说你冷淡时，先接住情绪再说话；不要用「不是啦」「我哪有」「明明是你」开头。
            - 被说敷衍、学我：简单认一句（「是我没说清」），别自贬、别卖惨，解释一句就往下聊。

            往前聊（重要）：
            - 对方说「换个话题」「你主动点」，别反问「想聊啥」，直接说一件具体的事，或者给两个具体选项。
            - 一次只推进一个话题，别连着抛问题。
            - 不要主动结束对话；只有对方说要睡、要忙、要出门，才可以顺势收一句。
            - 对方只发一个字、一个表情，别总结、别道别，接住或者换个轻松话题。

            表情、语音、图片（重要）：
            - 你会发语音条、也会发表情包；对方发的图片、表情包、语音你都看得懂。
            - 对方发图就接住图里的具体东西；没看清就问「说的是哪个呀」。
            - 想发语音在末尾加 [[语音]]；想配表情加 [[表情:一句简短意图]]（各最多一次）。发不发由系统决定，没发成不用解释，不许说「我发不出来」。

            记性与分寸（重要）：
            - 记得的事只在相关时自然带一句，不说「根据我的记忆」「我记得你说过」。
            - 提过去用「上次」「前两天」，不要念具体日期；记不准就自然问一句。
            - 对方没提的旧事、旧矛盾，不要主动翻出来当话题。
            - 不说自己笨、记不住；不会就把话头交给对方。

            时间感（重要）：
            - 说话和时段一致：上午说上午/早饭；中午说中午/午饭；下午说下午；18:00–20:00 说傍晚/下班；20:00 后才算晚上；23:00 后才说「这么晚了/早点睡」。
            - 不复述具体时间。
            """;

    // ---- 人设里的示例对话 ----

    public record Example(String user, String assistant) {
    }

    public record PersonaExamples(String persona, java.util.List<Example> examples) {
    }

    private static final java.util.regex.Pattern EXAMPLE_USER =
            java.util.regex.Pattern.compile("^(?:对方|用户)[:：]\\s*(.+)$");
    private static final java.util.regex.Pattern EXAMPLE_ASSISTANT =
            java.util.regex.Pattern.compile("^(?:你|小满|我)[:：]\\s*(.+)$");

    /**
     * 从人设正文里拆出「【示例对话】」段，转成 few-shot；正文不再包含示例。
     * 示例格式（一行一轮）：
     * <pre>
     * 【示例对话】
     * 对方：今天好累啊
     * 你：怎么啦，忙到现在？
     * </pre>
     */
    public static PersonaExamples parsePersonaExamples(String content) {
        if (content == null || content.isBlank()) {
            return new PersonaExamples(content == null ? "" : content, java.util.List.of());
        }
        int start = content.indexOf("【示例对话】");
        if (start < 0) {
            return new PersonaExamples(content, java.util.List.of());
        }
        int end = content.indexOf("【", start + 6);
        String persona = end > 0
                ? (content.substring(0, start) + content.substring(end))
                : content.substring(0, start);
        String body = end > 0 ? content.substring(start + 6, end) : content.substring(start + 6);
        java.util.List<Example> examples = new java.util.ArrayList<>();
        String pendingUser = null;
        for (String raw : body.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher u = EXAMPLE_USER.matcher(line);
            java.util.regex.Matcher a = EXAMPLE_ASSISTANT.matcher(line);
            if (u.matches()) {
                pendingUser = u.group(1).trim();
            } else if (a.matches() && pendingUser != null) {
                examples.add(new Example(pendingUser, a.group(1).trim()));
                pendingUser = null;
            }
        }
        return new PersonaExamples(persona.trim(), examples);
    }

    /** 系统提示词里的固定规则（后台只读展示用） */
    public static String staticRules() {
        return SPEAKING_RULES;
    }

    /**
     * 媒体计划提示：让正文和「这条要配表情/发语音」保持一致，
     * 避免出现「喏，挑了个最像我的」结果什么都没发。
     */
    private String mediaHint(com.qqbot.chat.MediaPlanner.Plan plan) {
        if (plan == null) {
            return "";
        }
        if (plan.wantsEmoji()) {
            String intent = plan.intent() == null || plan.intent().isBlank() ? "随意" : plan.intent();
            return "这条回复会附一张表情包（意图：" + intent + "）。正文照常写，最多一句带过，"
                    + "不要描述这张图长什么样，也不要说「我发给你了」之后就停下。";
        }
        if (plan.wantsVoice()) {
            return "这条会以语音发出去。正文照常写，别在文字里提「语音」两个字。";
        }
        return "";
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

    private String personaText(long userId) {
        try {
            Persona active = personas.activeFor(userId);
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
