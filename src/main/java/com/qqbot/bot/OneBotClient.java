package com.qqbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * OneBot v11 正向 WebSocket 客户端：连接 NapCat、收发事件与动作。
 * 断线后每 5 秒重连一次。
 */
public class OneBotClient {

    private static final Logger log = LoggerFactory.getLogger(OneBotClient.class);

    private final long botId;
    private final String wsUrl;
    private final String accessToken;
    private final MessageDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onebot-reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocketSession session;
    private volatile long lastEventAt = System.currentTimeMillis();
    private volatile long lastProbeOkAt = System.currentTimeMillis();
    private volatile boolean probeOk = true;
    private final ScheduledExecutorService healthScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onebot-health");
        t.setDaemon(true);
        return t;
    });
    private volatile long selfId;
    private volatile long connectedAt;
    /** echo -> 等待中的 action 响应；只用于需要读取返回值的扩展接口 */
    private final Map<String, CompletableFuture<JsonNode>> pendingActions = new ConcurrentHashMap<>();

    /** 连续探活失败次数：累计到 3 次就主动断链，避免半开连接一直不复位 */
    private final java.util.concurrent.atomic.AtomicInteger probeFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 上一次主动断链时间，防止重连风暴（60 秒内只强断一次） */
    private volatile long lastForceCloseAt = 0;
    /** 最近一次收到 NapCat 心跳的时间；没开心跳时 heartbeatSeen 一直 false，不做静默判断 */
    private volatile long lastHeartbeatAt = 0;
    private volatile boolean heartbeatSeen = false;
    /** "已连接但未登录" 只告警一次，避免探活每 20 秒刷一条 */
    private volatile boolean loginWarned = false;

    /** 断线期间待发的文本：最多 20 条、60 秒内有效，重连后按顺序补发 */
    private final java.util.concurrent.ConcurrentLinkedDeque<QueuedText> outbox =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int OUTBOX_MAX = 20;
    private static final long OUTBOX_TTL_MS = 60_000L;
    /** 心跳静默阈值：心跳开着却 3 分钟没收到，说明连接虽然开着但已经不通 */
    private static final long HEARTBEAT_SILENT_MS = 3 * 60_000L;

    private record QueuedText(long userId, String text, long enqueuedAt) {
    }

    public OneBotClient(long botId, String wsUrl, String accessToken,
                        MessageDispatcher dispatcher, ObjectMapper mapper) {
        this.botId = botId;
        this.wsUrl = wsUrl;
        this.accessToken = accessToken;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.selfId = 0;
    }

    public long getBotId() {
        return botId;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::ensureConnected, 0, 5, TimeUnit.SECONDS);
        healthScheduler.scheduleWithFixedDelay(this::probe, 15, 20, TimeUnit.SECONDS);
    }

    /** 端到端探测：通过 WS 问一次 NapCat 是否真的登录，而不是只看连接是否打开 */
    private void probe() {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            probeOk = false;
            return;
        }
        try {
            // 以 get_status 的 online 为准：get_login_info 在掉线后可能仍返回缓存的账号
            boolean online = true;
            try {
                JsonNode st = callAction("get_status", mapper.createObjectNode(), 5000);
                online = st.path("data").path("online").asBoolean(true);
            } catch (Exception ignored) {
                // 旧版 NapCat 不支持 get_status 时退回 login_info 判断
            }
            if (!online) {
                markNotLoggedIn();
                return;
            }
            JsonNode r = callAction("get_login_info", mapper.createObjectNode(), 5000);
            long uid = r.path("data").path("user_id").asLong(0);
            if (uid > 0) {
                selfId = uid;
                loginWarned = false;
                probeFailures.set(0);
                probeOk = true;
                lastProbeOkAt = System.currentTimeMillis();
                checkHeartbeatSilence();
            } else {
                markNotLoggedIn();
            }
        } catch (Exception e) {
            markProbeFailed();
        }
    }

    /**
     * 连得上但没登录（正在等扫码）：不是连接故障，不能强断重连，否则二维码会被反复冲掉；
     * 但 isConnected() 必须为 false，否则后台和看门狗都以为在线，掉线没人知道。
     */
    private void markNotLoggedIn() {
        probeFailures.set(0);
        probeOk = false;
        if (!loginWarned) {
            log.warn("NapCat 已连接但未登录（等待扫码）bot={}", botId);
            loginWarned = true;
        }
    }

    /**
     * 探活失败：累计 3 次（约 1 分钟）就主动断链。
     * 半开连接的特征是 session.isOpen() 一直为 true，而 ensureConnected 看到 open 就跳过，
     * 所以不主动关闭就会一直卡在"显示离线但从不重连"，只能等看门狗重启 NapCat。
     */
    private void markProbeFailed() {
        int fails = probeFailures.incrementAndGet();
        if (System.currentTimeMillis() - lastProbeOkAt > 45_000L) {
            if (probeOk) {
                log.warn("NapCat 探测失败，标记为离线 bot={}", botId);
            }
            probeOk = false;
        }
        if (fails >= 3) {
            forceReconnect("探活连续失败 " + fails + " 次");
        }
    }

    /** 心跳开着但长时间没有心跳：判定为假在线，主动断链让重连逻辑接管 */
    private void checkHeartbeatSilence() {
        if (!heartbeatSeen) {
            return;
        }
        long silent = System.currentTimeMillis() - lastHeartbeatAt;
        if (silent > HEARTBEAT_SILENT_MS) {
            forceReconnect("心跳静默 " + (silent / 1000) + " 秒");
        }
    }

    /** 主动断开当前连接；把 session 置空后，5 秒一次的 ensureConnected 会立刻重连 */
    private void forceReconnect(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastForceCloseAt < 60_000L) {
            return;
        }
        lastForceCloseAt = now;
        probeFailures.set(0);
        probeOk = false;
        WebSocketSession s = session;
        log.warn("主动断开 NapCat 连接以触发重连 bot={} 原因={}", botId, reason);
        session = null;
        if (s != null) {
            try {
                // 用 1001 而不是 1006：1006 是保留码，放进 close 帧会被容器拒绝
                s.close(CloseStatus.GOING_AWAY);
            } catch (Exception ignored) {
                // 已经断了
            }
        }
    }

    /** 连上后补发断线期间排队的文本；过期或超量的直接丢弃 */
    private void flushOutbox() {
        if (outbox.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int sent = 0;
        QueuedText item;
        while ((item = outbox.pollFirst()) != null) {
            if (now - item.enqueuedAt() > OUTBOX_TTL_MS) {
                continue;
            }
            try {
                sendPrivateNow(item.userId(), item.text());
                sent++;
            } catch (Exception e) {
                log.warn("补发失败，丢弃剩余 {} 条: {}", outbox.size() + 1, e.getMessage());
                outbox.clear();
                return;
            }
        }
        log.info("重连后补发 {} 条离线消息 bot={}", sent, botId);
    }

    private void ensureConnected() {
        WebSocketSession current = session;
        if (current != null && current.isOpen()) {
            return;
        }
        try {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            String token = accessToken;
            if (token != null && !token.isBlank()) {
                headers.add("Authorization", "Bearer " + token);
            }
            String url = wsUrl;
            WebSocketSession s = client.execute(new Handler(), headers, URI.create(url))
                    .get(10, TimeUnit.SECONDS);
            session = s;
            connectedAt = System.currentTimeMillis();
            probeOk = true;
            probeFailures.set(0);
            lastProbeOkAt = System.currentTimeMillis();
            lastHeartbeatAt = System.currentTimeMillis();
            log.info("已连接 NapCat: {}", url);
            requestLoginInfo();
            flushOutbox();
        } catch (Exception e) {
            log.warn("连接 NapCat 失败（5 秒后重试）: {}", e.getMessage());
        }
    }

    private void requestLoginInfo() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("action", "get_login_info");
            root.put("echo", "login_info");
            send(root);
        } catch (Exception e) {
            log.debug("请求 login_info 失败: {}", e.getMessage());
        }
    }

    /** 发送私聊文本消息；连接不可用时进重试队列，60 秒内重连会补发 */
    public void sendPrivate(long userId, String text) {
        try {
            sendPrivateNow(userId, text);
        } catch (Exception e) {
            if (enqueueText(userId, text)) {
                log.warn("连接不可用，消息入队待补发 qq={} 队列={}", userId, outbox.size());
            } else {
                log.error("发送消息给 {} 失败（重试队列已满）", userId, e);
            }
        }
    }

    private void sendPrivateNow(long userId, String text) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("action", "send_private_msg");
        ObjectNode params = root.putObject("params");
        params.put("user_id", userId);
        ArrayNode message = params.putArray("message");
        ObjectNode seg = message.addObject();
        seg.put("type", "text");
        seg.putObject("data").put("text", text);
        root.put("echo", "send_" + System.nanoTime());
        send(root);
    }

    private boolean enqueueText(long userId, String text) {
        if (outbox.size() >= OUTBOX_MAX) {
            return false;
        }
        outbox.addLast(new QueuedText(userId, text, System.currentTimeMillis()));
        return true;
    }

    /** 发送私聊图片（base64 内联，OneBot v11 支持 base64://） */
    public void sendPrivateImage(long userId, byte[] image) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("action", "send_private_msg");
            ObjectNode params = root.putObject("params");
            params.put("user_id", userId);
            ArrayNode message = params.putArray("message");
            ObjectNode seg = message.addObject();
            seg.put("type", "image");
            String b64 = java.util.Base64.getEncoder().encodeToString(image);
            seg.putObject("data").put("file", "base64://" + b64);
            root.put("echo", "img_" + System.nanoTime());
            send(root);
        } catch (Exception e) {
            log.error("发送图片给 {} 失败", userId, e);
        }
    }

    /** 拍一拍（NapCat 支持 friend_poke）；失败返回 false，调用方降级为文本 */
    public boolean sendPoke(long userId) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("action", "friend_poke");
            ObjectNode params = root.putObject("params");
            params.put("user_id", userId);
            root.put("echo", "poke_" + System.nanoTime());
            send(root);
            return true;
        } catch (Exception e) {
            log.debug("拍一拍失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 调用需要读取返回值的 OneBot action。
     * 现有 sendPrivate 等方法仍走单向发送，不受影响。
     */
    public JsonNode callAction(String action, ObjectNode params, long timeoutMs) {
        String echo = action + "_" + System.nanoTime();
        ObjectNode root = mapper.createObjectNode();
        root.put("action", action);
        root.set("params", params == null ? mapper.createObjectNode() : params);
        root.put("echo", echo);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingActions.put(echo, future);
        try {
            send(root);
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            int retcode = response.path("retcode").asInt(0);
            String status = response.path("status").asText("ok");
            if (retcode != 0 || "failed".equalsIgnoreCase(status)) {
                String message = response.path("message").asText(
                        response.path("wording").asText("NapCat 调用失败"));
                throw new IllegalStateException(action + " 失败: " + message);
            }
            return response;
        } catch (TimeoutException e) {
            throw new IllegalStateException(action + " 超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + " 被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(action + " 失败: " + cause.getMessage(), cause);
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException(action + " 失败: " + e.getMessage(), e);
        } finally {
            pendingActions.remove(echo);
        }
    }

    /** 获取带 QQ 原生分组的好友列表 */
    public JsonNode getFriendsWithCategory() {
        JsonNode response = callAction("get_friends_with_category", mapper.createObjectNode(), 15_000);
        return response.path("data");
    }

    /** 取语音文件（转成 mp3 的 base64），失败返回 null */
    public byte[] getRecord(String file) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("file", file);
            params.put("out_format", "mp3");
            JsonNode response = callAction("get_record", params, 60_000);
            String b64 = response.path("data").path("base64").asText("");
            if (b64.isBlank()) {
                return null;
            }
            if (b64.startsWith("base64://")) {
                b64 = b64.substring("base64://".length());
            } else {
                int comma = b64.indexOf("base64,");
                if (b64.startsWith("data:") && comma > 0) {
                    b64 = b64.substring(comma + "base64,".length());
                }
            }
            return java.util.Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            log.warn("获取语音失败: {}", e.getMessage());
            return null;
        }
    }

    /** 改机器人昵称 */
    public void setNickname(String nickname) {
        ObjectNode params = mapper.createObjectNode();
        params.put("nickname", nickname);
        callAction("set_qq_profile", params, 10_000);
    }

    /** 改机器人头像（base64 数据） */
    public void setAvatar(byte[] image) {
        ObjectNode params = mapper.createObjectNode();
        params.put("file", "base64://" + java.util.Base64.getEncoder().encodeToString(image));
        callAction("set_qq_avatar", params, 20_000);
    }

    /** 当前登录信息（昵称等） */
    public String selfNickname() {
        try {
            JsonNode response = callAction("get_login_info", mapper.createObjectNode(), 8_000);
            return response.path("data").path("nickname").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 好友列表（qq + 昵称 + 备注），用于后台白名单选择 */
    public java.util.List<java.util.Map<String, Object>> getFriendList() {
        JsonNode response = callAction("get_friend_list", mapper.createObjectNode(), 15_000);
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        for (JsonNode node : response.path("data")) {
            long qq = node.path("user_id").asLong(0);
            if (qq <= 0) {
                continue;
            }
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("qqId", qq);
            item.put("nickname", node.path("nickname").asText(""));
            item.put("remark", node.path("remark").asText(""));
            list.add(item);
        }
        list.sort(java.util.Comparator.comparingLong(m -> ((Number) m.get("qqId")).longValue()));
        return list;
    }

    /**
     * 发表 QQ 空间说说。
     *
     * @param ugcRight  1 所有人 / 4 好友 / 16 部分好友 / 64 仅自己 / 128 部分好友不可见
     * @param targetUins ugcRight=16 或 128 时生效
     * @return 说说 tid，可用于删除
     */
    public String sendQzoneMsg(String content, List<String> images, int ugcRight, List<Long> targetUins) {
        ObjectNode params = mapper.createObjectNode();
        params.put("content", content);
        params.put("ugc_right", ugcRight);
        if (images != null && !images.isEmpty()) {
            ArrayNode arr = params.putArray("images");
            for (String image : images) {
                if (image != null && !image.isBlank()) {
                    arr.add(image);
                }
            }
        }
        if (targetUins != null && !targetUins.isEmpty()) {
            ArrayNode arr = params.putArray("target_uins");
            for (Long uin : targetUins) {
                if (uin != null && uin > 0) {
                    arr.add(uin);
                }
            }
        }
        JsonNode response = callAction("send_qzone_msg", params, 20_000);
        String tid = response.path("data").path("tid").asText("");
        if (tid.isBlank()) {
            throw new IllegalStateException("send_qzone_msg 没有返回 tid");
        }
        return tid;
    }

    /** 同意/拒绝好友申请 */
    public void setFriendAddRequest(String flag, boolean approve, String remark) {
        ObjectNode params = mapper.createObjectNode();
        params.put("flag", flag);
        params.put("approve", approve);
        if (remark != null && !remark.isBlank()) {
            params.put("remark", remark);
        }
        callAction("set_friend_add_request", params, 10_000);
    }

    /** 查陌生人昵称（好友申请里没有昵称时补一下） */
    public String strangerName(long userId) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("user_id", userId);
            JsonNode response = callAction("get_stranger_info", params, 5_000);
            String name = response.path("data").path("nickname").asText("");
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }
    /** 删除自己发的说说 */
    public void deleteQzoneMsg(String tid) {
        ObjectNode params = mapper.createObjectNode();
        params.put("tid", tid);
        callAction("delete_qzone_msg", params, 10_000);
    }

    /** 发送私聊语音（NapCat 会自行转码为 silk） */
    public void sendPrivateRecord(long userId, byte[] audio) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("action", "send_private_msg");
            ObjectNode params = root.putObject("params");
            params.put("user_id", userId);
            ArrayNode message = params.putArray("message");
            ObjectNode seg = message.addObject();
            seg.put("type", "record");
            String b64 = java.util.Base64.getEncoder().encodeToString(audio);
            seg.putObject("data").put("file", "base64://" + b64);
            root.put("echo", "rec_" + System.nanoTime());
            send(root);
        } catch (Exception e) {
            log.error("发送语音给 {} 失败", userId, e);
        }
    }

    private synchronized void send(ObjectNode node) throws Exception {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            throw new IllegalStateException("WebSocket 未连接");
        }
        s.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
    }

    /** 只代表 WebSocket 开着，不代表 QQ 已登录（等扫码时也是 true） */
    public boolean socketUp() {
        WebSocketSession s = session;
        return s != null && s.isOpen();
    }

    /** 真正可用：WS 开着 + 探活拿到真实 QQ 号 */
    public boolean isConnected() {
        return socketUp() && probeOk && selfId > 0
                && System.currentTimeMillis() - lastProbeOkAt < 60_000L;
    }

    public boolean isLoggedIn() {
        return isConnected();
    }

    public long getSelfId() {
        return selfId;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    private class Handler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession s) {
            try {
                // NapCat 可能推送很大的帧（例如语音/图片 base64），默认 8KB 会直接 1009 断开
                s.setTextMessageSizeLimit(20 * 1024 * 1024);
                s.setBinaryMessageSizeLimit(20 * 1024 * 1024);
            } catch (Exception e) {
                log.warn("设置 WebSocket 缓冲区失败: {}", e.getMessage());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            log.warn("NapCat 连接关闭: {}", status);
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            String payload = message.getPayload();
            try {
                JsonNode node = mapper.readTree(payload);
                String echo = node.path("echo").asText("");
                CompletableFuture<JsonNode> pending = echo.isEmpty() ? null : pendingActions.remove(echo);
                if (pending != null) {
                    pending.complete(node);
                    return;
                }
                if (node.has("post_type")) {
                    lastEventAt = System.currentTimeMillis();
                    String pt = node.path("post_type").asText("");
                    if ("meta_event".equals(pt)) {
                        // 心跳/生命周期只用来判活：不进业务分发，也不刷 INFO 日志
                        String sub = node.path("meta_event_type").asText("");
                        if ("heartbeat".equals(sub)) {
                            heartbeatSeen = true;
                            lastHeartbeatAt = System.currentTimeMillis();
                        } else {
                            log.info("收到元事件 meta_event_type={} bot={}", sub, botId);
                        }
                        return;
                    }
                    if (!"message".equals(pt)) {
                        String raw = node.toString();
                        if (raw.length() > 800) {
                            raw = raw.substring(0, 800);
                        }
                        log.info("收到事件 post_type={} request_type={} notice_type={} user_id={} flag={} raw={}",
                                pt, node.path("request_type").asText(""),
                                node.path("notice_type").asText(""),
                                node.path("user_id").asLong(0),
                                node.path("flag").asText(""),
                                raw);
                    }
                    dispatcher.onEvent(botId, node);
                } else if ("login_info".equals(echo)) {
                    long id = node.path("data").path("user_id").asLong(0);
                    if (id > 0) {
                        selfId = id;
                        log.info("机器人 QQ 号: {}", id);
                    }
                }
            } catch (Exception e) {
                log.warn("解析事件失败: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        healthScheduler.shutdownNow();
        WebSocketSession s = session;
        if (s != null) {
            try {
                s.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 关闭失败不影响退出
            }
        }
    }

    /** 供状态接口使用 */
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("connected", isConnected());
        m.put("loggedIn", isConnected());
        m.put("socketUp", socketUp());
        m.put("wsUrl", wsUrl);
        m.put("selfId", selfId);
        m.put("connectedAt", connectedAt);
        m.put("outbox", outbox.size());
        m.put("heartbeatSeen", heartbeatSeen);
        m.put("heartbeatAgoSec", heartbeatSeen
                ? (System.currentTimeMillis() - lastHeartbeatAt) / 1000 : -1);
        return m;
    }
}
