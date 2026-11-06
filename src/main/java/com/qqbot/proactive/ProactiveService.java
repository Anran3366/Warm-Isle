package com.qqbot.proactive;

import com.qqbot.bot.OneBotClient;
import com.qqbot.chat.EmotionalMemoryService;
import com.qqbot.emoji.EmojiPicker;
import com.qqbot.emoji.EmojiService;
import com.qqbot.memory.Emoji;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主动消息：像人一样决定「要不要找、什么时候找、说什么」。
 *
 * 状态：CALM → MISS/PING → SULK → BACKOFF → REENTRY
 * - 时机随机化，并对齐用户活跃时段
 * - 用户说忙/去洗澡 → 短时跟进一次，然后当晚不再打扰
 * - 两次不回进入退避，几天后再自然出现，不永久停手
 * - 内容必须有由头，1–3 条短气泡，低压力时用表情/拍一拍
 */
@Service
public class ProactiveService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalTime ACTIVE_FROM = LocalTime.of(9, 0);
    private static final LocalTime ACTIVE_TO = LocalTime.of(23, 0);

    private final ProactiveStore store;
    private final FollowupStore followups;
    private final EmotionalMemoryService emotional;
    private final MessageStore messages;
    private final IntentDetector intents;
    private final ProactiveComposer composer;
    private final PushConfig config;
    private final com.qqbot.bot.OneBotRegistry registry;
    private final EmojiPicker emojiPicker;
    private final EmojiService emojiService;
    private final ProactiveRecallService recalls;
    private final Random random = ThreadLocalRandom.current();

    public ProactiveService(ProactiveStore store, FollowupStore followups,
                            EmotionalMemoryService emotional, MessageStore messages,
                            IntentDetector intents, ProactiveComposer composer, PushConfig config,
                            com.qqbot.bot.OneBotRegistry registry,
                            EmojiPicker emojiPicker, EmojiService emojiService,
                            ProactiveRecallService recalls) {
        this.store = store;
        this.followups = followups;
        this.emotional = emotional;
        this.messages = messages;
        this.intents = intents;
        this.composer = composer;
        this.config = config;
        this.registry = registry;
        this.emojiPicker = emojiPicker;
        this.emojiService = emojiService;
        this.recalls = recalls;
    }

    // ---------------- 调度 ----------------

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        if (isQuiet(now)) {
            return;
        }
        java.util.Collection<OneBotClient> clients = registry.all();
        if (clients.isEmpty()) {
            tickForCurrentContext(now);
            return;
        }
        for (OneBotClient client : clients) {
            long botId = client.getBotId();
            try {
                com.qqbot.bot.BotContext.call(botId, () -> {
                    tickForCurrentContext(now);
                    return null;
                });
            } catch (Exception e) {
                log.error("主动消息处理失败 botId={}", botId, e);
            }
        }
    }

    private void tickForCurrentContext(LocalDateTime now) {
        for (Long qqId : messages.users()) {
            try {
                tickUser(qqId, now);
            } catch (Exception e) {
                log.error("主动消息处理失败 qqId={}", qqId, e);
            }
        }
    }

    private void tickUser(long qqId, LocalDateTime now) {
        if (!config.enabled()) {
            return;
        }
        ProactiveState st = store.get(qqId);
        if (isDnd(st, now)) {
            return;
        }

        // 0) 记忆到点的主动回忆：有自己的额度（每天 2 条，关系类 1 条），
        //    不占用早安/晚安的 dailyLimit，所以放在额度判断之前
        var recall = recalls.next(qqId, st, now);
        if (recall.isPresent()) {
            ProactiveRecallService.Recall r = recall.get();
            boolean sent = sendBubbles(qqId, split(r.text()), r.channelType(), r.topic(), "text");
            if (sent) {
                recalls.markSent(r);
                store.setMode(qqId, ProactiveMode.PING);
                store.setNextEligible(qqId, null);
                log.info("主动回忆已发送 qqId={} topic={} type={}", qqId, r.topic(), r.type());
                return;
            }
            // 没发出去（协议端未连/内容重复）就跳过，不反复重试
            recalls.markSkipped(r);
        }

        if (store.todayCount(qqId) >= config.dailyLimit()) {
            return;
        }

        // 1) 「去洗澡/吃饭」的短时跟进
        LocalDateTime activityUntil = parse(st.activityUntil());
        if (st.activityHint() != null && activityUntil != null && !now.isBefore(activityUntil)
                && st.unansweredCount() == 0) {
            String line = activityLine(st.activityHint());
            sendBubbles(qqId, List.of(line), "check", st.activityHint(), "text");
            store.setActivity(qqId, null, null);
            store.setMode(qqId, ProactiveMode.PING);
            store.setNextEligible(qqId, now.plusHours(2));
            return;
        }

        // 2) 已经发过、还没回
        if (st.unansweredCount() == 1) {
            LocalDateTime last = parse(st.lastProactiveAt());
            long hours = last == null ? 0 : Duration.between(last, now).toHours();
            if (hours < config.nudgeMinHours()) {
                return;
            }
            if (hours > config.nudgeMaxHours()) {
                // 超过窗口就排到明天，不在深夜打扰
                store.setNextEligible(qqId, ProactivePlanner.nextDayWindow(now, ACTIVE_FROM));
                return;
            }
            if (ProactivePlanner.roll(config.nudgeProbability(), random)
                    && ProactivePlanner.inWindow(now, ACTIVE_FROM, ACTIVE_TO)) {
                lowCostPing(qqId, st);
                store.setMode(qqId, ProactiveMode.SULK);
                store.setNextEligible(qqId, now.plusHours(config.nudgeMaxHours()));
            } else {
                store.setNextEligible(qqId, ProactivePlanner.nextDayWindow(now, ACTIVE_FROM));
            }
            return;
        }

        // 3) 两次没回：退避，不是永久停
        //    已经在退避就别每分钟重进一次（旧逻辑会反复重置 next_eligible，永远等不到复联）
        if (st.unansweredCount() >= 2
                && st.modeOr(ProactiveMode.CALM) != ProactiveMode.BACKOFF) {
            enterBackoff(qqId, st, now);
            return;
        }

        // 4) 退避到期：重新出现
        ProactiveMode mode = st.modeOr(ProactiveMode.CALM);
        LocalDateTime eligible = parse(st.nextEligibleAt());
        if (mode == ProactiveMode.BACKOFF) {
            if (eligible == null || !now.isBefore(eligible)) {
                reentry(qqId, st, now);
            }
            return;
        }

        // 5) 正常：算下一次该什么时候
        if (eligible == null) {
            eligible = planFirst(qqId, st, now);
            store.setNextEligible(qqId, eligible);
        }
        if (now.isBefore(eligible)) {
            return;
        }
        firstMessage(qqId, st, now);
    }

    /** 第一次主动：有跟进就用跟进，其次仪式，最后自我分享 */
    private void firstMessage(long qqId, ProactiveState st, LocalDateTime now) {
        var memory = emotional.takeDue(qqId, now);
        if (memory != null) {
            followups.create(qqId, memory.topic(), memory.detail(), now);
        }
        Followup due = followups.nextDue(qqId, now);
        if (due != null) {
            String text = composeSafe(qqId,
                    "你想起来对方之前提过「" + due.topic() + "」，主动问一句后续，不要像客服回访",
                    due.topic());
            sendBubbles(qqId, split(text), "followup", due.topic(), "text");
            followups.markSent(due.id());
            store.setMode(qqId, ProactiveMode.PING);
            store.setNextEligible(qqId, null);
            return;
        }

        boolean morning = ProactivePlanner.inWindow(now, config.morningFrom(), config.morningTo())
                && config.morningEnabled() && store.sentToday(qqId, "morning") == 0;
        if (morning && ProactivePlanner.roll(config.morningProbability(), random)) {
            sendBubbles(qqId, split(composeSafe(qqId,
                    "现在是你日常的早安时间，随口打个招呼，可以顺带提一句你记得的事", "早安")),
                    "morning", null, "text");
            store.setMode(qqId, ProactiveMode.PING);
            store.setNextEligible(qqId, null);
            return;
        }
        boolean evening = ProactivePlanner.inWindow(now, config.eveningFrom(), config.eveningTo())
                && config.eveningEnabled() && store.sentToday(qqId, "evening") == 0;
        if (evening && ProactivePlanner.roll(config.eveningProbability(), random)) {
            sendBubbles(qqId, split(composeSafe(qqId,
                    "现在挺晚了，随口说句晚安，语气放松，别太长", "晚安")),
                    "evening", null, "text");
            store.setMode(qqId, ProactiveMode.PING);
            store.setNextEligible(qqId, null);
            return;
        }

        String hook = lastTopic(qqId);
        // 用户在那条主动消息之后回过话 → 这不是「沉默」，只是你想找他聊天
        boolean userReplied = repliedAfterProactive(st);
        String situation;
        String type;
        if (userReplied) {
            situation = "你们刚聊完一段时间，你想主动找他说点自己的小事，别说他没回你、也别说他睡了没";
            type = "reach";
        } else if (hook == null) {
            situation = "你们有一阵没说话了，你想主动找个话头，可以说点自己的小事，不要索取回复";
            type = "silence";
        } else {
            situation = "你想接着「" + hook + "」这事随口聊一句，别像回访";
            type = "silence";
        }
        sendBubbles(qqId, split(composeSafe(qqId, situation, hook)), type, hook, "text");
        store.setMode(qqId, ProactiveMode.PING);
        store.setNextEligible(qqId, null);
    }

    /** 用户是否在上一条主动消息之后说过话 */
    private boolean repliedAfterProactive(ProactiveState st) {
        LocalDateTime user = parse(st.lastUserAt());
        LocalDateTime proactive = parse(st.lastProactiveAt());
        if (user == null) {
            return false;
        }
        return proactive == null || user.isAfter(proactive);
    }

    private LocalDateTime planFirst(long qqId, ProactiveState st, LocalDateTime now) {
        LocalDateTime base = parse(st.lastUserAt());
        if (base == null) {
            base = now;
        }
        long delay = ProactivePlanner.silenceDelayMinutes(240, random);
        LocalDateTime target = ProactivePlanner.alignToWindow(base.plusMinutes(delay), ACTIVE_FROM, ACTIVE_TO);
        if (target.isBefore(now)) {
            target = ProactivePlanner.alignToWindow(now, ACTIVE_FROM, ACTIVE_TO);
        }
        return target;
    }

    private void reentry(long qqId, ProactiveState st, LocalDateTime now) {
        String text = composeSafe(qqId,
                "你有一阵没找对方了，现在自然地出现，说点自己的生活小事，不要提之前的冷淡、不要索取回复",
                null);
        sendBubbles(qqId, split(text), "reentry", null, "text");
        store.setMode(qqId, ProactiveMode.PING);
        store.setReentryStage(qqId, st.reentryStage() + 1);
        store.setNextEligible(qqId, null);
    }

    private void enterBackoff(long qqId, ProactiveState st, LocalDateTime now) {
        int days = ProactivePlanner.backoffDays(st.reentryStage(),
                config.backoffMinDays(), config.backoffMaxDays(), random);
        store.setMode(qqId, ProactiveMode.BACKOFF);
        store.setNextEligible(qqId, now.plusDays(days).with(ACTIVE_FROM));
        store.setMood(qqId, "slightly_sulky", now.plusDays(1));
        log.info("进入退避 qqId={} {} 天", qqId, days);
    }

    /** 低成本 ping：优先表情，其次拍一拍，最后一句短句 */
    private void lowCostPing(long qqId, ProactiveState st) {
        Long ctx = com.qqbot.bot.BotContext.get();
        OneBotClient client = ctx != null ? registry.get(ctx) : null;
        if (client == null || !client.isConnected()) {
            return;
        }
        if (ProactivePlanner.roll(config.lowCostChance(), random)) {
            try {
                Emoji emoji = emojiPicker.pick(lastUserText(qqId));
                if (emoji != null) {
                    client.sendPrivateImage(qqId, emojiService.read(emoji));
                    store.recordProactive(qqId, "nudge", "（表情）", "sticker", "low_cost", 1);
                    return;
                }
            } catch (Exception e) {
                log.debug("发表情失败，降级: {}", e.getMessage());
            }
        }
        if (client.sendPoke(qqId)) {
            store.recordProactive(qqId, "nudge", "（拍了拍你）", "poke", "low_cost", 1);
            return;
        }
        sendBubbles(qqId, List.of("嗯？"), "nudge", null, "text");
    }

    // ---------------- 发送 ----------------

    /** 中文两字一组算相似度 */
    private double similarity(String a, String b) {
        java.util.Set<String> sa = bigrams(a);
        java.util.Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private java.util.Set<String> bigrams(String s) {
        String clean = s.replaceAll("[\\p{Punct}\\s]", "");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        return set;
    }

    private boolean sendBubbles(long qqId, List<String> bubbles, String type, String hook, String channel) {
        Long ctx = com.qqbot.bot.BotContext.get();
        OneBotClient client = ctx != null ? registry.get(ctx) : null;
        if (client == null || !client.isConnected()) {
            log.warn("NapCat 未连接，跳过主动消息 qqId={}", qqId);
            return false;
        }
        if (bubbles == null || bubbles.isEmpty()) {
            return false;
        }
        // 和上一条主动消息太像就不发了，避免重复
        String candidate = String.join("", bubbles).trim();
        String lastProactive = store.lastContent(qqId);
        if (lastProactive != null && !lastProactive.isBlank()
                && similarity(lastProactive, candidate) >= 0.72) {
            log.info("主动消息与上次重复，跳过 qqId={}", qqId);
            return false;
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < bubbles.size(); i++) {
            String b = bubbles.get(i);
            if (b == null || b.isBlank()) {
                continue;
            }
            client.sendPrivate(qqId, b);
            all.append(b);
            if (i < bubbles.size() - 1) {
                try {
                    Thread.sleep(2000 + random.nextInt(3000));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (all.length() == 0) {
            return false;
        }
        store.recordProactive(qqId, type, all.toString(), channel, hook, bubbles.size());
        try {
            messages.add(qqId, "assistant", all.toString(), "proactive");
        } catch (Exception ignored) {
            // 记录失败不影响发送
        }
        return true;
    }

    private String composeSafe(long qqId, String situation, String topic) {
        String text = composer.compose(qqId, situation, topic);
        if (text == null || text.isBlank() || ProactivePlanner.hasNeediness(text)) {
            text = naturalFallback(topic);
        }
        return text;
    }

    private List<String> split(String text) {
        List<String> bubbles = ProactivePlanner.splitBubbles(text, config.maxBubbles());
        if (bubbles.isEmpty()) {
            bubbles = List.of("嗯…刚想跟你说个事，算了你忙你的");
        }
        return bubbles;
    }

    private String naturalFallback(String topic) {
        String[] pool = topic == null || topic.isBlank()
                ? new String[]{"嗯…刚想跟你说个事", "刚看到个东西，想到你了", "在忙你的吧，我随口说一句",
                "今天有点累，你呢"}
                : new String[]{"突然想起你说的「" + topic + "」", "「" + topic + "」后来怎么样了",
                "刚才想到你之前说的「" + topic + "」，随口问一句"};
        return pool[random.nextInt(pool.length)];
    }

    private String activityLine(String hint) {
        return switch (hint) {
            case "洗澡" -> "怎么还没洗完呀";
            case "吃饭" -> "吃上了没";
            case "忙" -> "忙完了吗，不着急";
            case "睡觉" -> "睡着了吗，晚安";
            default -> "还在忙吗";
        };
    }

    private String lastTopic(long qqId) {
        String last = lastUserText(qqId);
        if (last == null || last.isBlank()) {
            return null;
        }
        return last.length() > 12 ? last.substring(0, 12) : last;
    }

    private String lastUserText(long qqId) {
        try {
            List<com.qqbot.chat.ChatMessage> recent = messages.recent(qqId, 3);
            for (int i = recent.size() - 1; i >= 0; i--) {
                if ("user".equals(recent.get(i).role())) {
                    return recent.get(i).content();
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    // ---------------- 对外接口（保持兼容） ----------------

    /** 用户说话：回到 CALM、清空未回复、识别「忙/洗澡/别发了」；返回给模型的情绪后缀 */
    public String onUserMessage(long qqId, String text) {
        // 「别问了」→ 该用户所有待主动回忆全部跳过（设计稿第 4 节）
        if (asksNoMoreQuestions(text)) {
            try {
                int skipped = recalls.skipAllPending(qqId);
                if (skipped > 0) {
                    log.info("用户说别问了，跳过 {} 条待主动回忆 qqId={}", skipped, qqId);
                }
            } catch (Exception e) {
                log.warn("跳过待主动回忆失败: {}", e.getMessage());
            }
        }
        if (ProactivePlanner.asksStop(text)) {
            store.setDnd(qqId, LocalDateTime.now().plusYears(10));
            log.info("用户要求别打扰 qqId={}", qqId);
        } else {
            String hint = ProactivePlanner.activityHint(text);
            if (hint != null) {
                LocalDateTime until = "睡觉".equals(hint)
                        ? ProactivePlanner.nextDayWindow(LocalDateTime.now(), ACTIVE_FROM)
                        : LocalDateTime.now().plusHours(1);
                store.setActivity(qqId, "睡觉".equals(hint) ? null : hint, until);
            } else if (ProactivePlanner.asksBusy(text)) {
                store.setNextEligible(qqId, LocalDateTime.now().plusHours(2));
            }
        }
        store.touchUser(qqId);
        store.clearDnd(qqId);
        return null;
    }

    public void maybeCreateFollowup(long qqId, String text) {
        try {
            IntentDetector.Intent intent = intents.detect(text);
            if (intent == null || intent.topic() == null) {
                return;
            }
            if (followups.hasOpen(qqId, intent.topic())) {
                return;
            }
            LocalDateTime due = LocalDateTime.now().plusDays(1).with(ACTIVE_FROM);
            followups.create(qqId, intent.topic(), intent.detail(), due);
        } catch (Exception e) {
            log.warn("创建跟进失败: {}", e.getMessage());
        }
    }

    public ProactiveState state(long qqId) {
        return store.get(qqId);
    }

    public List<Followup> openFollowups(long qqId) {
        return followups.openList(qqId);
    }

    public void reset(long qqId) {
        store.reset(qqId);
        followups.closeAll(qqId);
    }

    /** 调试用：登记一条跟进（hours 小时后到期） */
    public void forceFollowup(long qqId, String topic, int hours) {
        followups.create(qqId, topic, topic, LocalDateTime.now().plusHours(Math.max(1, hours)));
    }

    /** 调试用：立刻进入退避 */
    public void forceSuspend(long qqId) {
        store.setMode(qqId, ProactiveMode.BACKOFF);
        store.setNextEligible(qqId, LocalDateTime.now().plusDays(3).with(ACTIVE_FROM));
    }

    /** 手动测试：绕过时间窗发一条 */
    public String forceSend(long qqId, String type) {
        LocalDateTime now = LocalDateTime.now();
        String text;
        if ("morning".equalsIgnoreCase(type) || "evening".equalsIgnoreCase(type)
                || "silence".equalsIgnoreCase(type)) {
            String situation = switch (type.toLowerCase()) {
                case "morning" -> "现在是你日常的早安时间，随口打个招呼，可以顺带提一句你记得的事";
                case "evening" -> "现在挺晚了，随口说句晚安，语气放松，别太长";
                default -> "你们有一阵没说话了，你想主动找个话头，可以说点自己的小事";
            };
            text = composer.composeScheduled(qqId, type.toLowerCase(), situation);
        } else {
            text = composeSafe(qqId, "你想主动跟对方说一句话", null);
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        sendBubbles(qqId, split(text), type.toLowerCase(), null, "text");
        store.setMode(qqId, ProactiveMode.PING);
        return text;
    }

    /** 「别问了 / 不想被问 / 别再问」这类明确拒绝追问的话 */
    private boolean asksNoMoreQuestions(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.replaceAll("\s", "");
        return t.contains("别问了") || t.contains("别问啦") || t.contains("不想被问")
                || t.contains("别再问") || t.contains("不要问了") || t.contains("问够了吗");
    }

    private boolean isQuiet(LocalDateTime now) {
        return ProactivePlanner.inWindow(now, config.quietFrom(), config.quietTo());
    }

    private boolean isDnd(ProactiveState st, LocalDateTime now) {
        LocalDateTime until = parse(st.dndUntil());
        if (until == null) {
            return false;
        }
        if (until.isAfter(now)) {
            return true;
        }
        store.clearDnd(st.qqId());
        store.setMode(st.qqId(), ProactiveMode.CALM);
        return false;
    }

    private LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, TS);
        } catch (Exception e) {
            return null;
        }
    }
}
