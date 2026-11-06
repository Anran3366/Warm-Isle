package com.qqbot.chat;

import com.qqbot.llm.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用模型判断对方这句话的情绪倾向（-1 到 1），异步执行，不阻塞回复。
 */
@Service
public class MoodJudge {

    private static final Logger log = LoggerFactory.getLogger(MoodJudge.class);

    private final DeepSeekClient llm;
    private final MoodService moods;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mood-judge");
        t.setDaemon(true);
        return t;
    });

    public MoodJudge(DeepSeekClient llm, MoodService moods) {
        this.llm = llm;
        this.moods = moods;
    }

    public void judge(long qqId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        worker.submit(() -> {
            try {
                var r = llm.chat(List.of(
                        Map.of("role", "system", "content", "你只输出一个 -1 到 1 的数字，不要解释。"),
                        Map.of("role", "user", "content",
                                "判断下面这句话的情绪倾向：开心/积极为正，难过/生气/烦躁为负，普通为 0。"
                                        + "只输出数字。\n" + text)
                ), 0.1, 10);
                String out = r.content() == null ? "" : r.content().trim();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("-?\\d+(\\.\\d+)?").matcher(out);
                if (m.find()) {
                    moods.applyModel(qqId, Double.parseDouble(m.group()));
                }
            } catch (Exception e) {
                log.debug("情绪判断失败: {}", e.getMessage());
            }
        });
    }
}
