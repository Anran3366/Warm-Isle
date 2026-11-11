package com.qqbot.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.PluginProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商店公钥环 + 自动轮换（对应商店的 {@code /api/v1/keys/rotation}）。
 *
 * <p>初始信任锚是代码里内置的官方公钥。商店换签名密钥后，会提供一条由<b>旧私钥</b>
 * 签名的新公钥记录；实例验签通过后自动采纳并落盘，不需要发版，也不需要人工改配置。
 * 链可以连续多条，离线较久的实例会从自己信任的那把钥匙开始往后走。
 *
 * <p>落盘位置：插件目录的上一级（容器里就是 {@code /app/data/plugin-store-keys.json}），
 * 随数据卷持久化。
 */
public class StoreKeyRing {

    private static final Logger log = LoggerFactory.getLogger(StoreKeyRing.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 官方初始密钥：代码内置，永不从网络拉取 */
    static final String OFFICIAL_KEY_ID = "eacda113a48371ab";
    static final String OFFICIAL_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAeB9rLu3JU7Izrd+8ZmRe5rtM0FuUU1pjZRRyJj21/a4=";

    private final Path file;
    private final Map<String, PublicKey> keys = new LinkedHashMap<>();
    private volatile String currentKeyId = OFFICIAL_KEY_ID;

    public StoreKeyRing(PluginProperties props) {
        this(props, OFFICIAL_KEY_ID, OFFICIAL_PUBLIC_KEY);
    }

    /** 测试/自定义初始锚：只信任给定公钥，再叠加已落盘的钥匙 */
    public StoreKeyRing(PluginProperties props, String initialKeyId, String initialPublicKey) {
        Path dir = Path.of(props.getDir()).toAbsolutePath().getParent();
        this.file = (dir == null ? Path.of(".") : dir).resolve("plugin-store-keys.json");
        PublicKey key = parse(initialPublicKey);
        if (key != null && initialKeyId != null && !initialKeyId.isBlank()) {
            keys.put(initialKeyId, key);
            currentKeyId = initialKeyId;
        }
        load();
    }

    /** 测试/单密钥兼容构造 */
    public StoreKeyRing(String keyId, String publicKeyB64) {
        this.file = null;
        if (keyId != null && publicKeyB64 != null) {
            PublicKey key = parse(publicKeyB64);
            if (key != null) {
                keys.put(keyId, key);
                currentKeyId = keyId;
            }
        }
    }

    public synchronized boolean hasKeys() {
        return !keys.isEmpty();
    }

    public synchronized String currentKeyId() {
        return currentKeyId;
    }

    public synchronized boolean verify(byte[] body, String signatureB64, String keyId) {
        if (body == null || signatureB64 == null || signatureB64.isBlank()) {
            return false;
        }
        try {
            byte[] sig = Base64.getDecoder().decode(signatureB64.trim());
            if (keyId != null && !keyId.isBlank()) {
                PublicKey key = keys.get(keyId);
                return key != null && verifyWith(key, body, sig);
            }
            for (PublicKey key : keys.values()) {
                if (verifyWith(key, body, sig)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("商店签名解析失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 采纳轮换链：逐条用 {@code from} 公钥验签，成功才加入 {@code to}。
     * @return 是否有新钥匙被采纳
     */
    public synchronized boolean adopt(JsonNode chain, String serverCurrentKeyId) {
        if (chain == null || !chain.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode st : chain) {
            String from = st.path("from").asText("");
            String to = st.path("to").asText("");
            String newKey = st.path("newPublicKey").asText("");
            String effectiveAt = st.path("effectiveAt").asText("");
            String signature = st.path("signature").asText("");
            if (from.isBlank() || to.isBlank() || newKey.isBlank() || signature.isBlank()) {
                continue;
            }
            if (keys.containsKey(to)) {
                continue;
            }
            PublicKey oldKey = keys.get(from);
            if (oldKey == null) {
                // 这条链比本机信任锚更早：跳过，后面可能接得上
                continue;
            }
            PublicKey parsed = parse(newKey);
            if (parsed == null) {
                log.warn("轮换链里的新公钥格式非法，已忽略 keyId={}", to);
                continue;
            }
            if (!verifyWith(oldKey, canonical(from, to, newKey, effectiveAt), decode(signature))) {
                log.warn("轮换链验签失败，已忽略：{} -> {}", from, to);
                continue;
            }
            keys.put(to, parsed);
            currentKeyId = to;
            changed = true;
            log.info("已采纳商店新签名密钥: {} -> {}（生效 {}）", from, to, effectiveAt);
        }
        if (serverCurrentKeyId != null && !serverCurrentKeyId.isBlank() && keys.containsKey(serverCurrentKeyId)) {
            currentKeyId = serverCurrentKeyId;
        }
        if (changed) {
            persist();
        }
        return changed;
    }

    /** 与 Node 端 {@code JSON.stringify({from,to,newPublicKey,effectiveAt})} 保持完全一致的字节序 */
    private static byte[] canonical(String from, String to, String newKey, String effectiveAt) {
        return ("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"newPublicKey\":\""
                + newKey + "\",\"effectiveAt\":\"" + effectiveAt + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static boolean verifyWith(PublicKey key, byte[] body, byte[] sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(body);
            return s.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static PublicKey parse(String b64) {
        try {
            byte[] der = Base64.getDecoder().decode(b64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decode(String b64) {
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            for (JsonNode k : root.path("keys")) {
                String id = k.path("id").asText("");
                PublicKey key = parse(k.path("publicKey").asText(""));
                if (!id.isBlank() && key != null) {
                    keys.put(id, key);
                }
            }
            String cur = root.path("currentKeyId").asText("");
            if (!cur.isBlank() && keys.containsKey(cur)) {
                currentKeyId = cur;
            }
        } catch (Exception e) {
            log.warn("读取商店密钥环失败（忽略，使用内置公钥）: {}", e.getMessage());
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("currentKeyId", currentKeyId);
            var list = new java.util.ArrayList<Map<String, String>>();
            keys.forEach((id, key) -> {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("publicKey", Base64.getEncoder().encodeToString(key.getEncoded()));
                list.add(item);
            });
            root.put("keys", list);
            Files.writeString(file, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存商店密钥环失败: {}", e.getMessage());
        }
    }
}
