package com.qqbot.account;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.memory.GlobalDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 机器人账号管理：每个账号一个独立 NapCat 容器。
 * 通过 docker.sock 调 Engine API（容器内已装 curl），不依赖 docker CLI。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SOCKET = "/var/run/docker.sock";

    private final GlobalDb globalDb;
    private final OneBotRegistry registry;
    private final ProtocolBackends backends;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final String hostDir;
    private final String network;
    /** 容器截图二维码的短期缓存：账号 id -> {png, 时间戳} */
    private final Map<Long, Object[]> qrCache = new java.util.concurrent.ConcurrentHashMap<>();

    public AccountService(GlobalDb globalDb, OneBotRegistry registry,
                          ProtocolBackends backends,
                          org.springframework.core.env.Environment env) {
        this.globalDb = globalDb;
        this.registry = registry;
        this.backends = backends;
        this.hostDir = env.getProperty("ACCOUNT_HOST_DIR", "/srv/qqbot/deploy/linux/data/napcat-accounts");
        this.network = env.getProperty("ACCOUNT_COMPOSE_NETWORK", "ai-companion_default");
    }

    // ---------- 查询 ----------

    public List<Map<String, Object>> list() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM bot_accounts ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(row(rs));
            }
        }
        return out;
    }

    public Map<String, Object> get(long id) throws Exception {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM bot_accounts WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    private Map<String, Object> row(ResultSet rs) throws Exception {
        long id = rs.getLong("id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("qqId", rs.getLong("qq_id"));
        m.put("name", rs.getString("name"));
        String backendKey = rs.getString("backend");
        ProtocolBackend backend = backends.get(backendKey);
        m.put("backend", backend.key());
        m.put("backendName", backend.displayName());
        m.put("container", rs.getString("container"));
        m.put("wsUrl", rs.getString("ws_url"));
        m.put("wsPort", rs.getInt("ws_port"));
        m.put("uiPort", rs.getInt("ui_port"));
        m.put("enabled", rs.getInt("enabled") == 1);
        m.put("createdAt", rs.getString("created_at"));
        OneBotClient client = registry.get(id);
        boolean online = client != null && client.isConnected();
        m.put("online", online);
        if (online && client.getSelfId() > 0) {
            m.put("qqId", client.getSelfId());
            syncQq(id, client.getSelfId());
        }
        m.put("qrcode", qrFile(id) != null || backend.qrcodeCaptureCmd() != null);
        m.put("scanHint", backend.scanHint(rs.getInt("ws_port"), rs.getInt("ui_port")));
        return m;
    }

    /** 登录后回填真实 QQ 号 */
    private void syncQq(long id, long qq) {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE bot_accounts SET qq_id=? WHERE id=? AND (qq_id IS NULL OR qq_id<>?)")) {
            ps.setLong(1, qq);
            ps.setLong(2, id);
            ps.setLong(3, qq);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 回填失败不影响使用
        }
        adoptKeptData(id, qq);
    }

    /** 如果这个 QQ 之前删账号时保留了数据，就把它接回来 */
    private void adoptKeptData(long id, long qq) {
        Path target = Path.of("/app/data/bots", id + ".db");
        try (Connection c = globalDb.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT file FROM kept_data WHERE qq_id=?")) {
            ps.setLong(1, qq);
            String file;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                file = rs.getString(1);
            }
            Path kept = Path.of(file);
            if (!Files.exists(kept)) {
                return;
            }
            if (Files.exists(target) && hasMessages(target)) {
                log.info("已有数据，跳过接回 qq={}", qq);
                return;
            }
            Files.deleteIfExists(target);
            Files.deleteIfExists(Path.of(target + "-wal"));
            Files.deleteIfExists(Path.of(target + "-shm"));
            Files.move(kept, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
            try (PreparedStatement del = c.prepareStatement("DELETE FROM kept_data WHERE qq_id=?")) {
                del.setLong(1, qq);
                del.executeUpdate();
            }
            log.info("已接回保留的数据 qq={} -> bots/{}.db", qq, id);
        } catch (Exception e) {
            log.warn("接回保留数据失败 qq={}: {}", qq, e.getMessage());
        }
    }

    private boolean hasMessages(Path db) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM messages");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void moveIfExists(String from, String to) {
        try {
            Path f = Path.of("/app/data/bots", from);
            if (Files.exists(f)) {
                Files.move(f, Path.of("/app/data/bots", to),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // 辅助文件移动失败不影响主流程
        }
    }

    // ---------- 新增账号 ----------

    public Map<String, Object> create(String name) throws Exception {
        return create(name, null, null, false);
    }

    /** 兼容旧调用：不指定协议端时用默认（NapCat） */
    public Map<String, Object> create(String name, Long migrateFrom, boolean copyOnly) throws Exception {
        return create(name, null, migrateFrom, copyOnly);
    }

    /**
     * 新建账号。
     *
     * @param migrateFrom 要复用数据的旧账号 id；传 null 表示全新账号
     * @param copyOnly    true = 复制一份（旧账号保留）；false = 迁移（旧账号删除）
     */
    public Map<String, Object> create(String name, String backendKey, Long migrateFrom,
                                      boolean copyOnly) throws Exception {
        ProtocolBackend backend = backends.get(backendKey);
        long id;
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bot_accounts(name, backend, container, ws_url, ws_port, ui_port, created_at, enabled)"
                             + " VALUES(?,?,?,?,?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name == null || name.isBlank() ? "新账号" : name);
            ps.setString(2, backend.key());
            ps.setString(3, "pending");
            ps.setString(4, "pending");
            ps.setInt(5, 0);
            ps.setInt(6, 0);
            ps.setString(7, LocalDateTime.now().format(TS));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                id = rs.next() ? rs.getLong(1) : -1;
            }
        }
        if (id <= 0) {
            throw new IllegalStateException("创建账号记录失败");
        }

        int wsPort = backend.wsPortBase() + (int) id;
        int uiPort = backend.uiPortBase() + (int) id;
        String container = backend.containerPrefix() + id;
        String wsUrl = "ws://" + container + ":3001";
        try {
            Path base = Path.of("/app/napcat-accounts", String.valueOf(id));
            Files.createDirectories(base);
            backend.prepareDirs(base);
            dockerCreate(id, container, backend, wsPort, uiPort);
            exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                    "http://localhost/containers/" + container + "/start");
            String postStart = backend.postStartCmd();
            if (postStart != null) {
                try {
                    execInContainer(container, postStart);
                } catch (Exception e) {
                    log.warn("实例启动后置命令失败 container={}: {}", container, e.getMessage());
                }
            }
            updateAccount(id, backend, container, wsUrl, wsPort, uiPort);
            registry.start(id, wsUrl);
            log.info("已创建机器人实例 id={} container={} wsPort={}", id, container, wsPort);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("ok", true);
            result.put("id", id);
            result.put("container", container);

            // 可选：复用旧账号的数据。copyOnly=true 只复制，旧账号保留
            if (migrateFrom != null && migrateFrom > 0 && migrateFrom != id) {
                try {
                    migrateData(migrateFrom, id);
                    if (copyOnly) {
                        result.put("copied", true);
                        result.put("copiedFrom", migrateFrom);
                        log.info("账号数据复制完成 from={} to={}（旧账号保留）", migrateFrom, id);
                    } else {
                        delete(migrateFrom);
                        result.put("migrated", true);
                        result.put("migratedFrom", migrateFrom);
                        log.info("账号数据迁移完成 from={} to={}", migrateFrom, id);
                    }
                } catch (Exception e) {
                    result.put(copyOnly ? "copied" : "migrated", false);
                    result.put("migrateMessage", String.valueOf(e.getMessage()));
                    log.warn("账号数据复用失败 from={} to={}: {}", migrateFrom, id, e.getMessage());
                }
            } else {
                result.put("migrated", false);
            }
            return result;
        } catch (Exception e) {
            log.warn("创建实例失败: {}", e.getMessage());
            return Map.of("ok", false, "id", id, "message", String.valueOf(e.getMessage()));
        }
    }

    /** 把旧账号库里的业务数据整份复制到新账号库（表结构取两边交集列） */
    private void migrateData(long fromId, long toId) throws Exception {
        Path from = Path.of("/app/data/bots", fromId + ".db");
        Path to = Path.of("/app/data/bots", toId + ".db");
        if (!Files.exists(from)) {
            throw new IllegalStateException("旧账号的数据库不存在，无法迁移");
        }
        Files.createDirectories(to.getParent());
        String[] tables = {
                "messages", "facts", "summaries", "followups", "usage", "settings",
                "personas", "proactive_state", "proactive_log", "emotional_memory",
                "user_mood", "friend_profile", "qzone_post", "friend_request"
        };
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + to)) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                for (String ddl : com.qqbot.memory.Db.SCHEMA) {
                    s.execute(ddl);
                }
                s.execute("ATTACH DATABASE '" + from.toAbsolutePath().toString().replace("'", "''") + "' AS src");
            }
            for (String table : tables) {
                List<String> cols = commonColumns(c, table);
                if (cols.isEmpty()) {
                    continue;
                }
                String list = String.join(",", cols);
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT OR REPLACE INTO main." + table + "(" + list + ") SELECT " + list + " FROM src." + table);
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute("DETACH DATABASE src");
            }
        }
    }

    private List<String> commonColumns(Connection c, String table) {
        List<String> main = columns(c, "main", table);
        List<String> src = columns(c, "src", table);
        List<String> out = new java.util.ArrayList<>();
        for (String col : main) {
            if (src.contains(col)) {
                out.add(col);
            }
        }
        return out;
    }

    private List<String> columns(Connection c, String schema, String table) {
        List<String> cols = new java.util.ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        } catch (Exception ignored) {
            // 表不存在
        }
        return cols;
    }

    private void updateAccount(long id, ProtocolBackend backend, String container, String wsUrl,
                               int wsPort, int uiPort) throws Exception {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE bot_accounts SET backend=?, container=?, ws_url=?, ws_port=?, ui_port=?"
                             + " WHERE id=?")) {
            ps.setString(1, backend.key());
            ps.setString(2, container);
            ps.setString(3, wsUrl);
            ps.setInt(4, wsPort);
            ps.setInt(5, uiPort);
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    /** 用 Engine API 创建容器：具体镜像、端口、卷、权限由协议端自己决定 */
    private void dockerCreate(long id, String container, ProtocolBackend backend,
                              int wsPort, int uiPort) throws Exception {
        String base = hostDir + "/" + id;
        String body = backend.createBody(base, wsPort, uiPort, network);

        String response = execWithStdin(body, "curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                "-H", "Content-Type: application/json",
                "--data-binary", "@-", "http://localhost/containers/create?name=" + container);
        if (response.contains("\"message\"")) {
            throw new IllegalStateException("创建容器失败: " + response);
        }
    }

    // ---------- 二维码 ----------

    /** 取某个账号使用的协议端；读不到按默认处理 */
    private ProtocolBackend backendOf(long id) {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT backend FROM bot_accounts WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return backends.get(rs.getString(1));
                }
            }
        } catch (Exception ignored) {
            // 按默认
        }
        return backends.defaultBackend();
    }

    public Path qrFile(long id) {
        ProtocolBackend backend = backendOf(id);
        Path p = backend.qrcodePath(Path.of("/app/napcat-accounts", String.valueOf(id)));
        if (p == null) {
            return null;
        }
        if (!Files.exists(p)) {
            String container = "";
            try (java.sql.Connection c = globalDb.open();
                 java.sql.PreparedStatement ps = c.prepareStatement("SELECT container FROM bot_accounts WHERE id=?")) {
                ps.setLong(1, id);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        container = rs.getString(1);
                    }
                }
            } catch (Exception ignored) {
                // 读账号失败按普通路径处理
            }
            if ("napcat".equals(container)) {
                p = Path.of("/app/napcat-cache", "qrcode.png");
            }
        }
        try {
            if (Files.exists(p) && Files.getLastModifiedTime(p).toInstant()
                    .isAfter(java.time.Instant.now().minusSeconds(300))) {
                return p;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
    public byte[] qrcode(long id) throws IOException {
        Path p = qrFile(id);
        if (p != null) {
            return Files.readAllBytes(p);
        }
        ProtocolBackend backend = backendOf(id);
        String cmd = backend.qrcodeCaptureCmd();
        if (cmd == null) {
            return null;
        }
        // 同一账号 5 秒内复用截图，避免前端轮询把 ffmpeg 打爆
        Object[] cached = qrCache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now - (long) cached[1] < 5000) {
            return (byte[]) cached[0];
        }
        byte[] png = captureInContainer(containerOf(id), cmd);
        if (png != null) {
            qrCache.put(id, new Object[]{png, now});
        }
        return png;
    }

    /** 在容器里跑一条命令（不关心输出），用于启动后的配置修补 */
    private void execInContainer(String container, String cmd) throws Exception {
        String body = "{\"User\":\"root\",\"Cmd\":[\"sh\",\"-lc\","
                + mapper.writeValueAsString(cmd) + "],\"AttachStdout\":false,\"AttachStderr\":false}";
        String created = execWithStdin(body, "curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                "-H", "Content-Type: application/json", "--data-binary", "@-",
                "http://localhost/containers/" + container + "/exec");
        String execId = mapper.readTree(created).path("Id").asText("");
        if (execId.isBlank()) {
            throw new IllegalStateException("exec 创建失败: " + created);
        }
        exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                "-H", "Content-Type: application/json", "-d", "{\"Detach\":true}",
                "http://localhost/exec/" + execId + "/start");
    }

    /** 让容器内的 shell 生成 /tmp/qr.png，再把文件取出来 */
    private byte[] captureInContainer(String container, String cmd) {
        try {
            String body = "{\"User\":\"snowluma\",\"Env\":[\"DISPLAY=:1\"],"
                    + "\"Cmd\":[\"sh\",\"-lc\"," + mapper.writeValueAsString(cmd) + "],"
                    + "\"AttachStdout\":false,\"AttachStderr\":false}";
            String created = execWithStdin(body, "curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                    "-H", "Content-Type: application/json", "--data-binary", "@-",
                    "http://localhost/containers/" + container + "/exec");
            String execId = mapper.readTree(created).path("Id").asText("");
            if (execId.isBlank()) {
                log.warn("截图失败，exec 创建无 Id: {}", created);
                return null;
            }
            exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                    "-H", "Content-Type: application/json", "-d", "{\"Detach\":true}",
                    "http://localhost/exec/" + execId + "/start");
            Thread.sleep(2500);
            Path tar = Files.createTempFile("qr-", ".tar");
            exec("curl", "-s", "--unix-socket", SOCKET, "-o", tar.toString(),
                    "http://localhost/containers/" + container + "/archive?path=/tmp/qr.png");
            byte[] png = firstFileFromTar(Files.readAllBytes(tar));
            Files.deleteIfExists(tar);
            return png;
        } catch (Exception e) {
            log.warn("容器截图失败 container={}: {}", container, e.getMessage());
            return null;
        }
    }

    /** 从 Docker archive（tar）里取第一个普通文件的内容 */
    private byte[] firstFileFromTar(byte[] tar) {
        int off = 0;
        while (off + 512 <= tar.length) {
            String name = new String(tar, off, 100, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) {
                return null;
            }
            long size = 0;
            try {
                String octal = new String(tar, off + 124, 12, java.nio.charset.StandardCharsets.UTF_8)
                        .trim().replace("\0", "");
                size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
            } catch (Exception ignored) {
                return null;
            }
            int dataStart = off + 512;
            if (size > 0 && dataStart + (int) size <= tar.length) {
                return java.util.Arrays.copyOfRange(tar, dataStart, dataStart + (int) size);
            }
            off = dataStart + (int) ((size + 511) / 512) * 512;
        }
        return null;
    }

    /** 账号对应的容器名 */
    private String containerOf(long id) {
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT container FROM bot_accounts WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception ignored) {
            // 查不到就空
        }
        return "";
    }

    /** 刷新登录二维码：重启该账号的 NapCat 容器，等待新二维码落盘 */
    /** 当前账号的 WS 客户端（可能为 null） */
    public com.qqbot.bot.OneBotClient client(long id) {
        return registry.get(id);
    }

    public Map<String, Object> refreshQrcode(long id) throws Exception {
        Map<String, Object> row = get(id);
        if (row == null) {
            return Map.of("ok", false, "message", "账号不存在");
        }
        if (Boolean.TRUE.equals(row.get("online"))) {
            return Map.of("ok", true, "online", true, "message", "该账号已在线");
        }
        String container = String.valueOf(row.get("container"));
        // 刷新二维码视为重新启用该账号
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("UPDATE bot_accounts SET enabled=1 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        registry.start(id, String.valueOf(row.get("ws_url")));
        exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                "http://localhost/containers/" + container + "/restart");
        ProtocolBackend backend = backendOf(id);
        if (backend.qrcodePath(Path.of("/app/napcat-accounts", String.valueOf(id))) == null) {
            // SnowLuma 之类不落盘二维码：重启后由用户在远程桌面扫码
            Thread.sleep(2000);
            return Map.of("ok", true, "online", false,
                    "scanHint", backend.scanHint(((Number) row.get("wsPort")).intValue(),
                            ((Number) row.get("uiPort")).intValue()));
        }
        long start = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1500);
            Path p = qrFile(id);
            if (p != null) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() >= start - 5000) {
                        return Map.of("ok", true, "online", false);
                    }
                } catch (Exception ignored) {
                    // 继续等
                }
            }
        }
        return Map.of("ok", false, "message", "二维码生成超时，请稍后再试");
    }
    /** 删除账号：停连接、删容器、删该账号数据库 */
    public Map<String, Object> delete(long id) throws Exception {
        return delete(id, false);
    }

    /**
     * 删除账号。
     *
     * @param keepData true = 保留数据库，等以后用同一个 QQ 登录时自动接回
     */
    public Map<String, Object> delete(long id, boolean keepData) throws Exception {
        Map<String, Object> row = get(id);
        if (row == null) {
            return Map.of("ok", false, "message", "账号不存在");
        }
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bot_accounts");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) <= 1) {
                return Map.of("ok", false, "message", "至少保留一个账号");
            }
        }
        registry.stop(id);
        String container = String.valueOf(row.get("container"));
        try {
            exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                    "http://localhost/containers/" + container + "/stop");
        } catch (Exception ignored) {
            // 容器可能已停止
        }
        exec("curl", "-s", "-X", "DELETE", "--unix-socket", SOCKET,
                "http://localhost/containers/" + container + "?force=1");
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM bot_accounts WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        try {
            if (keepData) {
                long qq = row.get("qqId") instanceof Number n ? n.longValue() : 0L;
                if (qq <= 0) {
                    return Map.of("ok", false, "message", "这个账号还没登录过 QQ，数据无法按 QQ 关联");
                }
                Path kept = Path.of("/app/data/bots", "kept-" + qq + ".db");
                Files.move(Path.of("/app/data/bots", id + ".db"), kept,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moveIfExists(id + ".db-wal", "kept-" + qq + ".db-wal");
                moveIfExists(id + ".db-shm", "kept-" + qq + ".db-shm");
                try (Connection c = globalDb.open();
                     PreparedStatement ps = c.prepareStatement(
                             "INSERT OR REPLACE INTO kept_data(qq_id, file, created_at) VALUES(?,?,?)")) {
                    ps.setLong(1, qq);
                    ps.setString(2, kept.toAbsolutePath().toString());
                    ps.setString(3, LocalDateTime.now().format(TS));
                    ps.executeUpdate();
                }
                log.info("账号已删除，数据保留 id={} qq={}", id, qq);
            } else {
                Files.deleteIfExists(Path.of("/app/data/bots", id + ".db"));
                Files.deleteIfExists(Path.of("/app/data/bots", id + ".db-wal"));
                Files.deleteIfExists(Path.of("/app/data/bots", id + ".db-shm"));
                log.info("已删除账号及数据 id={} container={}", id, container);
            }
        } catch (Exception e) {
            log.warn("删除账号库失败: {}", e.getMessage());
            return Map.of("ok", false, "message", "删除数据失败: " + e.getMessage());
        }
        return Map.of("ok", true);
    }

    /**
     * 切换某个账号的接入方式（NapCat ↔ SnowLuma）。
     * 只重建协议端容器，账号的数据库（ chatbots/<id>.db）原样保留。
     */
    public Map<String, Object> switchBackend(long id, String backendKey) throws Exception {
        Map<String, Object> row = get(id);
        if (row == null) {
            return Map.of("ok", false, "message", "账号不存在");
        }
        ProtocolBackend target = backends.get(backendKey);
        if (String.valueOf(row.get("backend")).equals(target.key())) {
            return Map.of("ok", true, "backend", target.key(), "message", "已经是 " + target.displayName());
        }
        String oldContainer = String.valueOf(row.get("container"));
        registry.stop(id);
        if (!oldContainer.isBlank() && !"pending".equals(oldContainer)) {
            try {
                exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                        "http://localhost/containers/" + oldContainer + "/stop");
            } catch (Exception ignored) {
                // 可能已经停了
            }
            try {
                exec("curl", "-s", "-X", "DELETE", "--unix-socket", SOCKET,
                        "http://localhost/containers/" + oldContainer + "?force=1");
            } catch (Exception e) {
                log.warn("删除旧容器失败 {}: {}", oldContainer, e.getMessage());
            }
        }

        int wsPort = target.wsPortBase() + (int) id;
        int uiPort = target.uiPortBase() + (int) id;
        String container = target.containerPrefix() + id;
        String wsUrl = "ws://" + container + ":3001";
        Path base = Path.of("/app/napcat-accounts", String.valueOf(id));
        Files.createDirectories(base);
        target.prepareDirs(base);
        dockerCreate(id, container, target, wsPort, uiPort);
        exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                "http://localhost/containers/" + container + "/start");
        if (target.postStartCmd() != null) {
            try {
                execInContainer(container, target.postStartCmd());
            } catch (Exception e) {
                log.warn("切换后置命令失败 container={}: {}", container, e.getMessage());
            }
        }
        updateAccount(id, target, container, wsUrl, wsPort, uiPort);
        registry.start(id, wsUrl);
        qrCache.remove(id);
        log.info("账号接入方式已切换 id={} {} -> {}", id, row.get("backend"), target.key());
        return Map.of("ok", true, "backend", target.key(), "backendName", target.displayName(),
                "container", container, "wsUrl", wsUrl, "wsPort", wsPort, "uiPort", uiPort,
                "scanHint", target.scanHint(wsPort, uiPort));
    }

    // ---------- 下线 ----------

    public Map<String, Object> logout(long id) throws Exception {
        Map<String, Object> row = get(id);
        if (row == null) {
            return Map.of("ok", false, "message", "账号不存在");
        }
        registry.stop(id);
        // 真正下线：标记禁用，避免自动重连又把它拉起来
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("UPDATE bot_accounts SET enabled=0 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        String container = String.valueOf(row.get("container"));
        try {
            exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                    "http://localhost/containers/" + container + "/stop");
        } catch (Exception e) {
            log.warn("停止容器失败 {}: {}", container, e.getMessage());
        }
        return Map.of("ok", true);
    }

    private String execWithStdin(String input, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var os = p.getOutputStream()) {
            os.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker 调用超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("docker 调用失败: " + out.trim());
        }
        return out;
    }

    /**
     * 新实例登录后 NapCat 会写 onebot11_<qq>.json，但默认没开 WS。
     * 这里轮询发现后补上 WS 配置、重启一次、登记 QQ 号并建立连接。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void configureNewAccounts() {
        try {
            for (Map<String, Object> a : list()) {
                long id = ((Number) a.get("id")).longValue();
                long qq = ((Number) a.get("qqId")).longValue();
                if (qq > 0) {
                    continue;
                }
                Path dir = Path.of("/app/napcat-accounts", String.valueOf(id), "config");
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var files = Files.list(dir)) {
                    Path cfg = files.filter(f -> f.getFileName().toString().startsWith("onebot11_")
                                    && f.getFileName().toString().endsWith(".json"))
                            .findFirst().orElse(null);
                    if (cfg == null) {
                        // 还没登录成功（NapCat 登录后才写 onebot11 配置），继续等
                        continue;
                    }
                    String name = cfg.getFileName().toString();
                    // onebot11_<qq>.json：只取前缀之后的部分，避免把 "onebot11" 里的数字算进去
                    String digits = name.replaceFirst("^onebot11_", "").replaceFirst("\\.json$", "");
                    long realQq = Long.parseLong(digits.replaceAll("\\D", ""));
                    patchWebsocket(cfg);
                    try (Connection c = globalDb.open();
                         PreparedStatement ps = c.prepareStatement("UPDATE bot_accounts SET qq_id=? WHERE id=?")) {
                        ps.setLong(1, realQq);
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                    exec("curl", "-s", "-X", "POST", "--unix-socket", SOCKET,
                            "http://localhost/containers/napcat-" + id + "/restart");
                    registry.start(id, "ws://napcat-" + id + ":3001");
                    log.info("新账号已配置 WS 并重启: id={} qq={}", id, realQq);
                }
            }
        } catch (Exception e) {
            log.warn("检查新账号配置失败: {}", e.getMessage());
        }
    }

    private void patchWebsocket(Path cfg) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(cfg.toFile());
        var networkNode = root.has("network") ? root.get("network") : root.putObject("network");
        var wsArray = mapper.createArrayNode();
        var server = wsArray.addObject();
        server.put("name", "qqbot-ws");
        server.put("enable", true);
        server.put("host", "0.0.0.0");
        server.put("port", 3001);
        server.put("messagePostFormat", "array");
        server.put("reportSelfMessage", false);
        server.put("token", "qqbot-local-token");
        server.put("enableForcePushEvent", true);
        server.put("debug", false);
        server.put("heartInterval", 30000);
        ((com.fasterxml.jackson.databind.node.ObjectNode) networkNode).set("websocketServers", wsArray);
        mapper.writerWithDefaultPrettyPrinter().writeValue(cfg.toFile(), root);
    }

    private String exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker 调用超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("docker 调用失败: " + out.trim());
        }
        return out;
    }
}
