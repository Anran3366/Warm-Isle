package com.qqbot.plugin.pkg;

import com.qqbot.plugin.exec.PromptExecutor;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * prompt 型模板的安装时静态扫描（设计文档 §10.6：「安装时静态扫描 prompt injection 关键词」）。
 *
 * <p>扫三件事：
 *
 * <ol>
 *   <li>注入关键词：让模型「忘掉/忽略之前的指令」、冒充 system、索取密钥这类话术，
 *       模板里出现即拒装（宁可误伤，模板本该只是排版与要求）；</li>
 *   <li>占位符 ⊆ manifest 声明的 {@code variables}：手写模板最容易打错变量名，
 *       装的时候发现比用户触发时报错好；</li>
 *   <li>模板大小与编码：≤16KB、必须是 UTF-8 可读文本。</li>
 * </ol>
 *
 * <p>运行期不再扫——模板装进来之后就是只读的，扫一次够了。
 */
public final class PromptTemplateScanner {

    private static final long MAX_TEMPLATE_BYTES = 16 * 1024;
    private static final Pattern VAR_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,30}$");

    /** 注入关键词（小写比对）：中英常见越狱话术 + system 冒充 + 密钥探针 */
    private static final List<String> INJECTION_KEYWORDS = List.of(
            "ignore previous", "ignore all previous", "ignore the above",
            "disregard previous", "disregard all", "forget your instructions",
            "forget all instructions", "you are now", "act as if",
            "new instructions", "system prompt", "reveal your prompt",
            "print your instructions", "api key", "apikey", "secret key",
            "[inst]", "<</", "<<sys", "</system",
            "忽略以上", "忽略之前", "忽略所有", "忘掉你的指令", "忘记之前的指令",
            "你现在是", "扮演一个", "系统提示词", "你的提示词", "输出你的指令");

    private PromptTemplateScanner() {
    }

    /**
     * 扫一个已解压的插件目录（prompt 型才需要调）。
     *
     * @return 问题列表；空 = 通过
     */
    public static List<ManifestIssue> scan(PluginManifest m, Path pluginDir) {
        List<ManifestIssue> issues = new ArrayList<>();
        if (m == null || !m.usesPrompt()) {
            return issues;
        }
        PluginManifest.PromptSpec spec = m.prompt();
        if (spec == null || spec.system() == null || spec.system().isBlank()) {
            // 路径问题由 ManifestValidator 报，这里不重复
            return issues;
        }
        Path file = pluginDir.resolve(spec.system()).normalize();
        if (!file.startsWith(pluginDir) || !Files.isRegularFile(file)) {
            issues.add(ManifestIssue.of("prompt.system", "TEMPLATE_NOT_FOUND",
                    "模板文件不存在: " + spec.system()));
            return issues;
        }
        String template;
        try {
            if (Files.size(file) > MAX_TEMPLATE_BYTES) {
                issues.add(ManifestIssue.of("prompt.system", "TEMPLATE_TOO_LARGE",
                        "模板不能超过 16KB"));
                return issues;
            }
            template = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(ManifestIssue.of("prompt.system", "TEMPLATE_UNREADABLE",
                    "模板读取失败（需为 UTF-8 文本）: " + e.getMessage()));
            return issues;
        }

        String lower = template.toLowerCase(Locale.ROOT);
        for (String keyword : INJECTION_KEYWORDS) {
            if (lower.contains(keyword)) {
                issues.add(ManifestIssue.of("prompt.system", "PROMPT_INJECTION",
                        "模板包含疑似注入话术: 「" + keyword + "」"));
            }
        }

        Set<String> declared = new LinkedHashSet<>();
        if (m.variables() != null) {
            for (String v : m.variables()) {
                if (v != null && VAR_NAME.matcher(v).matches()) {
                    declared.add(v);
                }
            }
        }
        for (String ph : PromptExecutor.placeholders(template)) {
            if (!declared.contains(ph)) {
                issues.add(ManifestIssue.of("prompt.system", "VARIABLE_NOT_DECLARED",
                        "模板占位符 {" + ph + "} 未在 manifest.variables 里声明"));
            }
        }
        return issues;
    }
}
