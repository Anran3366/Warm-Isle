package com.qqbot.plugin.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;

import java.util.ArrayList;
import java.util.List;

/** manifest.json → {@link PluginManifest} + 语义校验结果 */
public final class ManifestParser {

    private ManifestParser() {
    }

    public record Result(PluginManifest manifest, List<ManifestIssue> issues) {
        public boolean ok() {
            return manifest != null && issues.stream().noneMatch(ManifestIssue::isError);
        }
    }

    public static Result parse(ObjectMapper mapper, byte[] json) {
        List<ManifestIssue> issues = new ArrayList<>();
        PluginManifest manifest = null;
        try {
            manifest = mapper.readValue(json, PluginManifest.class);
        } catch (Exception e) {
            issues.add(ManifestIssue.of("manifest", "MANIFEST_UNREADABLE",
                    "manifest.json 无法解析: " + e.getMessage()));
            return new Result(null, issues);
        }
        issues.addAll(ManifestValidator.validate(manifest));
        return new Result(manifest, issues);
    }
}
