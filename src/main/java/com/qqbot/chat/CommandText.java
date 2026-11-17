package com.qqbot.chat;

/**
 * 判断一条消息是不是「指令」。
 *
 * <p>连发合并会把同一用户 1.8 秒内的多条文字拼成一段，再走命令解析；
 * 昵称/头像这类命令的正则锚在行尾，拼上第二行后就匹配不到了，指令会被静默吞掉。
 * 所以指令类消息必须绕过合并、立即处理。</p>
 */
public final class CommandText {

    private CommandText() {
    }

    public static boolean isCommand(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.startsWith("/")) {
            return true;
        }
        boolean rename = (t.contains("昵称") || t.contains("名字"))
                && (t.contains("改") || t.contains("换") || t.contains("设"));
        boolean avatar = t.contains("头像")
                && (t.contains("换") || t.contains("改") || t.contains("设")
                || t.contains("用这张") || t.contains("这张图"));
        return rename || avatar
                || t.contains("删除我的数据") || t.contains("删除我的信息")
                || t.contains("确认删除")
                || t.contains("别问了") || t.contains("别打扰") || t.contains("别烦我");
    }
}
