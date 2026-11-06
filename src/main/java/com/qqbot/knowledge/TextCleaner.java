package com.qqbot.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识库文本清洗与切片。
 * 目标：把电视剧台词、小说、聊天记录里「像人说话」的部分抽出来，
 * 同时保留可检索的知识性内容。
 */
public final class TextCleaner {

    private TextCleaner() {
    }

    /** 一条切片：style=说话风格样本，content=知识内容 */
    public record Piece(String text, String kind) {
    }

    private static final Pattern SPEAKER = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_·\\-（）() ]{1,14}\\s*[:：]\\s*(.+)$");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKDOWN = Pattern.compile("^[#>*\\-\\s]+|`{1,3}|\\*\\*|__");
    private static final int CONTENT_CHUNK = 600;
    private static final int OVERLAP = 100;
    private static final int STYLE_MAX = 300;

    /** 整篇清洗：去 markdown、URL、多余空行、重复行 */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = URL.matcher(text).replaceAll("");
        StringBuilder out = new StringBuilder(text.length());
        String last = null;
        for (String line : text.split("\n")) {
            String t = MARKDOWN.matcher(line).replaceAll("").trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() < 2) {
                continue;
            }
            if (t.equals(last)) {
                continue;
            }
            out.append(t).append('\n');
            last = t;
        }
        return out.toString().trim();
    }

    /**
     * 切片：台词行按句抽成 style；其余按 600 字聚合为 content。
     */
    public static List<Piece> split(String cleaned) {
        List<Piece> pieces = new ArrayList<>();
        if (cleaned == null || cleaned.isBlank()) {
            return pieces;
        }
        StringBuilder buf = new StringBuilder();
        for (String line : cleaned.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String dialogue = dialogueOf(t);
            if (dialogue != null && dialogue.length() <= STYLE_MAX) {
                flush(buf, pieces);
                pieces.add(new Piece(dialogue, "style"));
                continue;
            }
            if (buf.length() > 0 && buf.length() + t.length() > CONTENT_CHUNK) {
                flush(buf, pieces);
            }
            buf.append(t).append('\n');
        }
        flush(buf, pieces);
        return pieces;
    }

    /** 判断是否是「角色：台词」或引号包裹的台词，返回台词本身 */
    private static String dialogueOf(String line) {
        var m = SPEAKER.matcher(line);
        if (m.matches()) {
            String body = m.group(1).trim();
            return body.length() >= 2 ? body : null;
        }
        if (line.length() >= 4) {
            char c = line.charAt(0);
            if ((c == '“' || c == '"' || c == '「' || c == '『') && line.length() <= STYLE_MAX) {
                return line.substring(1, line.length() - 1).trim();
            }
        }
        return null;
    }

    private static void flush(StringBuilder buf, List<Piece> pieces) {
        if (buf.length() == 0) {
            return;
        }
        String text = buf.toString().trim();
        if (!text.isEmpty()) {
            pieces.add(new Piece(text, "content"));
        }
        // 保留尾部作为下一块的重叠
        if (text.length() > OVERLAP) {
            buf.setLength(0);
            buf.append(text.substring(text.length() - OVERLAP));
        } else {
            buf.setLength(0);
        }
    }
}
