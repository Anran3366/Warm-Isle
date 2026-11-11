package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.store.StoreKeyRing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商店签名密钥轮换：新公钥必须由旧私钥签名，采纳后可验新密钥签的响应。 */
class StoreKeyRingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeyPair kp() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static String sign(KeyPair kp, byte[] body) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(body);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private static ObjectNode statement(KeyPair oldKp, String from, String to, KeyPair newKp,
                                        String at, boolean tamper) throws Exception {
        ObjectNode st = MAPPER.createObjectNode();
        st.put("from", from);
        st.put("to", to);
        st.put("newPublicKey", pub(newKp));
        st.put("effectiveAt", at);
        String canonical = MAPPER.writeValueAsString(st);
        st.put("signature", sign(oldKp, canonical.getBytes(StandardCharsets.UTF_8)));
        if (tamper) {
            // 签名后再改内容：应当验不过
            st.put("newPublicKey", pub(kp()));
        }
        return st;
    }

    @Test
    void adoptsNewKeyOnlyWithValidOldSignature(@TempDir Path tmp) throws Exception {
        PluginProperties props = new PluginProperties();
        props.setDir(tmp.resolve("plugins").toString());

        KeyPair oldKp = kp();
        KeyPair newKp = kp();
        String oldId = "old-key";
        String newId = "new-key";
        StoreKeyRing ring = new StoreKeyRing(props, oldId, pub(oldKp));

        byte[] body = "{\"plugins\":[]}".getBytes(StandardCharsets.UTF_8);
        String newSig = sign(newKp, body);
        assertFalse(ring.verify(body, newSig, newId), "还没轮换时不应认识新钥匙");

        ArrayNode chain = MAPPER.createArrayNode();
        chain.add(statement(oldKp, oldId, newId, newKp, "2026-11-10T00:00:00Z", false));
        assertTrue(ring.adopt(chain, newId));
        assertTrue(ring.verify(body, newSig, newId));

        // 落盘后新实例仍信任
        StoreKeyRing reloaded = new StoreKeyRing(props, oldId, pub(oldKp));
        assertTrue(reloaded.verify(body, newSig, newId));
    }

    @Test
    void rejectsTamperedChain(@TempDir Path tmp) throws Exception {
        PluginProperties props = new PluginProperties();
        props.setDir(tmp.resolve("plugins").toString());
        KeyPair oldKp = kp();
        KeyPair newKp = kp();
        StoreKeyRing ring = new StoreKeyRing(props, "old-key", pub(oldKp));

        ArrayNode chain = MAPPER.createArrayNode();
        chain.add(statement(oldKp, "old-key", "new-key", newKp, "2026-11-10T00:00:00Z", true));
        assertFalse(ring.adopt(chain, "new-key"));
        assertTrue(!ring.verify("x".getBytes(StandardCharsets.UTF_8), "bad", "new-key"));
    }
}
