package com.qqbot.plugin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 插件 manifest（schemaVersion=2）。
 *
 * <p>字段规则见设计文档 §5.3；这里是<b>数据载体</b>，校验在 {@code ManifestValidator}。
 * 所有嵌套对象都允许未知字段，便于向后兼容（新字段旧宿主忽略）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
        Integer schemaVersion,
        String id,
        String name,
        String version,
        Integer apiVersion,
        String runtime,
        String description,
        Author author,
        String license,
        List<String> tags,
        String icon,
        String readme,
        Compat compat,
        Intent intent,
        List<Action> actions,
        Hosted hosted,
        LocalEndpoint local,
        Permissions permissions,
        Catalog catalog,
        PromptSpec prompt,
        Map<String, String> data,
        List<String> variables,
        MemorySpec memory,
        Quota quota,
        VoiceSpec voice,
        List<ConfigField> config,
        String changelog) {

    @JsonIgnore
    public boolean usesData() {
        return "data".equals(runtime);
    }

    @JsonIgnore
    public boolean usesPrompt() {
        return "prompt".equals(runtime);
    }

    @JsonIgnore
    public boolean usesHosted() {
        return "hosted".equals(runtime);
    }

    @JsonIgnore
    public boolean usesLocal() {
        return "local".equals(runtime);
    }

    /** 是否产出音频：决定要不要走语音频控与 sendPrivateRecordSync */
    @JsonIgnore
    public boolean returnsAudio() {
        return actions != null && actions.stream()
                .anyMatch(a -> a.returns() != null && a.returns().contains("audio"));
    }

    @JsonIgnore
    public boolean returnsImage() {
        return actions != null && actions.stream()
                .anyMatch(a -> a.returns() != null && a.returns().contains("image"));
    }

    @JsonIgnore
    public Action action(String name) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (a.name() != null && a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }

    @JsonIgnore
    public Action primaryAction() {
        return actions == null || actions.isEmpty() ? null : actions.get(0);
    }

    @JsonIgnore
    public int perUserPerDay() {
        Integer v = quota == null ? null : quota.perUserPerDay();
        return v == null ? 5 : Math.min(50, Math.max(1, v));
    }

    @JsonIgnore
    public String memoryPolicy() {
        return memory == null || memory.policy() == null ? "none" : memory.policy();
    }

    /** 作者声明过的配置项；为空表示这个插件没有可调参数 */
    @JsonIgnore
    public List<ConfigField> configFields() {
        return config == null ? List.of() : config;
    }

    @JsonIgnore
    public ConfigField configField(String key) {
        for (ConfigField f : configFields()) {
            if (f != null && key != null && key.equals(f.key())) {
                return f;
            }
        }
        return null;
    }

    /** 声明过的出站域名（小写，含 hosted.endpoint 推导值由调用方补） */
    @JsonIgnore
    public List<String> outboundHosts() {
        if (permissions == null || permissions.network() == null
                || permissions.network().outbound() == null) {
            return List.of();
        }
        return permissions.network().outbound().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase().trim())
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String name, String url, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Compat(String minHostVersion, String maxHostVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(String id, List<String> examples, Boolean exclusive) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String name, String description, Map<String, Param> params,
                         List<String> returns, Integer timeoutMs) {

        public boolean hasRequiredParams() {
            return params != null && params.values().stream()
                    .anyMatch(p -> p != null && Boolean.TRUE.equals(p.required()));
        }

        public List<String> requiredParams() {
            if (params == null) {
                return List.of();
            }
            return params.entrySet().stream()
                    .filter(e -> e.getValue() != null && Boolean.TRUE.equals(e.getValue().required()))
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Param(String type, Boolean required, String description,
                        List<String> values, String pattern) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hosted(String endpoint, String health, String auth) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalEndpoint(String endpointHint, String health, String invoke, String setup,
                                List<Requirement> requires) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requirement(String name, String hint, Boolean optional) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(Network network, MemoryPerm memory, LocalPerm localService) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Network(List<String> outbound, String purpose) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryPerm(Boolean write) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalPerm(Boolean allow, String reason) {
    }

    /** data 型的查表结构：match 参与意图匹配，entries 是真正的内容 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(String key, List<String> match, List<Entry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String value, List<String> aliases, String file, Long durationMs) {
    }

    /** prompt 型：system 是包内模板文件相对路径；模板渲染后只进 user 消息（§10.6） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptSpec(String system, Integer maxTokens, Double temperature) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemorySpec(String policy, String type, String keyTemplate,
                             String valueTemplate, Double importance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quota(Integer perUserPerDay, Integer globalPerHour) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VoiceSpec(String strategy) {
    }

    /**
     * 插件配置项定义（宿主据此在后台渲染表单，并按 {@code type} 校验取值）。
     *
     * <p>{@code type}：{@code number} / {@code switch} / {@code select} / {@code text}。
     * 值按「账号 × 插件 × key」存在 bot 库的 {@code plugin_state.config} 里，
     * 未设置时取 {@code default}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigField(String key, String label, String type,
                              @JsonProperty("default") Object defaultValue,
                              Number min, Number max, List<ConfigOption> options,
                              String description, String unit) {

        @JsonIgnore
        public String typeOrDefault() {
            return type == null || type.isBlank() ? "text" : type.toLowerCase();
        }

        @JsonIgnore
        public String effectiveDefault() {
            return defaultValue == null ? "" : String.valueOf(defaultValue);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigOption(String value, String label) {
    }
}
