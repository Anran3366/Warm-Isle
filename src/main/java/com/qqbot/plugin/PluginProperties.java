package com.qqbot.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 插件配置（前缀 {@code qqbot.plugin}）。
 *
 * <p><b>默认关闭</b>：不配置时插件链路整条空转，行为与改造前逐字节一致（设计文档 §1.3）。
 * staging 实例通过环境变量显式打开。
 */
@Component
@ConfigurationProperties(prefix = "qqbot.plugin")
public class PluginProperties {

    private boolean enabled = false;
    private String dir = "./data/plugins";
    private String hostVersion = "1.0.0";
    private int maxSyncMs = 20_000;
    /** 隐私模式：只允许 data / prompt 型，hosted / local 一律不触发（文档 §7.7） */
    private boolean privacyMode = false;
    /** 升级后旧版本目录保留天数，用于回滚（文档 §7.4） */
    private int keepOldVersionsDays = 7;
    /** L3 语义意图召回：L1/L2 未命中时，用向量比较 intent.examples（设计文档 §8.2） */
    private boolean semanticEnabled = true;
    private double semanticThreshold = 0.80;
    private int semanticMaxChars = 40;
    private boolean semanticPrefilter = true;
    private Store store = new Store();
    private Limits limits = new Limits();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getHostVersion() {
        return hostVersion;
    }

    public void setHostVersion(String hostVersion) {
        this.hostVersion = hostVersion;
    }

    /** 同步调用硬上限（N3）：超过此时长的 action 由 PluginTaskRunner 转异步 */
    public int getMaxSyncMs() {
        return maxSyncMs;
    }

    public void setMaxSyncMs(int maxSyncMs) {
        this.maxSyncMs = maxSyncMs;
    }

    public boolean isPrivacyMode() {
        return privacyMode;
    }

    public void setPrivacyMode(boolean privacyMode) {
        this.privacyMode = privacyMode;
    }

    public boolean isSemanticEnabled() {
        return semanticEnabled;
    }

    public void setSemanticEnabled(boolean semanticEnabled) {
        this.semanticEnabled = semanticEnabled;
    }

    public double getSemanticThreshold() {
        return semanticThreshold;
    }

    public void setSemanticThreshold(double semanticThreshold) {
        this.semanticThreshold = semanticThreshold;
    }

    public int getSemanticMaxChars() {
        return semanticMaxChars;
    }

    public void setSemanticMaxChars(int semanticMaxChars) {
        this.semanticMaxChars = semanticMaxChars;
    }

    public boolean isSemanticPrefilter() {
        return semanticPrefilter;
    }

    public void setSemanticPrefilter(boolean semanticPrefilter) {
        this.semanticPrefilter = semanticPrefilter;
    }

    public int getKeepOldVersionsDays() {
        return keepOldVersionsDays;
    }

    public void setKeepOldVersionsDays(int keepOldVersionsDays) {
        this.keepOldVersionsDays = keepOldVersionsDays;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public static class Store {
        private String url = "";
        /** 仅商店所有者：审核/发布代理走内网地址；其他部署留空 */
        private String adminUrl = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 10_000;
        private int indexTtlSeconds = 900;
        /** 商店公钥（Ed25519，SPKI DER 的 base64）。配置后索引/吊销名单验签，验签失败拒绝采用（§6.8） */
        private String publicKey = "";
        /** 已配置公钥时是否强制验签。false 仅自研商店联调用，生产必须 true */
        private boolean signatureRequired = true;
        /** 商店管理令牌：只有商店所有者的实例需要配置，用于审核/下架；其他实例留空 */
        private String adminToken = "";
        /** 发布时上报的实例名，帮助所有者识别来源；可留空 */
        private String publisher = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getAdminUrl() {
            return adminUrl;
        }

        public void setAdminUrl(String adminUrl) {
            this.adminUrl = adminUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getIndexTtlSeconds() {
            return indexTtlSeconds;
        }

        public void setIndexTtlSeconds(int indexTtlSeconds) {
            this.indexTtlSeconds = indexTtlSeconds;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }

        public String getPublisher() {
            return publisher;
        }

        public void setPublisher(String publisher) {
            this.publisher = publisher;
        }

        public boolean isSignatureRequired() {
            return signatureRequired;
        }

        public void setSignatureRequired(boolean signatureRequired) {
            this.signatureRequired = signatureRequired;
        }
    }

    public static class Limits {
        private int perUserPerDay = 10;
        private int globalPerHour = 60;
        private int circuitFailures = 5;
        private int circuitOpenSeconds = 300;

        public int getPerUserPerDay() {
            return perUserPerDay;
        }

        public void setPerUserPerDay(int perUserPerDay) {
            this.perUserPerDay = perUserPerDay;
        }

        public int getGlobalPerHour() {
            return globalPerHour;
        }

        public void setGlobalPerHour(int globalPerHour) {
            this.globalPerHour = globalPerHour;
        }

        public int getCircuitFailures() {
            return circuitFailures;
        }

        public void setCircuitFailures(int circuitFailures) {
            this.circuitFailures = circuitFailures;
        }

        public int getCircuitOpenSeconds() {
            return circuitOpenSeconds;
        }

        public void setCircuitOpenSeconds(int circuitOpenSeconds) {
            this.circuitOpenSeconds = circuitOpenSeconds;
        }
    }
}
