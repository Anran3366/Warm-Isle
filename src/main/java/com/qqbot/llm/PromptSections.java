package com.qqbot.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态 prompt 选择：把人设按【小节】、固定规则按空行分块，
 * 只把当前这条消息真正用得上的段落拼进去。
 *
 * <p>设计原则：常驻段只保留「不能缺」的（说话方式、时间感、性格、绝对不要），
 * 其余按场景触发；判断全部是本地规则，不额外调用模型。
 */
public final class PromptSections {

    private PromptSections() {
    }

    /** 当前这条消息的场景特征 */
    public record Ctx(String userText, double mood, int userMsgCount,
                      boolean hasMemory, boolean mediaPlanned, boolean imageInput) {
    }

    // ---------------- 规则分割 ----------------

    /** 把整块规则按空行拆成若干段，key 取标题（去掉「（重要）」） */
    public static Map<String, String> ruleBlocks(String rules) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rules == null) {
            return out;
        }
        for (String block : rules.split("\n\\s*\n")) {
            String t = block.trim();
            if (t.isEmpty()) {
                continue;
            }
            String title = t.split("\n", 2)[0].replace("（重要）：", "").replace("：", "").trim();
            out.put(title, t);
        }
        return out;
    }

    /** 按场景挑规则段；顺序固定，保证前缀稳定 */
    public static String rulesFor(String rules, Ctx ctx) {
        Map<String, String> blocks = ruleBlocks(rules);
        if (blocks.isEmpty()) {
            return rules == null ? "" : rules;
        }
        List<String> picked = new ArrayList<>();
        add(picked, blocks, "说话方式");
        add(picked, blocks, "时间感");
        if (emoTional(ctx)) {
            add(picked, blocks, "接住情绪");
        }
        if (needsPush(ctx)) {
            add(picked, blocks, "往前聊");
        }
        if (mediaTopic(ctx)) {
            add(picked, blocks, "表情、语音、图片");
        }
        if (ctx.hasMemory()) {
            add(picked, blocks, "记性与分寸");
        }
        if (picked.isEmpty()) {
            // 兜底：至少带上说话方式
            return rules == null ? "" : rules;
        }
        return String.join("\n\n", picked);
    }

    private static void add(List<String> picked, Map<String, String> blocks, String key) {
        String v = blocks.get(key);
        if (v != null && !v.isBlank()) {
            picked.add(v);
        }
    }

    // ---------------- 人设切片 ----------------

    /** 人设小节：标题（含【】）+ 内容 */
    public record Section(String title, String body) {
    }

    public static List<Section> personaSections(String persona) {
        List<Section> out = new ArrayList<>();
        if (persona == null || persona.isBlank()) {
            return out;
        }
        String[] parts = persona.split("(?=【)");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            String title;
            if (t.startsWith("【")) {
                int end = t.indexOf('】');
                title = end > 0 ? t.substring(1, end) : "";
            } else {
                title = "";
            }
            out.add(new Section(title, t));
        }
        return out;
    }

    /** 按场景挑人设小节；解析不出小节就整段返回 */
    public static String personaFor(String persona, Ctx ctx) {
        List<Section> sections = personaSections(persona);
        if (sections.size() <= 1) {
            return persona == null ? "" : persona;
        }
        List<String> picked = new ArrayList<>();
        for (Section s : sections) {
            if (keepPersona(s.title(), ctx)) {
                picked.add(s.body());
            }
        }
        if (picked.isEmpty()) {
            return persona == null ? "" : persona;
        }
        return String.join("\n\n", picked);
    }

    private static boolean keepPersona(String title, Ctx ctx) {
        if (title.isEmpty()) {
            return true; // 开头那段（自我介绍）保留
        }
        return switch (title) {
            case "性格", "绝对不要", "口头禅参考" -> true;
            case "关于你自己" -> identityQuestion(ctx);
            case "先观察，再相处" -> ctx.userMsgCount() < 20;
            case "哄人的顺序" -> emoTional(ctx);
            case "聊天风格" -> needsPush(ctx);
            case "边界" -> intimacy(ctx);
            // 人设里的「说话方式」和固定规则重复，动态模式下省掉
            case "说话方式" -> false;
            default -> false;
        };
    }

    // ---------------- 触发器 ----------------

    private static final String[] EMOTION_WORDS = {
            "烦", "难受", "委屈", "哭", "累", "生气", "气死", "难过", "不开心", "崩", "烦死",
            "讨厌", "滚", "闭嘴", "敷衍", "冷淡", "不想活", "焦虑", "压力", "失眠", "睡不着"
    };
    private static final String[] PUSH_WORDS = {"换个话题", "你主动点", "没意思", "无聊", "不知道聊什么", "你说呢"};
    private static final String[] MEDIA_WORDS = {"表情", "语音", "图片", "照片", "发个", "发张", "来张"};
    private static final String[] IDENTITY_WORDS = {"你是谁", "你叫什么", "你多大", "你几岁", "干什么的", "做什么工作", "在哪", "是不是ai", "机器人", "你是人吗"};
    private static final String[] INTIMACY_WORDS = {"女朋友", "男朋友", "表白", "喜欢你", "爱你", "亲", "抱", "老公", "老婆", "恋爱", "在一起"};

    static boolean emoTional(Ctx ctx) {
        if (ctx.mood() <= -0.3) {
            return true;
        }
        return containsAny(ctx.userText(), EMOTION_WORDS);
    }

    static boolean needsPush(Ctx ctx) {
        if (ctx.userText() != null && ctx.userText().trim().length() <= 4) {
            return true;
        }
        return containsAny(ctx.userText(), PUSH_WORDS);
    }

    static boolean mediaTopic(Ctx ctx) {
        return ctx.mediaPlanned() || ctx.imageInput()
                || containsAny(ctx.userText(), MEDIA_WORDS);
    }

    static boolean identityQuestion(Ctx ctx) {
        return containsAny(ctx.userText(), IDENTITY_WORDS);
    }

    static boolean intimacy(Ctx ctx) {
        return containsAny(ctx.userText(), INTIMACY_WORDS);
    }

    private static boolean containsAny(String text, String[] words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        for (String w : words) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
