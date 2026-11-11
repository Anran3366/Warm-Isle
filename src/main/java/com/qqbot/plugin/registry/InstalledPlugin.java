package com.qqbot.plugin.registry;

import com.qqbot.plugin.model.PluginManifest;

import java.nio.file.Path;

/** 一条已安装记录（shared.db.installed_plugins + 磁盘目录） */
public record InstalledPlugin(
        String installId,
        String pluginId,
        String version,
        String runtime,
        String source,
        String sha256,
        String status,
        String endpoint,
        PluginManifest manifest,
        Path dir) {

    public boolean usable() {
        return "installed".equals(status) && manifest != null && dir != null;
    }
}
