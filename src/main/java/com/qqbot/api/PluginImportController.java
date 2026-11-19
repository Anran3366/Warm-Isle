package com.qqbot.api;

import com.qqbot.plugin.imports.PluginImportService;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.PluginRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通用插件导入：上传、预览、应用、开关。 */
@RestController
@RequestMapping("/api/plugins")
public class PluginImportController {

    private final PluginRegistry registry;
    private final PluginImportService importer;

    public PluginImportController(PluginRegistry registry, PluginImportService importer) {
        this.registry = registry;
        this.importer = importer;
    }

    @PostMapping("/{id}/imports")
    public Map<String, Object> upload(@PathVariable String id,
                                      @RequestParam long qqId,
                                      @RequestParam("file") MultipartFile file) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        if (plugin.manifest() == null || plugin.manifest().importsOrEmpty().isEmpty()) {
            return fail("插件没有声明导入能力");
        }
        try {
            Map<String, Object> result = importer.start(plugin, qqId,
                    file.getOriginalFilename(), file.getBytes());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("result", result);
            return out;
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @GetMapping("/{id}/imports")
    public Map<String, Object> list(@PathVariable String id) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        return Map.of("ok", true, "items", importer.list(id));
    }

    @GetMapping("/{id}/imports/{importId}")
    public Map<String, Object> get(@PathVariable String id, @PathVariable long importId) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        Map<String, Object> row = importer.get(importId);
        if (row == null || !id.equals(row.get("pluginId"))) {
            return fail("导入记录不存在");
        }
        return Map.of("ok", true, "item", row);
    }

    @PostMapping("/{id}/imports/{importId}/apply")
    public Map<String, Object> apply(@PathVariable String id, @PathVariable long importId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        Map<String, Object> owned = importer.get(importId);
        if (owned == null || !id.equals(owned.get("pluginId"))) {
            return fail("导入记录不存在");
        }
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean applyFacts = !Boolean.FALSE.equals(b.get("applyFacts"));
        boolean applyPersona = !Boolean.FALSE.equals(b.get("applyPersona"));
        boolean enablePersona = Boolean.TRUE.equals(b.get("enablePersona"));
        List<Integer> indexes = new java.util.ArrayList<>();
        Object raw = b.get("factIndexes");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    indexes.add(n.intValue());
                }
            }
        }
        try {
            Map<String, Object> result = importer.apply(importId, applyFacts, applyPersona,
                    indexes, enablePersona);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("result", result);
            return out;
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/imports/{importId}/enable")
    public Map<String, Object> enable(@PathVariable String id, @PathVariable long importId,
                                      @RequestParam boolean enabled) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        Map<String, Object> owned = importer.get(importId);
        if (owned == null || !id.equals(owned.get("pluginId"))) {
            return fail("导入记录不存在");
        }
        try {
            return importer.setEnabled(importId, enabled);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/imports/{importId}/retry")
    public Map<String, Object> retry(@PathVariable String id, @PathVariable long importId) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        Map<String, Object> owned = importer.get(importId);
        if (owned == null || !id.equals(owned.get("pluginId"))) {
            return fail("导入记录不存在");
        }
        try {
            return importer.retry(importId);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}/imports/{importId}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable long importId) {
        InstalledPlugin plugin = registry.find(id);
        if (plugin == null) {
            return fail("插件未安装");
        }
        return fail("第一版不物理删除记录，请使用关闭");
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("message", message == null ? "操作失败" : message);
        return out;
    }
}
