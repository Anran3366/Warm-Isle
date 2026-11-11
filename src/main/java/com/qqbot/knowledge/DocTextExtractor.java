package com.qqbot.knowledge;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 从 md / txt / docx 提取纯文本 */
public final class DocTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocTextExtractor.class);

    private DocTextExtractor() {
    }

    public static String extract(String filename, byte[] bytes) throws Exception {
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                 XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                return ex.getText();
            }
        }
        if (name.endsWith(".doc")) {
            throw new IllegalArgumentException("暂不支持旧版 .doc，请另存为 .docx 后上传");
        }
        if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".markdown")) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.contains("\uFFFD")) {
                // UTF-8 解出乱码时按 GBK 再试一次（Windows 记事本常见）
                try {
                    text = new String(bytes, "GBK");
                } catch (Exception e) {
                    log.warn("GBK 解码失败，保留 UTF-8 结果");
                }
            }
            return text;
        }
        throw new IllegalArgumentException("仅支持 .md / .txt / .docx");
    }
}
