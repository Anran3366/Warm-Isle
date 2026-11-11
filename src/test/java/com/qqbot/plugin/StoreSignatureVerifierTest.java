package com.qqbot.plugin;

import com.qqbot.plugin.store.StoreSignatureVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商店响应验签（§6.8）：签名对才采用；缺签名、错签名、篡改内容一律拒绝。
 */
class StoreSignatureVerifierTest {

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(KeyPair kp, byte[] body) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(body);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static StoreSignatureVerifier verifierFor(KeyPair kp, boolean required) {
        String b64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()); // X509 = SPKI
        return new StoreSignatureVerifier(b64, required);
    }

    @Test
    void 签名正确则通过() throws Exception {
        KeyPair kp = keyPair();
        byte[] body = "{\"plugins\":[]}".getBytes(StandardCharsets.UTF_8);
        assertTrue(verifierFor(kp, true).verify(body, sign(kp, body)));
    }

    @Test
    void 内容被篡改则拒绝() throws Exception {
        KeyPair kp = keyPair();
        byte[] body = "{\"plugins\":[]}".getBytes(StandardCharsets.UTF_8);
        String sig = sign(kp, body);
        assertFalse(verifierFor(kp, true).verify("{\"plugins\":[{\"evil\":true}]}".getBytes(StandardCharsets.UTF_8), sig));
    }

    @Test
    void 缺签名或签名乱写都拒绝() throws Exception {
        KeyPair kp = keyPair();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        StoreSignatureVerifier v = verifierFor(kp, true);
        assertFalse(v.verify(body, null));
        assertFalse(v.verify(body, ""));
        assertFalse(v.verify(body, "not-base64!!!"));
        assertFalse(v.verify(body, Base64.getEncoder().encodeToString(new byte[64])));
    }

    @Test
    void 别的钥匙签的不认() throws Exception {
        KeyPair good = keyPair();
        KeyPair evil = keyPair();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(verifierFor(good, true).verify(body, sign(evil, body)));
    }

    @Test
    void 未配置公钥时_强制模式拒绝_非强制放行() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(new StoreSignatureVerifier("", true).verify(body, null));
        assertTrue(new StoreSignatureVerifier("", false).verify(body, null));
        assertFalse(new StoreSignatureVerifier("", true).active());
    }

    @Test
    void 公钥配置损坏时按未生效处理() {
        StoreSignatureVerifier v = new StoreSignatureVerifier("!!!bad-base64@@@", true);
        assertFalse(v.active());
        assertFalse(v.verify("{}".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void 生效状态取决于公钥与开关() throws Exception {
        assertTrue(verifierFor(keyPair(), true).active());
        assertFalse(verifierFor(keyPair(), false).active());
    }
}
