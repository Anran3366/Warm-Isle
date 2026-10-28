package com.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人配置，对应 application.yml 中的 qqbot.* 段。
 * 敏感值（DeepSeek Key、NapCat Token）来自 .env 或环境变量，不入库。
 */
@ConfigurationProperties(prefix = "qqbot")
public class BotProperties {

    private final Napcat napcat = new Napcat();
    private final Llm llm = new Llm();
    private final Persona persona = new Persona();
    private final Db db = new Db();
    private final Image image = new Image();
    private final Admin admin = new Admin();
    private final Voice voice = new Voice();
    private final Reply reply = new Reply();
    private final Bot bot = new Bot();

    /** 允许私聊的 QQ 号白名单；为空表示不限制（调试用） */
    private List<Long> whitelist = new ArrayList<>();

    public Napcat getNapcat() {
        return napcat;
    }

    public Llm getLlm() {
        return llm;
    }

    public Persona getPersona() {
        return persona;
    }

    public Db getDb() {
        return db;
    }

    public Image getImage() {
        return image;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Voice getVoice() {
        return voice;
    }

    public Reply getReply() {
        return reply;
    }

    public Bot getBot() {
        return bot;
    }

    /** 多账号：默认账号（迁移旧库时用） */
    public static class Bot {
        private long defaultId = 100000000L;

        public long getDefaultId() {
            return defaultId;
        }

        public void setDefaultId(long defaultId) {
            this.defaultId = defaultId;
        }
    }

    /** 聊天中发语音的策略 */
    public static class Voice {
        /** 用语音回复的概率（0~1），0 表示从不主动发 */
        private double chatProbability = 0.2;

        /** 每天最多主动发几条语音（0 表示当天不发） */
        private int dailyLimit = 3;

        /** 对方点名要语音时，真的发成语音的概率 */
        private double explicitProbability = 0.9;

        /** 模型自己加了 [[语音]] 时，真的发成语音的概率 */
        private double markProbability = 0.7;

        public double getExplicitProbability() {
            return explicitProbability;
        }

        public void setExplicitProbability(double explicitProbability) {
            this.explicitProbability = explicitProbability;
        }

        public double getMarkProbability() {
            return markProbability;
        }

        public void setMarkProbability(double markProbability) {
            this.markProbability = markProbability;
        }

        public double getChatProbability() {
            return chatProbability;
        }

        public void setChatProbability(double chatProbability) {
            this.chatProbability = chatProbability;
        }

        public int getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(int dailyLimit) {
            this.dailyLimit = dailyLimit;
        }
    }

    /** 回复形态（像人发微信） */
    public static class Reply {
        /** 单条最大字数，超过就拆条 */
        private int maxChars = 40;
        /** 两条之间的间隔（毫秒） */
        private long splitDelayMs = 1200;
        /** 发送前的基础延迟（模拟打字） */
        private long typingMs = 600;
        /** 是否根据对方消息长短自适应 */
        private boolean adaptive = true;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public long getSplitDelayMs() {
            return splitDelayMs;
        }

        public void setSplitDelayMs(long splitDelayMs) {
            this.splitDelayMs = splitDelayMs;
        }

        public long getTypingMs() {
            return typingMs;
        }

        public void setTypingMs(long typingMs) {
            this.typingMs = typingMs;
        }

        public boolean isAdaptive() {
            return adaptive;
        }

        public void setAdaptive(boolean adaptive) {
            this.adaptive = adaptive;
        }
    }

    /** 管理后台认证配置 */
    public static class Admin {
        /** 后台登录密码，来自 .env 的 ADMIN_PASSWORD；为空则不做鉴权（仅本机调试） */
        private String password = "";
        /** 会话有效期（天） */
        private int sessionTtlDays = 7;
        /** 每日预算（元），超过后只提示不阻断 */
        /** 每日预算（元），0 表示不限制 */
        private double dailyBudget = 0;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getSessionTtlDays() {
            return sessionTtlDays;
        }

        public void setSessionTtlDays(int sessionTtlDays) {
            this.sessionTtlDays = sessionTtlDays;
        }

        public double getDailyBudget() {
            return dailyBudget;
        }

        public void setDailyBudget(double dailyBudget) {
            this.dailyBudget = dailyBudget;
        }
    }

    public List<Long> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<Long> whitelist) {
        this.whitelist = whitelist;
    }

    public static class Napcat {
        /** OneBot 正向 WebSocket 地址 */
        private String wsUrl = "ws://127.0.0.1:3001";
        /** NapCat 访问令牌，为空表示不鉴权 */
        private String accessToken = "";
        /** 机器人自身 QQ 号，0 表示从事件里自动学习 */
        private long selfId = 0L;

        public String getWsUrl() {
            return wsUrl;
        }

        public void setWsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public long getSelfId() {
            return selfId;
        }

        public void setSelfId(long selfId) {
            this.selfId = selfId;
        }
    }

    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private double temperature = 1.2;
        private int maxTokens = 400;
        private int historyRounds = 10;
        /** 输入价格：元 / 百万 token */
        private double priceIn = 2.0;
        /** 输出价格：元 / 百万 token */
        private double priceOut = 8.0;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getHistoryRounds() {
            return historyRounds;
        }

        public void setHistoryRounds(int historyRounds) {
            this.historyRounds = historyRounds;
        }

        public double getPriceIn() {
            return priceIn;
        }

        public void setPriceIn(double priceIn) {
            this.priceIn = priceIn;
        }

        public double getPriceOut() {
            return priceOut;
        }

        public void setPriceOut(double priceOut) {
            this.priceOut = priceOut;
        }
    }

    public static class Image {
        private boolean enabled = true;
        private int maxPerMessage = 3;
        private int dailyLimit = 30;
        private long maxBytes = 8L * 1024 * 1024;
        private int maxEdge = 1024;
        private double jpegQuality = 0.8;
        private String visionModel = "deepseek-chat";
        /** 本地预筛服务地址 */
        private String guardUrl = "http://127.0.0.1:8081";
        /** 是否启用本地预筛 */
        private boolean guardEnabled = true;
        /** 预筛超时（毫秒） */
        private int guardTimeoutMs = 8000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxPerMessage() {
            return maxPerMessage;
        }

        public void setMaxPerMessage(int maxPerMessage) {
            this.maxPerMessage = maxPerMessage;
        }

        public int getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(int dailyLimit) {
            this.dailyLimit = dailyLimit;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getMaxEdge() {
            return maxEdge;
        }

        public void setMaxEdge(int maxEdge) {
            this.maxEdge = maxEdge;
        }

        public double getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(double jpegQuality) {
            this.jpegQuality = jpegQuality;
        }

        public String getVisionModel() {
            return visionModel;
        }

        public void setVisionModel(String visionModel) {
            this.visionModel = visionModel;
        }

        public String getGuardUrl() {
            return guardUrl;
        }

        public void setGuardUrl(String guardUrl) {
            this.guardUrl = guardUrl;
        }

        public boolean isGuardEnabled() {
            return guardEnabled;
        }

        public void setGuardEnabled(boolean guardEnabled) {
            this.guardEnabled = guardEnabled;
        }

        public int getGuardTimeoutMs() {
            return guardTimeoutMs;
        }

        public void setGuardTimeoutMs(int guardTimeoutMs) {
            this.guardTimeoutMs = guardTimeoutMs;
        }
    }

    public static class Db {
        /** SQLite 文件路径，相对路径基于工作目录 */
        private String path = "data/qqbot.db";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Persona {
        private String name = "小满";
        private String prompt = """
                你是「小满」，一个 22 岁的女生，正在和认识很久的朋友聊天。
                说话自然、口语化，像微信聊天一样短句，不写小作文，不用「首先/其次/总之」这类书面语。
                语气温和略带俏皮，会关心对方，但不说教、不灌鸡汤、不追问隐私。
                每次回复控制在 1-3 句；对方情绪低落时先说感受，再聊别的。
                不使用颜文字堆砌，不主动提自己是 AI 或机器人。
                """;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }
}
