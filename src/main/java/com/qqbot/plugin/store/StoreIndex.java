package com.qqbot.plugin.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 公开索引快照（设计文档 §6.3），顺带带上吊销名单（§6.8：刷新索引时一起拉）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreIndex(
        int schemaVersion,
        String generatedAt,
        int intentsVersion,
        List<StorePlugin> plugins,
        List<Revocation> revocations
) {

    /** 一条吊销记录：version 为 null 表示整个插件拉黑 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revocation(String pluginId, String version, String reason, String createdAt) {
    }

    public static StoreIndex empty() {
        return new StoreIndex(1, null, 0, List.of(), List.of());
    }

    public boolean isEmpty() {
        return plugins == null || plugins.isEmpty();
    }

    public List<Revocation> revocationsOrEmpty() {
        return revocations == null ? List.of() : revocations;
    }

    /** 这版插件是否被吊销（null version = 全版本拉黑） */
    public boolean revoked(String pluginId, String version) {
        if (pluginId == null) {
            return false;
        }
        for (Revocation r : revocationsOrEmpty()) {
            if (!pluginId.equals(r.pluginId())) {
                continue;
            }
            if (r.version() == null || r.version().equals(version)) {
                return true;
            }
        }
        return false;
    }

    public Revocation revocationOf(String pluginId, String version) {
        if (pluginId == null) {
            return null;
        }
        for (Revocation r : revocationsOrEmpty()) {
            if (pluginId.equals(r.pluginId())
                    && (r.version() == null || r.version().equals(version))) {
                return r;
            }
        }
        return null;
    }
}
