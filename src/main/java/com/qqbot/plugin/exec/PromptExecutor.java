package com.qqbot.plugin.exec;

import com.qqbot.llm.BudgetExceededException;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * prompt 型执行器：宿主代调模型，费用走部署者预算（设计文档 §10.6）。
 *
 * <p>四条硬约束：
 *
 * <ul>
 *   <li>模板只进 user 消息，永远不进 system —— 作者的模板不能覆盖宿主的人设与底线；</li>
 *   <li>变量白名单固定：{@code date}（宿主填今天）、{@code profile}（宿主从记忆库取的用户画像摘要）、
 *       action 参数（路由抽取）、{@code data} 声明的包内素材；模板里出现别的占位符直接报错；</li>
 *   <li>单次调用 ≤ 1000 tokens（manifest 里写更大也会被压回 1000）；</li>
 *   <li>输出纯文本，封顶 800 字，防止模型刹不住车刷屏。</li>
 * </ul>
 *
 * <p>模板文件的安装时静态扫描（prompt injection 关键词）在
 * {@code PromptTemplateScanner}，这里只做运行期渲染与调用。
 */
@Component
public class PromptExecutor {

    private static final Logger log = LoggerFactory.getLogger(PromptExecutor.class);

    private static final Pattern VAR = Pattern.compile("\\{(\\w+)}");
    private static final long MAX_TEMPLATE_BYTES = 16 * 1024;
    private static final long MAX_DATA_BYTES = 64 * 1024;
    private static final int MAX_TOKENS_CAP = 1000;
    private static final int DEFAULT_MAX_TOKENS = 600;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int MAX_OUTPUT_CHARS = 800;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 宿主自己的 system，作者模板碰不到这一层 */
    private static final String HOST_SYSTEM =
            "你在替一个聊天机器人生成一段要直接发给用户的中文回复。"
                    + "严格按用户消息里的模板要求输出，只输出正文，不要解释、不要加引号、不要加前后缀。";

    private final DeepSeekClient llm;
    private final MemoryStore memories;

    public PromptExecutor(DeepSeekClient llm, MemoryStore memories) {
        this.llm = llm;
        this.memories = memories;
    }

    public PluginOutput execute(InstalledPlugin plugin, String actionName,
                                Map<String, String> params, long qqId) {
        PluginManifest manifest = plugin.manifest();
        PluginManifest.PromptSpec spec = manifest.prompt();
        if (spec == null || spec.system() == null) {
            throw new DataExecutor.PluginException("INTERNAL", "插件缺少 prompt 模板声明");
        }
        String template = readPackageFile(plugin, spec.system(), MAX_TEMPLATE_BYTES, "模板");

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("date", LocalDate.now().format(DAY));
        vars.put("profile", profileOf(qqId));
        // action 声明过的参数先占位空串：可选参数用户没给时模板照常渲染
        PluginManifest.Action action = manifest.action(actionName);
        if (action != null && action.params() != null) {
            for (String name : action.params().keySet()) {
                vars.put(name, "");
            }
        }
        if (params != null) {
            params.forEach((k, v) -> vars.put(k, v == null ? "" : v));
        }
        if (manifest.data() != null) {
            for (Map.Entry<String, String> e : manifest.data().entrySet()) {
                // 素材缺了是打包事故，不是用户的问题
                vars.put(e.getKey(), readPackageFile(plugin, e.getValue(), MAX_DATA_BYTES, "素材"));
            }
        }

        String userMessage = render(template, vars, plugin.pluginId());
        int maxTokens = spec.maxTokens() == null
                ? DEFAULT_MAX_TOKENS : Math.min(MAX_TOKENS_CAP, Math.max(100, spec.maxTokens()));
        double temperature = spec.temperature() == null
                ? DEFAULT_TEMPERATURE : Math.min(2.0, Math.max(0, spec.temperature()));

        LlmResult result;
        try {
            result = llm.chat(List.of(
                    Map.of("role", "system", "content", HOST_SYSTEM),
                    Map.of("role", "user", "content", userMessage)), temperature, maxTokens);
        } catch (BudgetExceededException e) {
            throw new DataExecutor.PluginException("RATE_LIMITED", "模型预算用尽",
                    "今天的额度用完啦，明天再玩。");
        } catch (IllegalStateException e) {
            throw new DataExecutor.PluginException("UNAVAILABLE", "模型未配置: " + e.getMessage());
        } catch (Exception e) {
            log.warn("prompt 插件 {} 调模型失败: {}", plugin.pluginId(), e.getMessage());
            throw new DataExecutor.PluginException("UNAVAILABLE", "模型调用失败");
        }
        int total = result.promptTokens() + result.completionTokens();
        if (total > 2 * MAX_TOKENS_CAP) {
            log.warn("prompt 插件 {} 单次调用 {} tokens，超过预期", plugin.pluginId(), total);
        }
        String text = result.content() == null ? "" : result.content().trim();
        if (text.isEmpty()) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "模型返回空内容");
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS);
        }
        log.info("prompt 插件 {} 完成: tokens={}+{}", plugin.pluginId(),
                result.promptTokens(), result.completionTokens());

        // 记忆变量只来自路由参数（prompt 型没有「插件响应」，不存在绕过模板的通道）
        Map<String, Object> memoryVars = new LinkedHashMap<>();
        if (params != null) {
            memoryVars.putAll(params);
        }
        return PluginOutput.text(text, null, memoryVars);
    }

    /** 渲染模板：占位符必须能在白名单变量表里找到，缺必填参数视为没听懂 */
    public static String render(String template, Map<String, String> vars, String pluginId) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null) {
                throw new DataExecutor.PluginException("BAD_PARAMS",
                        "模板变量 {" + name + "} 没有取值（插件 " + pluginId + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 模板里的占位符集合（安装扫描与运行期渲染共用同一套正则） */
    public static List<String> placeholders(String template) {
        Matcher m = VAR.matcher(template);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 用户画像摘要：记忆库里与该用户最相关的 relation 记忆前 3 条（只要 subject=user 的，
     * 插件自己写的记忆不喂回模板）。没有就是「还不了解」，模板照样能跑。
     */
    private String profileOf(long qqId) {
        try {
            List<Memory> top = memories.topByType(qqId, "relation", 10).stream()
                    .filter(mem -> "user".equals(mem.subject()))
                    .limit(3)
                    .toList();
            if (top == null || top.isEmpty()) {
                return "（还不太了解这个用户）";
            }
            StringBuilder sb = new StringBuilder();
            for (Memory mem : top) {
                String v = mem.value();
                if (v == null || v.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('；');
                }
                sb.append(v.length() <= 20 ? v : v.substring(0, 20));
            }
            return sb.length() == 0 ? "（还不太了解这个用户）" : sb.toString();
        } catch (Exception e) {
            log.debug("取用户画像失败: {}", e.getMessage());
            return "（还不太了解这个用户）";
        }
    }

    private String readPackageFile(InstalledPlugin plugin, String rel, long limit, String what) {
        if (rel == null || rel.isBlank() || rel.contains("..")
                || rel.startsWith("/") || rel.contains(":")) {
            throw new DataExecutor.PluginException("INTERNAL", "非法的" + what + "路径: " + rel);
        }
        Path file = plugin.dir().resolve(rel).normalize();
        if (!file.startsWith(plugin.dir())) {
            throw new DataExecutor.PluginException("INTERNAL", what + "路径越界: " + rel);
        }
        if (!Files.isRegularFile(file)) {
            throw new DataExecutor.PluginException("INTERNAL", what + "文件不存在: " + rel);
        }
        try {
            long size = Files.size(file);
            if (size > limit) {
                throw new DataExecutor.PluginException("INTERNAL",
                        what + "超过大小上限: " + rel);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (DataExecutor.PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new DataExecutor.PluginException("INTERNAL", "读取" + what + "失败: " + e.getMessage());
        }
    }
}
