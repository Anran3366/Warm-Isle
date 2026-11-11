package com.qqbot.plugin.permission;

import com.qqbot.plugin.model.PluginManifest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 权限说明书（设计文档 §7.7）。
 *
 * <p>把 manifest 里「作者声明的能力」翻译成用户能看懂的一句话，用于启用/安装前的二次确认。
 * 三条原则：
 *
 * <ul>
 *   <li>只说事实：域名、每天条数、要不要连本机服务，全部来自 manifest，不替作者美化；</li>
 *   <li>摘要只算「权限」，不算整份 manifest —— 作者改一句描述不该让所有用户重新确认，
 *       但多要一个域名、多写一类记忆必须重新确认；</li>
 *   <li>宿主强制项优先于作者声明：记忆每天 5 条、hosted 不带聊天原文，这些写死在宿主里。</li>
 * </ul>
 */
public final class PermissionCatalog {

    /** 记忆条数硬上限，与 {@code PluginMemoryWriter.DAILY_CAP} 保持一致 */
    private static final int MEMORY_DAILY_CAP = 5;

    /**
     * 一条要用户点头的权限。
     *
     * @param key    机器标识（{@code network.outbound} / {@code memory.write} / {@code localService} / {@code llm.use}）
     * @param phase  什么时候问：{@code install} / {@code enable} / {@code install+enable}
     * @param label  短语，列表里加粗显示
     * @param detail 一句话说清后果，含具体域名/条数
     * @param level  {@code low} / {@code medium} / {@code high}，前端标色用
     */
    public record Item(String key, String phase, String label, String detail, String level) {
    }

    private PermissionCatalog() {
    }

    /** 按 manifest 推导权限清单；顺序固定，便于前端稳定展示与测试比对 */
    public static List<Item> describe(PluginManifest m) {
        List<Item> out = new ArrayList<>();
        if (m == null) {
            return out;
        }

        List<String> hosts = m.outboundHosts();
        if (!hosts.isEmpty()) {
            out.add(new Item("network.outbound", "install+enable", "联网访问",
                    "会把参数（比如歌名）发送到 " + String.join("、", hosts) + "，不发送聊天内容",
                    "medium"));
        }

        if (writesMemory(m)) {
            String type = m.memory() == null || m.memory().type() == null
                    ? "我做过的事" : "event".equals(m.memory().type()) ? "事件" : "我和你的关系";
            out.add(new Item("memory.write", "install", "写入记忆",
                    "会往记忆库写记录（类型：" + type + "），每个用户每天最多 " + MEMORY_DAILY_CAP + " 条",
                    "low"));
        }

        if (m.usesLocal()) {
            String reason = m.permissions() != null && m.permissions().localService() != null
                    && m.permissions().localService().reason() != null
                    ? m.permissions().localService().reason() : null;
            out.add(new Item("localService", "install+enable", "连接本机服务",
                    "会连接你自己启动的服务，地址只保存在本地" + (reason == null ? "" : "（" + reason + "）"),
                    "high"));
        }

        if (m.usesPrompt()) {
            Integer maxTokens = m.prompt() == null ? null : m.prompt().maxTokens();
            out.add(new Item("llm.use", "enable", "消耗模型额度",
                    "会调用你的模型来生成回复，单次约 " + (maxTokens == null ? 500 : maxTokens) + " tokens",
                    "medium"));
        }

        return out;
    }

    private static boolean writesMemory(PluginManifest m) {
        if (!"none".equals(m.memoryPolicy())) {
            return true;
        }
        return m.permissions() != null && m.permissions().memory() != null
                && Boolean.TRUE.equals(m.permissions().memory().write());
    }

    /**
     * 权限摘要：只有「权限键 + 关键取值」参与计算。
     *
     * <p>存进 {@code plugin_state.manifest_hash}，升级/回滚后与新 manifest 比对；
     * 不一致就要求重新确认，一致则保持原开关不动。
     */
    public static String digest(PluginManifest m) {
        StringBuilder sb = new StringBuilder();
        for (Item it : describe(m)) {
            sb.append(it.key()).append('=').append(it.detail()).append('\n');
        }
        if (sb.length() == 0) {
            return "none";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "none";
        }
    }

    /** 该插件是否属于「隐私模式下也不许跑」的类型（hosted / local 都会离开本机） */
    public static boolean blockedInPrivacyMode(PluginManifest m) {
        return m != null && (m.usesHosted() || m.usesLocal());
    }
}
