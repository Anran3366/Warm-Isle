package com.qqbot.plugin.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 商店响应的 Ed25519 验签器（设计文档 §6.8）。
 *
 * <p>公钥内置在宿主配置里（{@code qqbot.plugin.store.public-key}，SPKI DER 的 base64），
 * 商店用私钥对 index.json / revocations.json 的<b>响应体原文</b>签名，
 * 放在 {@code X-Store-Signature} 响应头。验签失败的响应一律不采用——
 * 商店被入侵时，攻击者没有私钥就投不了毒。
 */
public class StoreSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(StoreSignatureVerifier.class);

    private final PublicKey publicKey; // null = 单密钥模式未配置
    private final StoreKeyRing ring;   // 非 null = 支持轮换的公钥环
    private final boolean required;

    public StoreSignatureVerifier(StoreKeyRing ring, boolean required) {
        this.ring = ring;
        this.publicKey = null;
        this.required = required;
    }

    public StoreSignatureVerifier(String publicKeyB64, boolean required) {
        this.ring = null;
        this.required = required;
        PublicKey key = null;
        if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
            try {
                byte[] der = Base64.getDecoder().decode(publicKeyB64.trim());
                key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception e) {
                log.error("商店公钥配置无效，验签不可用: {}", e.getMessage());
            }
        }
        this.publicKey = key;
    }

    /** 验签能力是否生效（公钥配置成功且 required）。吊销阻断以此为开关（§6.8） */
    public boolean active() {
        return configured() && required;
    }

    /** 公钥是否可用（与 required 无关，便于后台区分「没配」和「关了」） */
    public boolean configured() {
        return ring != null ? ring.hasKeys() : publicKey != null;
    }

    public boolean required() {
        return required;
    }

    /**
     * 校验响应体签名。
     *
     * @param body      响应体原文字节
     * @param signature {@code X-Store-Signature} 头的值，可能为 null
     * @return true = 可采用；false = 必须丢弃
     */
    public boolean verify(byte[] body, String signature) {
        return verify(body, signature, null);
    }

    /**
     * @param keyId 商店响应头 {@code X-Store-Key-Id}；为空时尝试密钥环里所有钥匙
     */
    public boolean verify(byte[] body, String signature, String keyId) {
        if (ring != null) {
            return ring.verify(body, signature, keyId);
        }
        if (publicKey == null) {
            // 没配公钥：自研商店联调场景。required 时拒绝，否则放行并告警一次由调用方记
            return !required;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(body);
            return sig.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (Exception e) {
            return false;
        }
    }
}
