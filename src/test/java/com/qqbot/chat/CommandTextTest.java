package com.qqbot.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指令不能被连发合并吞掉，普通聊天不能误判。 */
class CommandTextTest {

    @Test
    void detectsOwnerCommands() {
        assertTrue(CommandText.isCommand("把昵称改成小满"));
        assertTrue(CommandText.isCommand("改名字叫小满"));
        assertTrue(CommandText.isCommand("换个头像"));
        assertTrue(CommandText.isCommand("用这张图当头像"));
        assertTrue(CommandText.isCommand("删除我的数据"));
        assertTrue(CommandText.isCommand("确认删除我的数据"));
        assertTrue(CommandText.isCommand("别问了"));
        assertTrue(CommandText.isCommand("/help"));
    }

    @Test
    void leavesNormalChatAlone() {
        assertFalse(CommandText.isCommand("今天好累啊"));
        assertFalse(CommandText.isCommand("你叫什么名字"));
        assertFalse(CommandText.isCommand("晚安"));
        assertFalse(CommandText.isCommand(""));
    }
}
