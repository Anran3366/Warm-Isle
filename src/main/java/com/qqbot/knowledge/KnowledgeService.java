package com.qqbot.knowledge;

import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 知识库：上传、清洗入库、自动分类 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private final KnowledgeStore store;
    private final DeepSeekClient llm;
    private final com.qqbot.memory.PersonaStore personas;
    private final Path uploadDir = Path.of("data", "knowledge", "uploads");

    public KnowledgeService(KnowledgeStore store, DeepSeekClient llm,
                            com.qqbot.memory.PersonaStore personas) {
        this.store = store;
        this.llm = llm;
        this.personas = personas;
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.warn("创建知识库目录失败: {}", e.getMessage());
        }
    }

    /** 上传：落盘 + 入队，真正的清洗由定时任务或手动触发 */
    public Map<String, Object> upload(MultipartFile file, String category, String parseType) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = file.getOriginalFilename() == null ? "unknown.txt" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".docx") || lower.endsWith(".markdown"))) {
            throw new IllegalArgumentException("仅支持 .md / .txt / .docx");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 30MB");
        }
        String ext = lower.substring(lower.lastIndexOf('.'));
        String stored = UUID.randomUUID().toString().replace("-", "") + ext;
        Files.createDirectories(uploadDir);
        file.transferTo(uploadDir.resolve(stored).toAbsolutePath());
        long id = store.insertDoc(name, stored, file.getSize(), category, parseType);
        log.info("知识库上传: id={} file={} size={} category={}", id, name, file.getSize(), category);
        return Map.of("id", id, "filename", name, "status", "pending");
    }

    /**
     * 处理待入库文件。
     * @param windowed true 时只在 00:00–06:00 之间处理（定时任务用）
     */
    public Map<String, Object> processPending(boolean windowed) {
        int done = 0;
        int failed = 0;
        int skipped = 0;
        try {
            List<Map<String, Object>> docs = store.pendingDocs(50);
            for (Map<String, Object> doc : docs) {
                if (windowed && !inModelWindow()) {
                    skipped++;
                    continue;
                }
                if (!memoryOk()) {
                    skipped++;
                    continue;
                }
                Thread.sleep(1500);
                long id = ((Number) doc.get("id")).longValue();
                try {
                    processOne(doc);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("处理失败 id={}: {}", id, e.getMessage());
                    try {
                        store.updateStatus(id, "failed", truncate(e.getMessage()), 0);
                    } catch (Exception ignored) {
                        // 状态更新失败不阻断其他文件
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理队列失败", e);
        }
        return Map.of("processed", done, "failed", failed, "skipped", skipped);
    }

    /** 00:05–06:05 之外不跑任何模型相关处理 */
    @org.springframework.beans.factory.annotation.Value("${KNOWLEDGE_ALLOW_ANYTIME:false}")
    private boolean allowAnytime;

    public boolean inModelWindow() {
        if (allowAnytime) {
            return true;
        }
        java.time.LocalTime now = java.time.LocalTime.now();
        return !now.isBefore(java.time.LocalTime.of(0, 5)) && now.isBefore(java.time.LocalTime.of(6, 5));
    }

    /** 剩余内存（MB），读不到返回 -1 */
    private long availableMb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String d = line.replaceAll("\\D", "");
                    return Long.parseLong(d) / 1024;
                }
            }
        } catch (Exception ignored) {
            // 非 Linux 或读取失败
        }
        return -1;
    }

    /** 清理前确认内存充足，避免把 NapCat 挤掉 */
    private boolean memoryOk() {
        long avail = availableMb();
        if (avail < 0) {
            return true;
        }
        if (avail < 500) {
            log.warn("可用内存仅 {}MB，暂停解析", avail);
            return false;
        }
        return true;
    }

    private void processOne(Map<String, Object> doc) throws Exception {
        long id = ((Number) doc.get("id")).longValue();
        String filename = String.valueOf(doc.get("filename"));
        String category = String.valueOf(doc.get("category"));
        String storedName = String.valueOf(doc.get("storedName"));
        if (storedName.isBlank() || "null".equals(storedName)) {
            throw new IllegalStateException("找不到原始文件记录（stored_name 为空）");
        }
        Path file = uploadDir.resolve(storedName);
        if (!Files.exists(file)) {
            throw new IllegalStateException("原始文件不存在: " + file.getFileName());
        }

        byte[] bytes = Files.readAllBytes(file);
        String raw = DocTextExtractor.extract(filename, bytes);
        String cleaned = TextCleaner.clean(raw);
        if (cleaned.isBlank()) {
            throw new IllegalStateException("清洗后没有有效文本");
        }

        String parseType = String.valueOf(doc.getOrDefault("parseType", "style"));
        if ("persona".equals(parseType)) {
            String personaName = generatePersona(cleaned);
            store.clearChunks(id);
            store.updateStatus(id, "done", null, 0);
            log.info("人设生成完成: doc={} persona={}", filename, personaName);
            return;
        }
        List<TextCleaner.Piece> pieces = TextCleaner.split(cleaned);
        if (pieces.isEmpty()) {
            throw new IllegalStateException("没有切出任何片段");
        }

        String finalCategory = resolveCategory(category, cleaned);
        store.clearChunks(id);
        int count = 0;
        for (TextCleaner.Piece p : pieces) {
            if (p.text().isBlank()) {
                continue;
            }
            store.insertChunk(id, finalCategory, p.kind(), p.text());
            count++;
        }
        store.setCategory(id, finalCategory);
        store.updateStatus(id, "done", null, count);
        log.info("知识库入库: id={} file={} category={} chunks={}", id, filename, finalCategory, count);
    }

    /** 从小说文本里抽取一个角色，先生成原始人设，再让模型润色成完整提示词 */
    private String generatePersona(String text) throws Exception {
        String sample = sampleText(text, 12000);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是角色分析师。从给定的小说片段里挑选一个最适合做聊天陪聊人设的角色，"
                        + "输出严格的 JSON：{\"name\":\"角色名\",\"content\":\"第二人称的人设描述\"}。"
                        + "content 要包含：身份、性格、说话方式（口头禅/语气）、对用户的态度、边界（不聊什么）。"
                        + "只输出 JSON，不要解释。"));
        messages.add(Map.of("role", "user", "content", sample));
        LlmResult r = llm.chat(messages, 0.7, 600);
        String out = r.content() == null ? "" : r.content().trim();
        int start = out.indexOf('{');
        int end = out.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型未返回有效人设 JSON");
        }
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.substring(start, end + 1));
        String name = node.path("name").asText("").trim();
        String content = node.path("content").asText("").trim();
        if (name.isEmpty() || content.isEmpty()) {
            throw new IllegalStateException("人设缺少名称或内容");
        }
        String polished = polishPersona(name, content);
        personas.create(name, polished);
        return name;
    }

    /** 开头 + 中间 + 结尾各取一段，避免只读小说开头 */
    private String sampleText(String text, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        int part = budget / 3;
        String head = text.substring(0, part);
        int midStart = Math.max(part, text.length() / 2 - part / 2);
        String middle = text.substring(midStart, Math.min(text.length(), midStart + part));
        String tail = text.substring(Math.max(0, text.length() - part));
        return head + "\n……\n" + middle + "\n……\n" + tail;
    }

    /** 把抽取出来的人设润色成可直接使用的人格提示词 */
    private String polishPersona(String name, String raw) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content",
                    "你是人格设定编辑。把下面这份粗糙的角色设定，改写成一个完整、可直接用作聊天机器人系统提示词的人设。"
                            + "要求：\n"
                            + "1. 用第二人称「你」来写，不要说「该角色」。\n"
                            + "2. 必须分块覆盖：【身份】、【性格】、【说话方式】、【怎么回应对方情绪】、【聊天风格】、【边界】、【绝对不要】、【口头禅参考】。\n"
                            + "3. 说话方式要具体：句子长短、语气词、能不能用表情、能不能撒娇。\n"
                            + "4. 不编造具体职业、公司、学校、城市；不预设对方性别和关系。\n"
                            + "5. 没有依据的细节宁可写得含糊，不要瞎编；不要出现「根据原文」这类话。\n"
                            + "6. 400-800 字，只输出人设正文，不要解释。"));
            messages.add(Map.of("role", "user", "content", "角色名：" + name + "\n原始设定：\n" + raw));
            LlmResult r = llm.chat(messages, 0.7, 1200);
            String polished = r.content() == null ? "" : r.content().trim();
            return polished.isBlank() ? raw : polished;
        } catch (Exception e) {
            log.warn("人设润色失败，使用原始版本: {}", e.getMessage());
            return raw;
        }
    }

    /** category=auto 时用模型判断，失败回退「日常」 */
    private String resolveCategory(String category, String text) {
        if (category != null && !category.isBlank() && !"auto".equals(category)) {
            return category;
        }
        List<String> cats;
        try {
            cats = store.categories();
        } catch (Exception e) {
            cats = List.of("日常");
        }
        try {
            String sample = text.length() > 1500 ? text.substring(0, 1500) : text;
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content",
                    "你是文本分类器。把用户给的文本归类到以下类别之一：" + String.join("、", cats)
                            + "。只输出类别名，不要解释。"));
            messages.add(Map.of("role", "user", "content", sample));
            LlmResult r = llm.chat(messages, 0.1, 8);
            String out = r.content() == null ? "" : r.content().trim();
            for (String c : cats) {
                if (out.contains(c)) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.warn("自动分类失败，回退日常: {}", e.getMessage());
        }
        return "日常";
    }



    private String truncate(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
