package com.qqbot.memory;

import com.qqbot.bot.BotAccountRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆向量索引：把每条 active 记忆（key + value）向量化存进 {@code memory_vectors}，
 * 供 {@link MemoryRecall} 做语义召回。
 *
 * <p>同步策略是最朴素也最稳的「对账式重建」：启动后、以及每 10 分钟，
 * 把库里多出来/改了内容的记忆补上向量，把已删除/归档的向量清掉。
 * 单账号最多几百条，一次对账几十毫秒，比写穿 + 消息队列简单得多。
 *
 * <p>向量服务不可用时静默跳过，检索自动退回关键词。
 */
@Component
public class MemoryVectorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorService.class);
    private static final int DIM = 512;

    private final Db db;
    private final MemoryStore memories;
    private final EmbeddingClient embeddings;
    private final BotAccountRunner accountRunner;

    public MemoryVectorService(Db db, MemoryStore memories, EmbeddingClient embeddings,
                               BotAccountRunner accountRunner) {
        this.db = db;
        this.memories = memories;
        this.embeddings = embeddings;
        this.accountRunner = accountRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(() -> accountRunner.forEach(id -> {
            try {
                reconcile();
            } catch (Exception e) {
                log.warn("记忆向量初始化失败 botId={}: {}", id, e.getMessage());
            }
        }), "memory-vector-init");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 3 * 60 * 1000L)
    public void scheduledReconcile() {
        accountRunner.forEach(id -> {
            try {
                reconcile();
            } catch (Exception e) {
                log.warn("记忆向量对账失败 botId={}: {}", id, e.getMessage());
            }
        });
    }

    /** 补齐缺失/过期的向量，删除多余向量；返回本次新写入条数 */
    public synchronized int reconcile() {
        int written = 0;
        for (Long qqId : users()) {
            List<Memory> active = memories.active(qqId);
            Map<Long, String> stamped = stamps(qqId);
            Map<Long, String> wanted = new HashMap<>();
            List<Memory> todo = new ArrayList<>();
            for (Memory m : active) {
                String stamp = stampOf(m);
                wanted.put(m.id(), stamp);
                if (!stamp.equals(stamped.get(m.id()))) {
                    todo.add(m);
                }
            }
            // 清理已删除/归档的向量
            for (Long id : stamped.keySet()) {
                if (!wanted.containsKey(id)) {
                    deleteVector(id);
                }
            }
            if (todo.isEmpty()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (Memory m : todo) {
                texts.add(textOf(m));
            }
            List<float[]> vectors = embeddings.embed(texts);
            if (vectors.size() != todo.size()) {
                continue; // 服务不可用，下次再试
            }
            for (int i = 0; i < todo.size(); i++) {
                Memory m = todo.get(i);
                upsert(qqId, m.id(), vectors.get(i), stampOf(m));
                written++;
            }
        }
        if (written > 0) {
            log.info("记忆向量已更新 {} 条", written);
        }
        return written;
    }

    /** 该用户全部记忆向量 */
    /** 全部用户的向量：星图跨用户连边用。 */
    public Map<Long, float[]> vectorsAll() {
        Map<Long, float[]> out = new HashMap<>();
        String sql = "SELECT memory_id, vec FROM memory_vectors";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(1), decode(rs.getBytes(2)));
            }
        } catch (Exception e) {
            log.debug("读取全量记忆向量失败: {}", e.getMessage());
        }
        return out;
    }

    public Map<Long, float[]> vectorsOf(long qqId) {
        Map<Long, float[]> out = new HashMap<>();
        String sql = "SELECT memory_id, vec FROM memory_vectors WHERE qq_id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), decode(rs.getBytes(2)));
                }
            }
        } catch (Exception e) {
            log.debug("读取记忆向量失败: {}", e.getMessage());
        }
        return out;
    }

    /** 把一句话向量化，供检索用；失败返回 null */
    public float[] queryVector(String text) {
        return embeddings.embedOne(text);
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return Math.max(-1, Math.min(1, dot));
    }

    // ---------------- 内部 ----------------

    private List<Long> users() {
        List<Long> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT qq_id FROM memories");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getLong(1));
            }
        } catch (Exception e) {
            log.debug("读取记忆用户失败: {}", e.getMessage());
        }
        return list;
    }

    /** memory_id -> 内容戳（内容变了就要重新向量化） */
    private Map<Long, String> stamps(long qqId) {
        Map<Long, String> out = new HashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT memory_id, updated_at FROM memory_vectors WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getString(2));
                }
            }
        } catch (Exception e) {
            log.debug("读取向量戳失败: {}", e.getMessage());
        }
        return out;
    }

    private void upsert(long qqId, long memoryId, float[] vec, String stamp) {
        String sql = "INSERT INTO memory_vectors(memory_id, qq_id, dim, vec, updated_at) "
                + "VALUES(?,?,?,?,?) ON CONFLICT(memory_id) DO UPDATE SET "
                + "qq_id=excluded.qq_id, dim=excluded.dim, vec=excluded.vec, updated_at=excluded.updated_at";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, memoryId);
            ps.setLong(2, qqId);
            ps.setInt(3, vec.length);
            ps.setBytes(4, encode(vec));
            ps.setString(5, stamp);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("写入向量失败 id={}: {}", memoryId, e.getMessage());
        }
    }

    private void deleteVector(long memoryId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM memory_vectors WHERE memory_id=?")) {
            ps.setLong(1, memoryId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("删除向量失败 id={}: {}", memoryId, e.getMessage());
        }
    }

    private static String textOf(Memory m) {
        String key = m.baseKey();
        String value = m.value() == null ? "" : m.value();
        return key.isEmpty() || value.contains(key) ? value : key + "：" + value;
    }

    /** 内容 + 重要度都算进戳：内容或权重变了就重建向量 */
    private static String stampOf(Memory m) {
        return Math.round(m.importance() * 100) + "|" + m.type() + "|" + textOf(m);
    }

    private static byte[] encode(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % 4 != 0) {
            return new float[0];
        }
        float[] v = new float[bytes.length / 4];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
