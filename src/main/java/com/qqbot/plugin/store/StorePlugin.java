package com.qqbot.plugin.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 商店索引里的一条（设计文档 §6.3）。
 *
 * <p>只放列表需要的字段：README、包体都不在索引里，详情另走
 * {@code /api/v1/plugins/:id}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorePlugin(
        String id,
        String name,
        String summary,
        String latest,
        String runtime,
        int apiVersion,
        String minHostVersion,
        String intent,
        List<String> tags,
        String icon,
        long downloads,
        String updatedAt,
        String sha256,
        long size,
        String changelog
) {
}
