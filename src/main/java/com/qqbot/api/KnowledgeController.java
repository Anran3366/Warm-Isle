package com.qqbot.api;

import com.qqbot.knowledge.KnowledgeService;
import com.qqbot.knowledge.KnowledgeStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 知识库：上传、处理状态、分类、检索测试 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;
    private final KnowledgeStore store;

    public KnowledgeController(KnowledgeService service, KnowledgeStore store) {
        this.service = service;
        this.store = store;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() throws Exception {
        return store.stats();
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() throws Exception {
        return Map.of("items", store.categories());
    }

    @PostMapping("/categories")
    public Map<String, Object> addCategory(@RequestBody Map<String, Object> body) throws Exception {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            return Map.of("code", "INVALID_NAME", "message", "分类名不能为空");
        }
        store.addCategory(name);
        return Map.of("items", store.categories());
    }

    @DeleteMapping("/categories/{name}")
    public Map<String, Object> removeCategory(@PathVariable String name) throws Exception {
        store.removeCategory(name);
        return Map.of("items", store.categories());
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "category", required = false) String category,
                                                      @RequestParam(value = "parseType", required = false) String parseType) {
        try {
            return ResponseEntity.ok(service.upload(file, category, parseType));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", "UPLOAD_FAILED", "message", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/docs")
    public Map<String, Object> docs(@RequestParam(required = false) String category,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) throws Exception {
        return store.listDocs(category, page, size);
    }

    @PatchMapping("/docs/{id}")
    public Map<String, Object> toggle(@PathVariable long id, @RequestBody Map<String, Object> body) throws Exception {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", true)));
        store.setEnabled(id, enabled);
        return Map.of("ok", true);
    }

    @DeleteMapping("/docs/{id}")
    public Map<String, Object> delete(@PathVariable long id) throws Exception {
        store.deleteDoc(id);
        return Map.of("ok", true);
    }

    @PostMapping("/docs/{id}/reprocess")
    public Map<String, Object> reprocess(@PathVariable long id) throws Exception {
        store.updateStatus(id, "pending", null, 0);
        return Map.of("ok", true);
    }

    @GetMapping("/chunks")
    public Map<String, Object> allChunks(@RequestParam(required = false) Long docId,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String kind,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) throws Exception {
        int s = Math.min(200, Math.max(1, size));
        return store.listChunks(docId, q, category, kind, Math.max(1, page), s);
    }

    @PostMapping("/chunks")
    public Map<String, Object> createChunk(@RequestBody Map<String, Object> body) throws Exception {
        long docId = body.get("docId") instanceof Number n ? n.longValue() : 0L;
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (docId <= 0 || text.isEmpty()) {
            return Map.of("ok", false, "message", "docId 和 text 必填");
        }
        long id = store.createChunk(docId,
                body.get("category") == null ? "auto" : String.valueOf(body.get("category")),
                body.get("kind") == null ? "content" : String.valueOf(body.get("kind")),
                text);
        return Map.of("ok", true, "id", id);
    }

    @PutMapping("/chunks/{id}")
    public Map<String, Object> updateChunk(@PathVariable long id,
                                           @RequestBody Map<String, Object> body) throws Exception {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (text.isEmpty()) {
            return Map.of("ok", false, "message", "text 不能为空");
        }
        store.updateChunk(id,
                body.get("category") == null ? "auto" : String.valueOf(body.get("category")),
                body.get("kind") == null ? "content" : String.valueOf(body.get("kind")),
                text);
        return Map.of("ok", true);
    }

    @DeleteMapping("/chunks/{id}")
    public Map<String, Object> deleteChunk(@PathVariable long id) throws Exception {
        store.deleteChunk(id);
        return Map.of("ok", true);
    }

    @GetMapping("/docs/{id}/chunks")
    public Map<String, Object> chunks(@PathVariable long id,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "50") int size) throws Exception {
        return store.chunksOf(id, page, size);
    }

    /** 手动触发清洗（不等定时任务） */
    @PostMapping("/process")
    public Map<String, Object> process() {
        if (!service.inModelWindow()) {
            return Map.of("code", "OUT_OF_WINDOW",
                    "message", "解析任务只在每天 00:05–06:05 运行（避免占用模型额度/影响机器人）");
        }
        return service.processPending(false);
    }

    /** 检索测试 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) throws Exception {
        String q = String.valueOf(body.getOrDefault("q", ""));
        String category = String.valueOf(body.getOrDefault("category", ""));
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 5;
        return Map.of("items", store.search(q, category, limit));
    }
}
