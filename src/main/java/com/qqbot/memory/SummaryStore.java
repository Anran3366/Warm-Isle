package com.qqbot.memory;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** 每日摘要：每个用户每天一条，记录当天聊了什么。 */
@Component
public class SummaryStore {

    private final Db db;

    public SummaryStore(Db db) {
        this.db = db;
    }

    public void upsert(long qqId, String date, String text) {
        String sql = "INSERT INTO summaries(qq_id, date, text) VALUES(?,?,?) "
                + "ON CONFLICT(qq_id, date) DO UPDATE SET text=excluded.text";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, date);
            ps.setString(3, text);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入摘要失败: " + e.getMessage(), e);
        }
    }

    /** 按日期区间查询（后台摘要页用） */
    public List<String[]> range(long qqId, String from, String to) {
        String sql = "SELECT date, text FROM summaries WHERE qq_id=? AND date >= ? AND date <= ? ORDER BY date DESC";
        List<String[]> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, from);
            ps.setString(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new String[]{rs.getString("date"), rs.getString("text")});
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }

    public List<String[]> recent(long qqId, int limit) {
        String sql = "SELECT date, text FROM summaries WHERE qq_id=? ORDER BY date DESC LIMIT ?";
        List<String[]> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new String[]{rs.getString("date"), rs.getString("text")});
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }
}
