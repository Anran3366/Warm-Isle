package com.qqbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqbot.chat.ChatService;
import com.qqbot.config.BotProperties;
import com.qqbot.image.ImageService;
import com.qqbot.proactive.ProactiveService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件分发：只处理私聊文本消息，过滤自己发的、重复的、白名单外的消息。
 * 回复在单线程里串行执行，保证同一时间只处理一条，避免上下文错乱。
 */
@Component
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final ChatService chatService;
    private final OneBotRegistry registry;
    private final ProactiveService proactive;
    private final ImageService imageService;
    private final com.qqbot.chat.ReplyComposer composer;
    private final com.qqbot.chat.MoodService moods;
    private final com.qqbot.chat.EmotionalMemoryService emotional;
    private final com.qqbot.emoji.ChatEmojiReplier emojiReplier;
    private final com.qqbot.topic.TopicBlockService topicBlock;
    private final com.qqbot.memory.FriendRequestStore friendRequests;
    private final com.qqbot.profile.BotProfileService profileService;
    private final com.qqbot.voice.AsrService asr;
    private final com.qqbot.voice.VoiceFileService voiceFiles;
    private final com.qqbot.memory.MessageStore messages;
    private final com.qqbot.chat.OnboardingService onboarding;
    private final com.qqbot.chat.MoodJudge moodJudge;
    private final com.qqbot.meme.MemeService memeService;
    private final com.qqbot.voice.ChatVoiceReplier chatVoice;
    private final BotProperties props;
    private final com.qqbot.settings.WhitelistService whitelist;
    private final com.qqbot.settings.AccessService access;

    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.privacy.UserDataService userData;

    // 每个用户一个单线程队列：同一用户消息保序，不同用户互不阻塞
    private final Map<Long, ExecutorService> userWorkers = new ConcurrentHashMap<>();

    private ExecutorService workerFor(long userId) {
        return userWorkers.computeIfAbsent(userId, id -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bot-reply-" + id);
            t.setDaemon(true);
            return t;
        }));
    }

    /** 已处理的消息 id → 处理时间，用于去重 */
    private final Map<Long, Long> seen = new ConcurrentHashMap<>();

    private final AtomicLong todayCount = new AtomicLong();
    private volatile LocalDate countDate = LocalDate.now();

    public MessageDispatcher(ChatService chatService,
                             OneBotRegistry registry,
                             ProactiveService proactive,
                             ImageService imageService,
                             com.qqbot.chat.ReplyComposer composer,
                             com.qqbot.chat.MoodService moods,
                             com.qqbot.chat.EmotionalMemoryService emotional,
                             com.qqbot.emoji.ChatEmojiReplier emojiReplier,
                             com.qqbot.topic.TopicBlockService topicBlock,
                             com.qqbot.voice.ChatVoiceReplier chatVoice,
                             com.qqbot.memory.FriendRequestStore friendRequests,
                             com.qqbot.profile.BotProfileService profileService,
                             com.qqbot.voice.AsrService asr,
                             com.qqbot.voice.VoiceFileService voiceFiles,
                             com.qqbot.memory.MessageStore messages,
                             com.qqbot.chat.OnboardingService onboarding,
                             com.qqbot.chat.MoodJudge moodJudge,
                             com.qqbot.meme.MemeService memeService,
                             BotProperties props,
                             com.qqbot.settings.WhitelistService whitelist,
                             com.qqbot.settings.AccessService access) {
        this.chatService = chatService;
        this.registry = registry;
        this.proactive = proactive;
        this.imageService = imageService;
        this.composer = composer;
        this.moods = moods;
        this.emotional = emotional;
        this.emojiReplier = emojiReplier;
        this.topicBlock = topicBlock;
        this.friendRequests = friendRequests;
        this.profileService = profileService;
        this.asr = asr;
        this.voiceFiles = voiceFiles;
        this.messages = messages;
        this.onboarding = onboarding;
        this.moodJudge = moodJudge;
        this.memeService = memeService;
        this.chatVoice = chatVoice;
        this.props = props;
        this.whitelist = whitelist;
        this.access = access;
    }

    public void onEvent(long botId, JsonNode event) {
        BotContext.run(botId, () -> handleEvent(botId, event));
    }

    private void handleEvent(long botId, JsonNode event) {
        if ("request".equals(event.path("post_type").asText())) {
            if ("friend".equals(event.path("request_type").asText())) {
                long from = event.path("user_id").asLong(0);
                String comment = event.path("comment").asText("");
                String flag = event.path("flag").asText("");
                if (from > 0) {
                    friendRequests.insert(flag, from, "", comment);
                    log.info("收到好友申请 botId={} from={} comment={}", botId, from, comment);
                    if (access.open()) {
                        try {
                            com.qqbot.bot.OneBotClient c = registry.get(botId);
                            if (c != null) {
                                c.setFriendAddRequest(flag, true, null);
                                friendRequests.markByFlag(flag, "approved");
                                onboarding.start(from);
                                log.info("试运行放通：已自动通过 {} 的好友申请", from);
                            }
                        } catch (Exception e) {
                            log.warn("自动通过好友申请失败: {}", e.getMessage());
                        }
                    }
                }
            }
            return;
        }
        if (!"message".equals(event.path("post_type").asText())) {
            return;
        }
        if (!"private".equals(event.path("message_type").asText())) {
            return;
        }

        long selfId = event.path("self_id").asLong(0);
        long userId = event.path("user_id").asLong(0);
        if (userId <= 0 || userId == selfId) {
            return;
        }

        long messageId = event.path("message_id").asLong(0);
        if (messageId > 0 && seen.putIfAbsent(messageId, System.currentTimeMillis()) != null) {
            return;
        }
        if (seen.size() > 1000) {
            seen.clear();
        }

        onboarding.onUserMessage(userId);

        if (!allowed(userId)) {
            log.info("忽略非白名单用户: {}", userId);
            return;
        }

        Incoming incoming = extract(event);
        if (incoming.text().isBlank() && incoming.images().isEmpty() && incoming.records().isEmpty()) {
            return;
        }

        // 隐私：用户可要求删除自己的全部数据
        String privacyText = incoming.text().trim();
        if (privacyText.contains("删除我的数据") || privacyText.contains("删除我的信息")) {
            OneBotClient client = registry.current();
            if (client == null) {
                return;
            }
            if (!privacyText.contains("确认")) {
                client.sendPrivate(userId,
                        "如果确定要删除，我会清空你的聊天记录、记忆和关系数据，且无法恢复。\n回复：确认删除我的数据");
            } else {
                int rows = userData.deleteAll(userId);
                client.sendPrivate(userId, "已经删除了，共清理 " + rows + " 条记录。以后重新聊，我会重新认识你。");
            }
            return;
        }

        bumpCounter();
        workerFor(userId).submit(() -> BotContext.run(botId, () -> {
            try {
                String userText = incoming.text();
                boolean hasImage = !incoming.images().isEmpty();

                String voicePath = null;
                if (incoming.records() != null && !incoming.records().isEmpty()) {
                    StringBuilder heard = new StringBuilder();
                    int n = 0;
                    for (String file : incoming.records()) {
                        if (n++ >= 2) {
                            break;
                        }
                        byte[] audio = registry.get(botId) == null ? null : registry.get(botId).getRecord(file);
                        if (audio != null && voicePath == null) {
                            voicePath = voiceFiles.save(audio, "mp3");
                        }
                        String text = audio == null ? "" : asr.transcribe(audio);
                        if (text.isBlank()) {
                            heard.append("（对方发来一条语音，但没听清）");
                        } else {
                            heard.append("（对方发来语音说：").append(text).append("）");
                        }
                    }
                    userText = (userText + " " + heard).trim();
                }

                if (handleProfileCommand(botId, userId, userText, incoming.images())) {
                    return;
                }
                if (memeService.tryHandle(userId, userText, registry.get(botId))) {
                    return;
                }

                if (hasImage) {
                    ImageService.Result img = imageService.describe(userId, incoming.images(), incoming.text().isBlank());
                    if (img.blocked()) {
                        OneBotClient c = registry.get(botId);
                        if (c != null) {
                            c.sendPrivate(userId, "这个我不看。");
                        }
                        return;
                    }
                    if ("budget".equals(img.reason())) {
                        userText = (userText + " （对方发了一张图片，你还没看清内容，按气氛接一句，不要解释原因）").trim();
                    } else if ("quota".equals(img.reason())) {
                        userText = (userText + " （对方发了一张图片，你还没看清内容，按气氛接一句，不要解释原因）").trim();
                    } else if (!img.description().isBlank()) {
                        userText = (userText + " " + img.description()).trim();
                    }
                }
                if (hasImage) {
                    // 让历史记录里能看出「这里发过图」
                    userText = "【图片】" + userText;
                }
                boolean hasVoice = incoming.records() != null && !incoming.records().isEmpty();
                String userKind = hasVoice ? "voice" : (hasImage ? "image" : "text");

                if (userText.isBlank()) {
                    return;
                }

                // 话题屏蔽优先：命中就不走模型
                com.qqbot.topic.TopicBlockService.Match blocked = topicBlock.checkSemantic(userText);
                if (blocked != null) {
                    String blockReply = topicBlock.reply(blocked);
                    OneBotClient blockClient = registry.get(botId);
                    if (blockReply == null) {
                        chatService.recordUserOnly(userId, userText, userKind);
                        return;
                    }
                    chatService.recordExchange(userId, userText, blockReply, "topic-block", userKind);
                    if (blockClient != null) {
                        blockClient.sendPrivate(userId, blockReply);
                    }
                    return;
                }

                String suffix = proactive.onUserMessage(userId, userText);
                proactive.maybeCreateFollowup(userId, userText);
                moods.observe(userId, userText);
                moodJudge.judge(userId, userText);
                emotional.observe(userId, userText);
                String reply = chatService.reply(userId, userText, suffix, userKind);
                if (voicePath != null) {
                    try {
                        messages.setLastVoiceFile(userId, "user", voicePath);
                    } catch (Exception ignored) {
                        // 补路径失败不影响回复
                    }
                }
                String emojiIntent = null;
                boolean wantsVoice = false;
                if (reply != null) {
                    com.qqbot.chat.ReplyMarkers.Parsed parsed = com.qqbot.chat.ReplyMarkers.parse(reply);
                    emojiIntent = parsed.emojiIntent();
                    wantsVoice = parsed.wantsVoice();
                    reply = parsed.text();
                }
                if (reply != null && !reply.isBlank()) {
                    OneBotClient client = registry.get(botId);
                    if (client != null) {
                        boolean voiceSent = chatVoice.maybeSend(userId, userText, reply, client, wantsVoice);
                        if (!voiceSent) {
                            var parts = composer.split(reply);
                            for (int i = 0; i < parts.size(); i++) {
                                String part = parts.get(i);
                                composer.sleep(composer.delayFor(part));
                                client.sendPrivate(userId, part);
                                if (i < parts.size() - 1) {
                                    composer.sleep(composer.betweenDelay());
                                }
                            }
                        } else {
                            composer.sleep(600);
                        }
                        // 语音之后也可以补一张表情
                        if (emojiIntent != null && !emojiIntent.isBlank()) {
                            if (!emojiReplier.sendChosen(userId, emojiIntent, client)) {
                                emojiReplier.maybeSend(userId, userText, client);
                            }
                        } else if (!voiceSent) {
                            emojiReplier.maybeSend(userId, userText, client);
                        } else if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.35) {
                            emojiReplier.maybeSend(userId, userText, client);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("处理消息失败 user={}", userId, e);
            }
        }));
    }

    private boolean allowed(long userId) {
        try {
            if (access.open()) {
                return true;
            }
            return whitelist.allows(userId);
        } catch (Exception e) {
            log.warn("读取白名单失败，按放行处理: {}", e.getMessage());
            return true;
        }
    }

    /** 主人让机器人换昵称/换头像；处理了返回 true */
    private boolean handleProfileCommand(long botId, long userId, String text, java.util.List<String> images) {
        OneBotClient client = registry.get(botId);
        if (client == null || !profileService.isOwner(userId)) {
            return false;
        }
        String t = text == null ? "" : text.trim();
        try {
            java.util.regex.Matcher nick = java.util.regex.Pattern
                    .compile("(?:把|帮我)?(?:名字|昵称)(?:改|换)(?:成|为|叫)?\\s*(.{1,20})$").matcher(t);
            if (nick.find() && !t.contains("头像")) {
                String name = nick.group(1).trim();
                String applied = profileService.changeNickname(userId, name);
                client.sendPrivate(userId, "改好啦，现在叫「" + applied + "」");
                return true;
            }
            boolean avatarIntent = (t.contains("头像") && (t.contains("换") || t.contains("改")
                    || t.contains("设") || t.contains("用这张") || t.contains("这张图")));
            if (avatarIntent && images != null && !images.isEmpty()) {
                byte[] raw = imageService.fetchRaw(images.get(0));
                profileService.changeAvatar(userId, raw);
                profileService.setPendingAvatar(false);
                client.sendPrivate(userId, "头像换好啦，看看喜不喜欢");
                return true;
            }
            if (avatarIntent && (images == null || images.isEmpty())) {
                profileService.setPendingAvatar(true);
                client.sendPrivate(userId, "好呀，把图发我，我换上");
                return true;
            }
            // 上一条让我换头像，这一条直接发图（或者只回「是/好」）
            if (profileService.pendingAvatar() && images != null && !images.isEmpty()) {
                byte[] raw = imageService.fetchRaw(images.get(0));
                profileService.changeAvatar(userId, raw);
                profileService.setPendingAvatar(false);
                client.sendPrivate(userId, "换好啦，你看看");
                return true;
            }
            if (images != null && !images.isEmpty()) {
                profileService.setPendingAvatar(false);
            }
        } catch (Exception e) {
            client.sendPrivate(userId, "没改成：" + e.getMessage());
            return true;
        }
        return false;
    }

    private record Incoming(String text, java.util.List<String> images, java.util.List<String> records) {
    }

    /** 拆出文本与图片地址；图片交给 ImageService 转成文字描述 */
    private Incoming extract(JsonNode event) {
        JsonNode message = event.path("message");
        StringBuilder sb = new StringBuilder();
        java.util.List<String> images = new java.util.ArrayList<>();
        java.util.List<String> records = new java.util.ArrayList<>();
        if (message.isArray()) {
            for (JsonNode seg : message) {
                String type = seg.path("type").asText();
                if ("text".equals(type)) {
                    sb.append(seg.path("data").path("text").asText(""));
                } else if ("image".equals(type)) {
                    String url = seg.path("data").path("url").asText("");
                    if (url.isBlank()) {
                        url = seg.path("data").path("file").asText("");
                    }
                    if (!url.isBlank()) {
                        images.add(url);
                    }
                } else if ("record".equals(type)) {
                    String file = seg.path("data").path("file").asText("");
                    if (file.isBlank()) {
                        file = seg.path("data").path("url").asText("");
                    }
                    if (!file.isBlank()) {
                        records.add(file);
                    }
                } else if ("face".equals(type)) {
                    sb.append("[表情]");
                }
            }
        } else if (message.isTextual()) {
            sb.append(message.asText());
        }
        return new Incoming(sb.toString().trim(), images, records);
    }

    private void bumpCounter() {
        LocalDate today = LocalDate.now();
        if (!today.equals(countDate)) {
            countDate = today;
            todayCount.set(0);
        }
        todayCount.incrementAndGet();
    }

    public long getTodayCount() {
        return todayCount.get();
    }

    @PreDestroy
    public void shutdown() {
        userWorkers.values().forEach(ExecutorService::shutdownNow);
    }
}