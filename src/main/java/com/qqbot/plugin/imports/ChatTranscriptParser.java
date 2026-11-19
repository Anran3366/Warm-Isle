package com.qqbot.plugin.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 把 DeepSeek 导出 / 通用聊天导出统一成 messages[]。 */
@Component
public class ChatTranscriptParser {

    public record Message(String role, String content) {
    }

    public record Transcript(String title, List<Message> messages) {
    }

    private static final int MAX_UNZIP = 30 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Transcript parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            return parseZip(bytes);
        }
        return parseText(name, new String(bytes, StandardCharsets.UTF_8));
    }

    private Transcript parseZip(byte[] bytes) {
        List<Message> all = new ArrayList<>();
        String title = "";
        int total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".json") || name.endsWith(".md") || name.endsWith(".markdown")
                        || name.endsWith(".txt") || name.endsWith(".html") || name.endsWith(".htm"))) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNZIP) {
                        throw new IllegalArgumentException("压缩包解压后超过 30MB");
                    }
                    out.write(buf, 0, n);
                }
                Transcript one = parseText(name, out.toString(StandardCharsets.UTF_8));
                if (title.isBlank()) {
                    title = one.title();
                }
                all.addAll(one.messages());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("压缩包读取失败: " + e.getMessage());
        }
        return new Transcript(title.isBlank() ? "聊天记录" : title, dedupe(all));
    }

    private Transcript parseText(String name, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (name.endsWith(".json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Transcript json = parseJson(trimmed);
            if (json != null && !json.messages().isEmpty()) {
                return json;
            }
        }
        if (name.endsWith(".html") || name.endsWith(".htm") || trimmed.startsWith("<")) {
            trimmed = stripHtml(trimmed);
        }
        return parsePlain(trimmed);
    }

    private Transcript parseJson(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            String title = root.path("title").asText("");
            List<Message> messages = new ArrayList<>();
            collectJsonMessages(root, messages);
            if (messages.isEmpty()) {
                return null;
            }
            return new Transcript(title.isBlank() ? "聊天记录" : title, dedupe(messages));
        } catch (Exception e) {
            return null;
        }
    }

    private void collectJsonMessages(JsonNode node, List<Message> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectJsonMessages(item, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode messages = node.get("messages");
        if (messages != null && messages.isArray()) {
            addMessages(messages, out);
            return;
        }
        JsonNode mapping = node.get("mapping");
        if (mapping != null && mapping.isObject()) {
            mapping.fields().forEachRemaining(e -> {
                JsonNode msg = e.getValue().path("message");
                addMessage(msg, out);
            });
            return;
        }
        JsonNode conversations = node.get("conversations");
        if (conversations != null) {
            collectJsonMessages(conversations, out);
            return;
        }
        if (node.has("role") && node.has("content")) {
            addMessage(node, out);
        }
    }

    private void addMessages(JsonNode array, List<Message> out) {
        for (JsonNode item : array) {
            addMessage(item, out);
        }
    }

    private void addMessage(JsonNode node, List<Message> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        String role = normalizeRole(node.path("role").asText(
                node.path("author").path("role").asText("")));
        String content = contentText(node.get("content"));
        if (role == null || content.isBlank()) {
            return;
        }
        out.add(new Message(role, content.trim()));
    }

    private String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return content.asText("");
    }

    private Transcript parsePlain(String text) {
        List<Message> messages = new ArrayList<>();
        String currentRole = null;
        StringBuilder current = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            String role = roleOfLine(line);
            if (role != null) {
                if (currentRole != null && !current.toString().isBlank()) {
                    messages.add(new Message(currentRole, current.toString().trim()));
                }
                currentRole = role;
                current = new StringBuilder(line.replaceFirst(
                        "(?i)^(?:#{1,3}\\s*)?(?:\\*\\*)?(?:user|human|用户|我|you|assistant|ai|deepseek|助手|你)"
                                + "(?:\\*\\*)?\\s*[:：]?\\s*", ""));
            } else if (currentRole != null) {
                current.append('\n').append(line);
            }
        }
        if (currentRole != null && !current.toString().isBlank()) {
            messages.add(new Message(currentRole, current.toString().trim()));
        }
        if (messages.isEmpty()) {
            messages.add(new Message("user", text.trim()));
        }
        return new Transcript("聊天记录", dedupe(messages));
    }

    private String roleOfLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        String bare = lower.replaceFirst("^#{1,3}\\s*", "")
                .replaceFirst("^\\*\\*", "").replaceFirst("\\*\\*$", "").trim();
        if (bare.equals("user") || bare.equals("human") || bare.equals("用户") || bare.equals("我")
                || bare.equals("you")) {
            return "user";
        }
        if (bare.equals("assistant") || bare.equals("ai") || bare.equals("deepseek")
                || bare.equals("助手") || bare.equals("你")) {
            return "assistant";
        }
        if (lower.matches("^(?:#{1,3}\\s*)?(?:\\*\\*)?(user|human|用户|我|you)(?:\\*\\*)?\\s*[:：].*")) {
            return "user";
        }
        if (lower.matches("^(?:#{1,3}\\s*)?(?:\\*\\*)?(assistant|ai|deepseek|助手|你)(?:\\*\\*)?\\s*[:：].*")) {
            return "assistant";
        }
        return null;
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user", "human" -> "user";
            case "assistant", "ai", "deepseek", "model" -> "assistant";
            default -> null;
        };
    }

    private String stripHtml(String html) {
        String noScript = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        String text = noScript.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", " ");
        return text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private List<Message> dedupe(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        String last = null;
        for (Message m : messages) {
            String sig = m.role() + "\u0000" + m.content();
            if (!sig.equals(last)) {
                out.add(m);
            }
            last = sig;
        }
        return out;
    }
}
