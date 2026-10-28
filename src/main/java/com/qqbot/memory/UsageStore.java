package com.qqbot.memory;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * token 用量与花费记录，供 /api/status 与后续后台使用。
 */
@Component
public class UsageStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;

    public UsageStore(Db db) {
        this.db = db;
    }

    public void add(String model, int inputTokens, int outputTokens, double cost) {
        add(model, inputTokens, outputTokens, cost, 0);
    }

    public void add(String model, int inputTokens, int outputTokens, double cost, long qqId) {
        String sql = "INSERT INTO usage(model, input_tokens, output_tokens, cost, qq_id, created_at) VALUES(?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, model);
            ps.setInt(2, inputTokens);
            ps.setInt(3, outputTokens);
            ps.setDouble(4, cost);
            ps.setLong(5, qqId);
            ps.setString(6, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            // 计费失败不应影响聊天
        }
    }

    /** 按天汇总：[{date, cost, tokens}]，日期区间为闭区间 */    public java.util.List<java.util.Map<String, Object>> daily(String from, String to) {
        String sql = "SELECT substr(created_at,1,10) AS d, SUM(cost) AS c, "
                + "SUM(input_tokens+output_tokens) AS t FROM usage "
                + "WHERE substr(created_at,1,10) BETWEEN ? AND ? GROUP BY d ORDER BY d";
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from);
            ps.setString(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("date", rs.getString("d"));
                    item.put("cost", Math.round(rs.getDouble("c") * 10000.0) / 10000.0);
                    item.put("tokens", rs.getLong("t"));
                    list.add(item);
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }

    /** 每个用户今天/近 7 天的花费 */
    public java.util.List<java.util.Map<String, Object>> perUser(String from) {
        String sql = "SELECT qq_id, SUM(cost) c, SUM(input_tokens+output_tokens) t, COUNT(*) n "
                + "FROM usage WHERE created_at >= ? GROUP BY qq_id ORDER BY c DESC LIMIT 50";
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("qqId", rs.getLong("qq_id"));
                    m.put("cost", Math.round(rs.getDouble("c") * 10000.0) / 10000.0);
                    m.put("tokens", rs.getLong("t"));
                    m.put("calls", rs.getInt("n"));
                    list.add(m);
                }
            }
        } catch (Exception ignored) {
            // 返回空
        }
        return list;
    }

    public double todayCost() {
        return todayDouble("SELECT COALESCE(SUM(cost),0) FROM usage WHERE created_at >= ?");
    }

    public long todayTokens() {
        String sql = "SELECT COALESCE(SUM(input_tokens + output_tokens),0) FROM usage WHERE created_at >= ?";
        return (long) query(sql);
    }

    private double todayDouble(String sql) {
        return query(sql);
    }

    private double query(String sql) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, LocalDate.now() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}