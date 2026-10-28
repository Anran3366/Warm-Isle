package com.qqbot.auth;

import com.qqbot.memory.GlobalDb;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

/**
 * 扫码登录：PC 生成二维码 → 手机打开 /m/{token} 输入授权码 → PC 轮询到 approved → 领取会话。
 * 二维码本身只是 token，不携带密码。
 */
@Service
public class QrLoginService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TTL_MINUTES = 5;

    private final GlobalDb db;
    private final AuthService auth;

    public QrLoginService(GlobalDb db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    public Map<String, Object> create() throws Exception {
        cleanup();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO login_qr(token, status, created_at, expires_at) VALUES(?,?,?,?)")) {
            ps.setString(1, token);
            ps.setString(2, "pending");
            ps.setString(3, LocalDateTime.now().format(TS));
            ps.setString(4, LocalDateTime.now().plusMinutes(TTL_MINUTES).format(TS));
            ps.executeUpdate();
        }
        return Map.of("token", token, "ttlSeconds", TTL_MINUTES * 60);
    }

    public String status(String token) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT status, expires_at FROM login_qr WHERE token=?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "expired";
                }
                String status = rs.getString("status");
                if (LocalDateTime.parse(rs.getString("expires_at"), TS).isBefore(LocalDateTime.now())) {
                    return "expired";
                }
                return status;
            }
        }
    }

    public boolean approve(String token, String password) throws Exception {
        if (!auth.checkPassword(password)) {
            return false;
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE login_qr SET status='approved' WHERE token=? AND status='pending'")) {
            ps.setString(1, token);
            return ps.executeUpdate() > 0;
        }
    }

    /** 确认后由 PC 领取会话；只能领一次 */
    public String claim(String token) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM login_qr WHERE token=? AND status='approved'")) {
            ps.setString(1, token);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        return auth.issueToken();
    }

    private void cleanup() throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM login_qr WHERE expires_at < ?")) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        }
    }
}
