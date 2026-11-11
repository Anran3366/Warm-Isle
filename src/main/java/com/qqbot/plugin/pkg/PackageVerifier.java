package com.qqbot.plugin.pkg;

import com.qqbot.plugin.model.ManifestIssue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件包安全检查（设计文档 §5.1 / §5.4 结构校验）。
 *
 * <p>不信任 zip 里的任何东西：路径必须是相对、无 ..、无符号链接意图、无绝对路径；
 * 禁止可执行与脚本；限制条目数与解压总量（防 zip 炸弹）；manifest.json 与 README.md 必须存在。
 */
public final class PackageVerifier {

    /** 全部禁止的扩展名：local 型也不带代码，服务由部署者自己装 */
    private static final Set<String> FORBIDDEN_EXT = Set.of(
            "sh", "bat", "ps1", "exe", "dll", "so", "dylib", "jar", "class", "py", "js", "mjs",
            "cmd", "com", "scr", "msi", "vbs", "app", "bin");

    private static final int MAX_ENTRIES = 800;
    private static final long MAX_UNCOMPRESSED = 50L * 1024 * 1024;
    private static final long MAX_MANIFEST = 64 * 1024;

    private PackageVerifier() {
    }

    public record Result(boolean ok, List<ManifestIssue> issues, byte[] manifestJson,
                         long uncompressedBytes, int entries) {
    }

    public static Result inspect(byte[] zip) {
        List<ManifestIssue> issues = new ArrayList<>();
        byte[] manifest = null;
        boolean hasReadme = false;
        long total = 0;
        int count = 0;

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    issues.add(ManifestIssue.of("package", "TOO_MANY_ENTRIES",
                            "包内文件数超过 " + MAX_ENTRIES));
                    break;
                }
                String name = e.getName() == null ? "" : e.getName().replace('\\', '/');
                String problem = checkName(name);
                if (problem != null) {
                    issues.add(ManifestIssue.of("package:" + name, "BAD_ENTRY_PATH", problem));
                }
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    issues.add(ManifestIssue.of("package:" + name, "NESTED_ZIP", "包内不允许嵌套 zip"));
                }
                String ext = extension(name);
                if (FORBIDDEN_EXT.contains(ext)) {
                    issues.add(ManifestIssue.of("package:" + name, "FORBIDDEN_FILE_TYPE",
                            "包内禁止可执行/脚本文件: ." + ext));
                }
                long size = 0;
                int n;
                java.io.ByteArrayOutputStream keep = null;
                if ("manifest.json".equals(name)) {
                    keep = new java.io.ByteArrayOutputStream();
                }
                while ((n = in.read(buf)) > 0) {
                    size += n;
                    total += n;
                    if (keep != null) {
                        if (keep.size() + n > MAX_MANIFEST) {
                            issues.add(ManifestIssue.of("manifest.json", "MANIFEST_TOO_LARGE",
                                    "manifest.json 不能超过 64KB"));
                            keep = null;
                        } else {
                            keep.write(buf, 0, n);
                        }
                    }
                    if (total > MAX_UNCOMPRESSED) {
                        issues.add(ManifestIssue.of("package", "PACKAGE_TOO_LARGE",
                                "解压后总体积超过 50MB"));
                        break;
                    }
                }
                if (keep != null) {
                    manifest = keep.toByteArray();
                }
                if ("readme.md".equals(name.toLowerCase(Locale.ROOT))) {
                    hasReadme = true;
                }
                in.closeEntry();
                if (total > MAX_UNCOMPRESSED) {
                    break;
                }
            }
        } catch (Exception e) {
            issues.add(ManifestIssue.of("package", "ZIP_UNREADABLE",
                    "不是有效的 zip 或包含加密条目: " + e.getMessage()));
        }

        if (manifest == null) {
            issues.add(ManifestIssue.of("manifest.json", "MANIFEST_MISSING", "包内缺少 manifest.json"));
        }
        if (!hasReadme) {
            issues.add(ManifestIssue.of("README.md", "README_MISSING", "包内缺少 README.md"));
        }
        return new Result(issues.isEmpty(), issues, manifest, total, count);
    }

    /** 解压到目标目录（调用前必须先 inspect 通过）；再次做路径校验，双保险。 */
    public static void extract(byte[] zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName() == null ? "" : e.getName().replace('\\', '/');
                if (checkName(name) != null) {
                    throw new IOException("非法条目: " + name);
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("路径穿越: " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Path tmp = Files.createTempFile(out.getParent(), ".dl", ".part");
                // 注意：这里不能 try-with-resources 包 in —— 关掉它后面的条目就都没了
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static String checkName(String name) {
        if (name.isBlank()) {
            return "空文件名";
        }
        if (name.startsWith("/") || name.contains(":")) {
            return "不允许绝对路径";
        }
        if (name.contains("..")) {
            return "不允许 .. 路径";
        }
        if (name.length() > 200) {
            return "路径过长";
        }
        return null;
    }

    static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
