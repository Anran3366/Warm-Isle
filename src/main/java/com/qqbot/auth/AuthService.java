package com.qqbot.auth;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.GlobalDb;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 管理后台的会话认证：密码登录 → 随机 token → 存 SHA-256 哈希。
 * token 走 HttpOnly cookie，服务端只存哈希，重启不失效。
 */
@Service
public class AuthService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String KEY_HASH = "admin.passwordHash";

    private final GlobalDb db;
    private final BotProperties props;
    private final com.qqbot.memory.SharedSettings shared;

    public AuthService(GlobalDb db, BotProperties props, com.qqbot.memory.SharedSettings shared) {
        this.db = db;
        this.props = props;
        this.shared = shared;
    }

    /** 校验密码；成功返回 token，失败返回 null */
    public String login(String password) {
        if (!checkPassword(password)) {
            return null;
        }
        return issueToken();
    }

    /** 只校验密码，不建会话（扫码授权页用） */
    public boolean checkPassword(String password) {
        if (password == null) {
            return false;
        }
        String custom = shared.get(KEY_HASH, null);
        if (custom != null && !custom.isBlank()) {
            return verifySalted(password, custom);
        }
        String expected = props.getAdmin().getPassword();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return constantTimeEquals(expected, password);
    }

    /** 是否已经改成过自定义密码（存在 shared.db 里） */
    public boolean hasCustomPassword() {
        String custom = shared.get(KEY_HASH, null);
        return custom != null && !custom.isBlank();
    }

    /** 改密码：写入加盐哈希，并踢掉所有旧会话 */
    public void changePassword(String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码至少 6 位");
        }
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        shared.set(KEY_HASH, "sha256$" + salt + "$" + sha256(salt + newPassword));
        dropAllSessions();
    }

    private boolean verifySalted(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 3) {
            return false;
        }
        String expected = sha256(parts[1] + password);
        return constantTimeEquals(parts[2], expected);
    }

    private void dropAllSessions() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_sessions")) {
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 清理失败不影响改密码
        }
    }

    /** 直接签发一个会话 token（扫码确认后调用） */
    public String issueToken() {
        String token = randomToken();
        LocalDateTime expires = LocalDateTime.now().plusDays(props.getAdmin().getSessionTtlDays());
        String sql = "INSERT INTO auth_sessions(token_hash, created_at, expires_at) VALUES(?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sha256(token));
            ps.setString(2, LocalDateTime.now().format(TS));
            ps.setString(3, expires.format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("创建会话失败: " + e.getMessage(), e);
        }
        cleanupExpired();
        return token;
    }

    public boolean valid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM auth_sessions WHERE token_hash=? AND expires_at > ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sha256(token));
            ps.setString(2, LocalDateTime.now().format(TS));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_sessions WHERE token_hash=?")) {
            ps.setString(1, sha256(token));
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 退出失败不影响前端
        }
    }

    /** 密码是否已配置 */
    public boolean configured() {
        String pwd = props.getAdmin().getPassword();
        return pwd != null && !pwd.isBlank();
    }

    private void cleanupExpired() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_sessions WHERE expires_at <= ?")) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 清理失败可忽略
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
