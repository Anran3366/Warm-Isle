package com.qqbot.plugin.model;

/** 一条体检问题；field 精确到 manifest 路径，方便作者定位（设计文档 §6.4 上传响应） */
public record ManifestIssue(String field, String code, String message) {

    public static ManifestIssue of(String field, String code, String message) {
        return new ManifestIssue(field, code, message);
    }

    public boolean isError() {
        return !code.startsWith("WARN_");
    }
}
