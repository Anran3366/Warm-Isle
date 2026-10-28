package com.qqbot.knowledge;

import com.qqbot.memory.SharedDb;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识库的 SQLite 读写 */
@Component
public class KnowledgeStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SharedDb db;

    public KnowledgeStore(SharedDb db) {
        this.db = db;
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }

    // ---------- 文档 ----------

    public long insertDoc(String filename, String storedName, long size, String category, String parseType) throws Exception {
        String sql = "INSERT INTO knowledge_docs(filename, stored_name, category, status, size, uploaded_at, parse_type) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, filename);
            ps.setString(2, storedName);
            ps.setString(3, category == null || category.isBlank() ? "auto" : category);
            ps.setString(4, "pending");
            ps.setLong(5, size);
            ps.setString(6, now());
            ps.setString(7, parseType == null || parseType.isBlank() ? "style" : parseType);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public Map<String, Object> listDocs(String category, int page, int size) throws Exception {
        StringBuilder where = new StringBuilder(" WHERE status<>'deleted'");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            where.append(" AND category=?");
            args.add(category);
        }
        String countSql = "SELECT COUNT(*) FROM knowledge_docs" + where;
        String sql = "SELECT * FROM knowledge_docs" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = new ArrayList<>();
        long total;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(countSql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = bind(ps, args);
                ps.setInt(i++, size);
                ps.setInt(i, (Math.max(1, page) - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(row(rs));
                    }
                }
            }
        }
        return Map.of("items", items, "total", total);
    }

    public Map<String, Object> getDoc(long id) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM knowledge_docs WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> pendingDocs(int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM knowledge_docs WHERE status IN ('pending','failed') ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs));
                }
            }
        }
        return out;
    }

    public void updateStatus(long id, String status, String error, int chunks) throws Exception {
        String sql = "UPDATE knowledge_docs SET status=?, error=?, chunk_count=?, processed_at=? WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setInt(3, chunks);
            ps.setString(4, "done".equals(status) ? now() : null);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void setEnabled(long id, boolean enabled) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("UPDATE knowledge_docs SET enabled=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 删除文件：只标记文档为 deleted，切片保留（可在「切片」页继续管理和使用） */
    public void deleteDoc(long id) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE knowledge_docs SET status='deleted', enabled=0 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void clearChunks(long docId) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM knowledge_chunks WHERE doc_id=?")) {
            ps.setLong(1, docId);
            ps.executeUpdate();
        }
    }

    public void setCategory(long id, String category) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("UPDATE knowledge_docs SET category=? WHERE id=?")) {
            ps.setString(1, category);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ---------- 切片 ----------

    public void insertChunk(long docId, String category, String kind, String text) throws Exception {
        String sql = "INSERT INTO knowledge_chunks(doc_id, category, kind, text, created_at) VALUES(?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, docId);
            ps.setString(2, category);
            ps.setString(3, kind);
            ps.setString(4, text);
            ps.setString(5, now());
            ps.executeUpdate();
        }
    }

    /** 全局切片列表（可按文档/分类/关键词过滤），文档被删也能看到来源 */
    public Map<String, Object> listChunks(Long docId, String q, String category, String kind, int page, int size) throws Exception {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (docId != null && docId > 0) {
            where.append(" AND c.doc_id=?");
            args.add(docId);
        }
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            where.append(" AND c.category=?");
            args.add(category);
        }
        if (kind != null && !kind.isBlank() && !"all".equals(kind)) {
            where.append(" AND c.kind=?");
            args.add(kind);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND c.text LIKE ?");
            args.add("%" + q + "%");
        }
        String base = " FROM knowledge_chunks c LEFT JOIN knowledge_docs d ON d.id=c.doc_id" + where;
        String countSql = "SELECT COUNT(*)" + base;
        String sql = "SELECT c.id, c.doc_id, c.category, c.kind, c.text, c.created_at,"
                + " COALESCE(d.filename,'(已删除的文件)') AS filename, COALESCE(d.status,'deleted') AS doc_status"
                + base + " ORDER BY c.id DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = new ArrayList<>();
        long total;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(countSql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = bind(ps, args);
                ps.setInt(i++, size);
                ps.setInt(i, (Math.max(1, page) - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("docId", rs.getLong("doc_id"));
                        m.put("filename", rs.getString("filename"));
                        m.put("docStatus", rs.getString("doc_status"));
                        m.put("category", rs.getString("category"));
                        m.put("kind", rs.getString("kind"));
                        m.put("text", rs.getString("text"));
                        m.put("createdAt", rs.getString("created_at"));
                        items.add(m);
                    }
                }
            }
        }
        return Map.of("items", items, "total", total);
    }

    /** 手动新增切片 */
    public long createChunk(long docId, String category, String kind, String text) throws Exception {
        String sql = "INSERT INTO knowledge_chunks(doc_id, category, kind, text, created_at) VALUES(?,?,?,?,?)";
        long id;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, docId);
            ps.setString(2, category == null || category.isBlank() ? "auto" : category);
            ps.setString(3, kind == null || kind.isBlank() ? "content" : kind);
            ps.setString(4, text);
            ps.setString(5, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                id = rs.next() ? rs.getLong(1) : -1;
            }
        }
        refreshChunkCount(docId);
        return id;
    }

    public void updateChunk(long id, String category, String kind, String text) throws Exception {
        Long docId = null;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT doc_id FROM knowledge_chunks WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        docId = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE knowledge_chunks SET category=?, kind=?, text=? WHERE id=?")) {
                ps.setString(1, category == null || category.isBlank() ? "auto" : category);
                ps.setString(2, kind == null || kind.isBlank() ? "content" : kind);
                ps.setString(3, text);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
        }
        if (docId != null) {
            refreshChunkCount(docId);
        }
    }

    public void deleteChunk(long id) throws Exception {
        Long docId = null;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT doc_id FROM knowledge_chunks WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        docId = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM knowledge_chunks WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
        if (docId != null) {
            refreshChunkCount(docId);
        }
    }

    private void refreshChunkCount(long docId) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE knowledge_docs SET chunk_count=(SELECT COUNT(*) FROM knowledge_chunks WHERE doc_id=?) WHERE id=?")) {
            ps.setLong(1, docId);
            ps.setLong(2, docId);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 统计失败不影响主流程
        }
    }

    public Map<String, Object> chunksOf(long docId, int page, int size) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        long total;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM knowledge_chunks WHERE doc_id=?")) {
                ps.setLong(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, category, kind, text FROM knowledge_chunks WHERE doc_id=? ORDER BY id LIMIT ? OFFSET ?")) {
                ps.setLong(1, docId);
                ps.setInt(2, size);
                ps.setInt(3, (Math.max(1, page) - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("category", rs.getString("category"));
                        m.put("kind", rs.getString("kind"));
                        m.put("text", rs.getString("text"));
                        items.add(m);
                    }
                }
            }
        }
        return Map.of("items", items, "total", total);
    }

    // ---------- 检索（关键词 / 二元组打分） ----------

    public List<Map<String, Object>> search(String q, String category, int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        if (q == null || q.isBlank()) {
            return out;
        }
        String[] terms = q.replaceAll("[\\p{Punct}\\s]+", " ").trim().split("\\s+");
        StringBuilder where = new StringBuilder(" WHERE d.enabled=1");
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            where.append(" AND d.category=?");
        }
        String sql = "SELECT c.id, c.doc_id, c.category, c.kind, c.text FROM knowledge_chunks c"
                + " JOIN knowledge_docs d ON d.id=c.doc_id" + where + " LIMIT 2000";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (category != null && !category.isBlank() && !"all".equals(category)) {
                ps.setString(idx++, category);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString("text");
                    int score = 0;
                    for (String t : terms) {
                        if (t.length() < 2) {
                            continue;
                        }
                        if (text.contains(t)) {
                            score += 3;
                        } else {
                            // 二元组部分匹配
                            for (int i = 0; i + 2 <= t.length(); i++) {
                                if (text.contains(t.substring(i, i + 2))) {
                                    score += 2;
                                }
                            }
                        }
                    }
                    if (score > 0) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("docId", rs.getLong("doc_id"));
                        m.put("category", rs.getString("category"));
                        m.put("kind", rs.getString("kind"));
                        m.put("text", text);
                        m.put("score", score);
                        out.add(m);
                    }
                }
            }
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** 随机取若干风格样本（用于聊天注入，按分类过滤） */
    public List<String> randomStyleSamples(String category, int limit) throws Exception {
        List<String> out = new ArrayList<>();
        String sql = "SELECT c.text FROM knowledge_chunks c JOIN knowledge_docs d ON d.id=c.doc_id"
                + " WHERE d.enabled=1 AND c.kind='style' AND (? IS NULL OR c.category=?)"
                + " ORDER BY RANDOM() LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (category == null || category.isBlank() || "all".equals(category)) {
                ps.setNull(1, java.sql.Types.VARCHAR);
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, category);
                ps.setString(2, category);
            }
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    // ---------- 分类 ----------

    public List<String> categories() throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM knowledge_categories ORDER BY sort, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    public void addCategory(String name) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES(?, 99)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    public void removeCategory(String name) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM knowledge_categories WHERE name=?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> stats() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Connection c = db.open()) {
            m.put("docs", scalar(c, "SELECT COUNT(*) FROM knowledge_docs"));
            m.put("chunks", scalar(c, "SELECT COUNT(*) FROM knowledge_chunks"));
            m.put("pending", scalar(c, "SELECT COUNT(*) FROM knowledge_docs WHERE status='pending'"));
            m.put("failed", scalar(c, "SELECT COUNT(*) FROM knowledge_docs WHERE status='failed'"));
            m.put("styleChunks", scalar(c, "SELECT COUNT(*) FROM knowledge_chunks WHERE kind='style'"));
        }
        return m;
    }

    private long scalar(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private int bind(PreparedStatement ps, List<Object> args) throws Exception {
        int i = 1;
        for (Object a : args) {
            ps.setObject(i++, a);
        }
        return i;
    }

    private Map<String, Object> row(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("filename", rs.getString("filename"));
        m.put("storedName", rs.getString("stored_name"));
        m.put("category", rs.getString("category"));
        m.put("status", rs.getString("status"));
        m.put("error", rs.getString("error"));
        m.put("size", rs.getLong("size"));
        m.put("chunkCount", rs.getInt("chunk_count"));
        m.put("uploadedAt", rs.getString("uploaded_at"));
        m.put("processedAt", rs.getString("processed_at"));
        m.put("enabled", rs.getInt("enabled") == 1);
        try {
            m.put("parseType", rs.getString("parse_type"));
        } catch (Exception e) {
            m.put("parseType", "style");
        }
        return m;
    }
}
