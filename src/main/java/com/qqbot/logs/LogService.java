package com.qqbot.logs;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取服务日志尾部，供后台「日志」页展示 */
@Service
public class LogService {

    private static final Pattern LINE = Pattern.compile(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}|\\d{2}:\\d{2}:\\d{2})\\s+"
                    + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s+(?<logger>\\S+)\\s+-\\s+(?<msg>.*)$");

    private static final Map<String, String> FILES = Map.of(
            "bot", "logs/service.log",
            "guard", "logs/guard.log");

    public List<Map<String, String>> tail(String source, String level, int count) {
        String file = FILES.getOrDefault(source == null ? "bot" : source, FILES.get("bot"));
        Path path = Path.of(file).toAbsolutePath();
        List<String> lines = readTail(path, Math.min(5000, Math.max(count * 4, 400)));
        String want = level == null || level.isBlank() ? null : level.toUpperCase();

        List<Map<String, String>> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            Map<String, String> item = new LinkedHashMap<>();
            if (m.matches()) {
                String lv = m.group("level");
                if (want != null && !want.equals(lv) && !"ALL".equals(want)) {
                    continue;
                }
                item.put("ts", m.group("ts"));
                item.put("level", lv);
                item.put("logger", m.group("logger"));
                item.put("msg", m.group("msg"));
            } else {
                // 非标准日志行（空行、启动 banner、堆栈）：空行直接丢，其余保留为 INFO 方便排查
                if (line.isBlank() || (want != null && !"ALL".equals(want))) {
                    continue;
                }
                item.put("ts", "");
                item.put("level", "INFO");
                item.put("logger", "");
                item.put("msg", line);
            }
            out.add(item);
        }
        int from = Math.max(0, out.size() - count);
        return out.subList(from, out.size());
    }

    /** 从文件尾部读最多 limit 行，避免把大文件整个读进内存 */
    private List<String> readTail(Path path, int limit) {
        Deque<String> deque = new ArrayDeque<>();
        try {
            if (!Files.exists(path)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : all) {
                deque.addLast(line);
                if (deque.size() > limit) {
                    deque.removeFirst();
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(deque);
    }
}
