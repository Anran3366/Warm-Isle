package com.qqbot.plugin.manifest;

import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * manifest 语义校验（上传时 + 安装时各跑一遍，设计文档 §5.4）。
 *
 * <p>规则全部来自 §5.3 字段表；这里只做「不需要联网、不需要跑起来」就能判的部分。
 * 域名归属、内容合规留给人审。
 */
public final class ManifestValidator {

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,48}$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Set<String> RUNTIMES = Set.of("data", "prompt", "hosted", "local");
    private static final Set<String> RETURNS = Set.of("audio", "image", "text");
    private static final Set<String> POLICIES = Set.of("none", "episodic", "commitment");
    private static final Set<String> STRATEGIES = Set.of("audio", "lyrics");
    private static final Set<String> MEMORY_TYPES = Set.of("relation", "event");

    private ManifestValidator() {
    }

    public static List<ManifestIssue> validate(PluginManifest m) {
        List<ManifestIssue> issues = new ArrayList<>();
        if (m == null) {
            issues.add(ManifestIssue.of("manifest", "MANIFEST_MISSING", "manifest.json 解析失败"));
            return issues;
        }
        if (m.schemaVersion() == null || m.schemaVersion() != 2) {
            issues.add(ManifestIssue.of("schemaVersion", "BAD_SCHEMA_VERSION", "schemaVersion 必须为 2"));
        }
        if (m.id() == null || !ID.matcher(m.id()).matches()) {
            issues.add(ManifestIssue.of("id", "BAD_ID",
                    "id 需匹配 ^[a-z0-9][a-z0-9._-]{2,48}$，并使用「作者.能力」命名空间"));
        } else if (!m.id().contains(".")) {
            issues.add(ManifestIssue.of("id", "BAD_ID_NAMESPACE", "id 必须包含命名空间，如 menran.sing"));
        }
        if (m.name() == null || m.name().isBlank()
                || m.name().length() < 2 || m.name().length() > 20) {
            issues.add(ManifestIssue.of("name", "BAD_NAME", "name 需 2–20 字"));
        }
        if (m.version() == null || !SEMVER.matcher(m.version()).matches()) {
            issues.add(ManifestIssue.of("version", "BAD_VERSION", "version 需严格 semver，如 1.2.0"));
        }
        if (m.apiVersion() == null || m.apiVersion() != 1) {
            issues.add(ManifestIssue.of("apiVersion", "BAD_API_VERSION", "apiVersion 当前只支持 1"));
        }
        if (m.runtime() == null || !RUNTIMES.contains(m.runtime())) {
            issues.add(ManifestIssue.of("runtime", "BAD_RUNTIME", "runtime 需为 data/prompt/hosted/local"));
        }
        if (m.description() == null || m.description().isBlank() || m.description().length() > 80) {
            issues.add(ManifestIssue.of("description", "BAD_DESCRIPTION", "description 必填且 ≤80 字"));
        }
        if (m.license() == null || m.license().isBlank()) {
            issues.add(ManifestIssue.of("license", "LICENSE_MISSING", "license 必填（SPDX 标识）"));
        }
        if (m.tags() == null || m.tags().isEmpty() || m.tags().size() > 6) {
            issues.add(ManifestIssue.of("tags", "BAD_TAGS", "tags 需 1–6 个"));
        }
        if (m.compat() == null || m.compat().minHostVersion() == null
                || !SEMVER.matcher(m.compat().minHostVersion()).matches()) {
            issues.add(ManifestIssue.of("compat.minHostVersion", "BAD_COMPAT",
                    "compat.minHostVersion 必填且为 semver"));
        }

        validateIntent(m, issues);
        validateActions(m, issues);
        validateRuntime(m, issues);
        validateMemory(m, issues);
        return issues;
    }

    private static void validateIntent(PluginManifest m, List<ManifestIssue> issues) {
        if (m.intent() == null || m.intent().id() == null || m.intent().id().isBlank()) {
            issues.add(ManifestIssue.of("intent.id", "INTENT_MISSING", "intent.id 必填"));
            return;
        }
        List<String> examples = m.intent().examples();
        if (examples == null || examples.size() < 5 || examples.size() > 10) {
            issues.add(ManifestIssue.of("intent.examples", "EXAMPLES_COUNT",
                    "intent.examples 需 5–10 条"));
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < examples.size(); i++) {
            String e = examples.get(i);
            String field = "intent.examples[" + i + "]";
            if (e == null || e.trim().length() < 3) {
                issues.add(ManifestIssue.of(field, "EXAMPLE_TOO_SHORT", "示例句至少 3 个字"));
                continue;
            }
            if (isSingleWord(e)) {
                issues.add(ManifestIssue.of(field, "EXAMPLE_IS_WORD",
                        "示例句必须是完整句子，不能是单词或泛动词"));
            }
            if (!seen.add(e.trim())) {
                issues.add(ManifestIssue.of(field, "EXAMPLE_DUPLICATE", "示例句重复"));
            }
        }
    }

    /** 单字/双字/泛动词：会把正常聊天吞掉（§10.7 禁止项） */
    private static boolean isSingleWord(String e) {
        String t = e.trim();
        return t.length() <= 2 || Set.of("看", "来", "帮", "那个", "嗯", "唱歌", "音乐").contains(t);
    }

    private static void validateActions(PluginManifest m, List<ManifestIssue> issues) {
        if (m.actions() == null || m.actions().isEmpty()) {
            issues.add(ManifestIssue.of("actions", "ACTIONS_EMPTY", "至少需要一个 action"));
            return;
        }
        for (int i = 0; i < m.actions().size(); i++) {
            PluginManifest.Action a = m.actions().get(i);
            String field = "actions[" + i + "]";
            if (a == null || a.name() == null || a.name().isBlank()) {
                issues.add(ManifestIssue.of(field + ".name", "ACTION_NAME_MISSING", "action.name 必填"));
                continue;
            }
            if (a.returns() == null || a.returns().isEmpty()) {
                issues.add(ManifestIssue.of(field + ".returns", "RETURNS_EMPTY",
                        "action.returns 至少声明一种输出"));
            } else {
                for (String r : a.returns()) {
                    if (!RETURNS.contains(r)) {
                        issues.add(ManifestIssue.of(field + ".returns", "RETURN_UNKNOWN",
                                "不支持的输出类型: " + r + "（audio/image/text）"));
                    }
                }
            }
            if (a.timeoutMs() != null && (a.timeoutMs() < 1000 || a.timeoutMs() > 180_000)) {
                issues.add(ManifestIssue.of(field + ".timeoutMs", "BAD_TIMEOUT",
                        "timeoutMs 需在 1000–180000 之间"));
            }
        }
    }

    private static void validateRuntime(PluginManifest m, List<ManifestIssue> issues) {
        if (m.usesPrompt()) {
            validatePrompt(m, issues);
        }
        if (m.usesHosted()) {
            if (m.hosted() == null || m.hosted().endpoint() == null
                    || !m.hosted().endpoint().startsWith("https://")) {
                issues.add(ManifestIssue.of("hosted.endpoint", "ENDPOINT_NOT_HTTPS",
                        "hosted.endpoint 必须是 HTTPS"));
            } else {
                String host = hostOf(m.hosted().endpoint());
                if (host != null && m.outboundHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
                    issues.add(ManifestIssue.of("permissions.network.outbound", "DOMAIN_NOT_DECLARED",
                            "endpoint 域名未在 permissions 中声明"));
                }
            }
            if (m.hosted() != null && (m.hosted().health() == null || m.hosted().health().isBlank())) {
                issues.add(ManifestIssue.of("hosted.health", "HEALTH_MISSING", "hosted.health 必填"));
            }
        }
        if (m.usesLocal()) {
            if (m.local() == null || m.local().setup() == null || m.local().setup().isBlank()) {
                issues.add(ManifestIssue.of("local.setup", "SETUP_MISSING",
                        "local 型必须提供 SETUP.md 并在 manifest 中声明"));
            }
            if (m.local() != null && m.local().endpointHint() != null
                    && !m.local().endpointHint().startsWith("http")) {
                issues.add(ManifestIssue.of("local.endpointHint", "BAD_ENDPOINT_HINT",
                        "endpointHint 需是 http(s) 地址"));
            }
        }
        boolean audio = m.returnsAudio();
        String strategy = m.voice() == null ? null : m.voice().strategy();
        if (audio && (strategy == null || !STRATEGIES.contains(strategy))) {
            issues.add(ManifestIssue.of("voice.strategy", "VOICE_STRATEGY_MISSING",
                    "返回 audio 时必须声明 voice.strategy（audio / lyrics）"));
        }
        if (!audio && strategy != null) {
            issues.add(ManifestIssue.of("voice.strategy", "VOICE_STRATEGY_UNUSED",
                    "没有 audio 输出时不需要 voice.strategy"));
        }
    }

    /**
     * prompt 型校验（§10.6）：模板路径、token/温度上限、data 素材引用、变量白名单。
     *
     * <p>变量只允许三类来源：宿主内置（{@code date} / {@code profile}）、action 参数、
     * {@code data} 里声明的包内素材键。模板里的占位符在安装时还会再对一遍（防手写错）。
     */
    private static void validatePrompt(PluginManifest m, List<ManifestIssue> issues) {
        PluginManifest.PromptSpec spec = m.prompt();
        if (spec == null || spec.system() == null || spec.system().isBlank()) {
            issues.add(ManifestIssue.of("prompt.system", "PROMPT_TEMPLATE_MISSING",
                    "prompt 型必须声明 prompt.system（包内模板文件路径）"));
        } else {
            String p = spec.system();
            if (p.contains("..") || p.startsWith("/") || p.contains(":")) {
                issues.add(ManifestIssue.of("prompt.system", "BAD_TEMPLATE_PATH",
                        "模板路径必须是包内相对路径"));
            } else if (!p.endsWith(".md") && !p.endsWith(".txt")) {
                issues.add(ManifestIssue.of("prompt.system", "BAD_TEMPLATE_TYPE",
                        "模板只支持 .md / .txt"));
            }
        }
        if (spec != null && spec.maxTokens() != null
                && (spec.maxTokens() < 100 || spec.maxTokens() > 1000)) {
            issues.add(ManifestIssue.of("prompt.maxTokens", "BAD_MAX_TOKENS",
                    "prompt.maxTokens 需在 100–1000 之间（单次调用封顶 1000 tokens）"));
        }
        if (spec != null && spec.temperature() != null
                && (spec.temperature() < 0 || spec.temperature() > 2)) {
            issues.add(ManifestIssue.of("prompt.temperature", "BAD_TEMPERATURE",
                    "prompt.temperature 需在 0–2 之间"));
        }
        Pattern name = Pattern.compile("^[a-z][a-z0-9_]{0,30}$");
        Set<String> allowed = new LinkedHashSet<>(Set.of("date", "profile"));
        if (m.actions() != null) {
            for (PluginManifest.Action a : m.actions()) {
                if (a != null && a.params() != null) {
                    allowed.addAll(a.params().keySet());
                }
            }
        }
        if (m.data() != null) {
            for (Map.Entry<String, String> e : m.data().entrySet()) {
                if (e.getKey() == null || !name.matcher(e.getKey()).matches()) {
                    issues.add(ManifestIssue.of("data." + e.getKey(), "BAD_DATA_KEY",
                            "data 键需匹配 ^[a-z][a-z0-9_]{0,30}$"));
                    continue;
                }
                String path = e.getValue();
                if (path == null || path.isBlank() || path.contains("..")
                        || path.startsWith("/") || path.contains(":")) {
                    issues.add(ManifestIssue.of("data." + e.getKey(), "BAD_DATA_PATH",
                            "data 素材必须是包内相对路径"));
                } else if (!path.endsWith(".json") && !path.endsWith(".md")
                        && !path.endsWith(".txt") && !path.endsWith(".csv")) {
                    issues.add(ManifestIssue.of("data." + e.getKey(), "BAD_DATA_TYPE",
                            "data 素材只支持 .json / .md / .txt / .csv"));
                }
                allowed.add(e.getKey());
            }
        }
        if (m.variables() != null) {
            for (String v : m.variables()) {
                if (v == null || !name.matcher(v).matches()) {
                    issues.add(ManifestIssue.of("variables", "BAD_VARIABLE",
                            "变量名需匹配 ^[a-z][a-z0-9_]{0,30}$: " + v));
                } else if (!allowed.contains(v)) {
                    issues.add(ManifestIssue.of("variables", "VARIABLE_NOT_ALLOWED",
                            "变量不在白名单（date / profile / action 参数 / data 键）: " + v));
                }
            }
        }
    }

    private static void validateMemory(PluginManifest m, List<ManifestIssue> issues) {
        String policy = m.memoryPolicy();
        if (!POLICIES.contains(policy)) {
            issues.add(ManifestIssue.of("memory.policy", "BAD_POLICY", "memory.policy 需为 none/episodic/commitment"));
            return;
        }
        if ("none".equals(policy)) {
            return;
        }
        boolean allowed = m.permissions() != null && m.permissions().memory() != null
                && Boolean.TRUE.equals(m.permissions().memory().write());
        if (!allowed) {
            issues.add(ManifestIssue.of("permissions.memory.write", "MEMORY_WRITE_NOT_DECLARED",
                    "memory.policy 非 none 时必须声明 permissions.memory.write=true"));
        }
        PluginManifest.MemorySpec spec = m.memory();
        if (spec.type() == null || !MEMORY_TYPES.contains(spec.type())) {
            issues.add(ManifestIssue.of("memory.type", "BAD_MEMORY_TYPE",
                    "memory.type 只允许 relation / event"));
        }
        if (spec.keyTemplate() == null || spec.keyTemplate().isBlank()) {
            issues.add(ManifestIssue.of("memory.keyTemplate", "KEY_TEMPLATE_MISSING",
                    "keyTemplate 必填"));
        }
        if (spec.valueTemplate() == null || spec.valueTemplate().isBlank()) {
            issues.add(ManifestIssue.of("memory.valueTemplate", "VALUE_TEMPLATE_MISSING",
                    "valueTemplate 必填"));
        }
        if (spec.importance() != null && (spec.importance() < 0 || spec.importance() > 1)) {
            issues.add(ManifestIssue.of("memory.importance", "BAD_IMPORTANCE",
                    "importance 需在 0–1 之间"));
        }
        // key 必须带实体占位符，否则只记得最后一首
        if (spec.keyTemplate() != null && !spec.keyTemplate().contains("{")) {
            issues.add(ManifestIssue.of("memory.keyTemplate", "KEY_NO_ENTITY",
                    "keyTemplate 必须包含具体实体占位符（如 {song}）"));
        }
    }

    static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}
