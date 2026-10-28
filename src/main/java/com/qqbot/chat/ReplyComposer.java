package com.qqbot.chat;

import com.qqbot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 把一条回复拆成「像人发微信」的几条短消息，并带间隔。
 * 拆分规则：按标点切分，再按「这条回复该发几条」的目标数量合并/拆分，最多 5 条。
 */
@Service
public class ReplyComposer {

    private static final Logger log = LoggerFactory.getLogger(ReplyComposer.class);

    private final BotProperties props;

    public ReplyComposer(BotProperties props) {
        this.props = props;
    }

    /** 拆句；返回至少一条 */
    public List<String> split(String reply) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return out;
        }
        int maxChars = Math.max(20, props.getReply().getMaxChars());
        String text = reply.replace("\r", "").trim();

        // 先按换行切（模型自己分段的情况）
        for (String line : text.split("\n+")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 再按句末标点切
            String[] parts = t.split("(?<=[。！？!?~～…])");
            StringBuilder buf = new StringBuilder();
            for (String p : parts) {
                String seg = p.trim();
                if (seg.isEmpty()) {
                    continue;
                }
                if (buf.length() + seg.length() > maxChars && buf.length() > 0) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
                buf.append(seg);
                if (buf.length() >= maxChars) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
            }
            if (buf.length() > 0) {
                out.add(buf.toString().trim());
            }
        }

        // 合并过短的碎片（< 6 字）到前一条，避免“嗯。”“啊。”连发
        List<String> merged = new ArrayList<>();
        for (String s : out) {
            if (!merged.isEmpty() && s.length() <= 6) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + s);
            } else {
                merged.add(s);
            }
        }
        if (merged.isEmpty()) {
            merged.add(text);
        }

        // 再按逗号拆一次：长句里的停顿，真人会分成两条发
        List<String> commaSplit = new ArrayList<>();
        for (String m : merged) {
            if (m.length() >= 16 && (m.contains("，") || m.contains(","))) {
                int idx = m.indexOf('，') > 0 ? m.indexOf('，') : m.indexOf(',');
                String left = m.substring(0, idx).trim();
                String right = m.substring(idx + 1).trim();
                if (left.length() >= 5 && right.length() >= 4) {
                    commaSplit.add(left);
                    commaSplit.add(right);
                    continue;
                }
            }
            commaSplit.add(m);
        }
        if (commaSplit.size() > 1) {
            merged = commaSplit;
        }
        return fitCount(merged, targetCount(text));
    }

    /** 这条回复应该拆成几条：短的 1 条，长的可以到 5 条，中间带随机 */
    private int targetCount(String text) {
        int len = text.length();
        int maxByLen;
        if (len < 12) {
            maxByLen = 1;
        } else if (len < 30) {
            maxByLen = 2;
        } else if (len < 60) {
            maxByLen = 4;
        } else {
            maxByLen = 5;
        }
        int r = ThreadLocalRandom.current().nextInt(maxByLen) + 1;
        // 三分之一概率多一条，让节奏不那么规律
        if (r < maxByLen && ThreadLocalRandom.current().nextDouble() < 0.33) {
            r++;
        }
        return Math.max(1, Math.min(5, r));
    }

    /** 把分好的片段调到目标条数：多了就合并最短的相邻两条，少了就拆最长的一条 */
    private List<String> fitCount(List<String> parts, int target) {
        List<String> list = new ArrayList<>(parts);
        int want = Math.max(1, Math.min(5, target));

        while (list.size() > want && list.size() > 1) {
            int best = 0;
            int bestLen = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < list.size(); i++) {
                int n = list.get(i).length() + list.get(i + 1).length();
                if (n < bestLen) {
                    bestLen = n;
                    best = i;
                }
            }
            list.set(best, list.get(best) + "，" + list.get(best + 1));
            list.remove(best + 1);
        }

        while (list.size() < want) {
            int idx = -1;
            int idxLen = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).length() > idxLen) {
                    idxLen = list.get(i).length();
                    idx = i;
                }
            }
            if (idx < 0 || idxLen < 8) {
                break;
            }
            String s = list.get(idx);
            int cut = -1;
            for (String mark : new String[]{"，", ",", "；", ";"}) {
                int p = s.indexOf(mark, Math.max(4, s.length() / 3));
                if (p > 0) {
                    cut = p;
                    break;
                }
            }
            if (cut < 0) {
                break;
            }
            list.set(idx, s.substring(0, cut).trim());
            list.add(idx + 1, s.substring(cut + 1).trim());
        }
        list.removeIf(String::isBlank);
        return list;
    }

    /** 每条之间的等待时间（模拟打字） */
    public long delayFor(String part) {
        long base = Math.max(0, props.getReply().getTypingMs());
        long perChar = 35L;
        long d = base + part.length() * perChar;
        return Math.min(4000L, d);
    }

    public long betweenDelay() {
        return Math.max(0, props.getReply().getSplitDelayMs());
    }

    /** 自适应长度提示：对方说得多可以多回，说得少就短 */
    public String adaptiveHint(String userText) {
        if (!props.getReply().isAdaptive() || userText == null) {
            return "";
        }
        int n = userText.length();
        if (n <= 6) {
            return "对方这句话很短，你最多回一句，越短越好。";
        }
        if (n <= 30) {
            return "正常聊，一两句就行。";
        }
        return "对方说了挺多，你可以分 2-4 句回，最多 5 句，但每句要短。";
    }

    public void sleep(long ms) {
        try {
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
