package com.qqbot.proactive;

/** 主动消息的关系状态（替代原来只有 suspended 的开关） */
public enum ProactiveMode {
    /** 正常，不打扰，记录活跃规律 */
    CALM,
    /** 想找她，已排好下一次 */
    MISS,
    /** 已经发过一条，等回复 */
    PING,
    /** 第二条也没回，允许轻轻抱怨一次 */
    SULK,
    /** 退让，几天内不主动 */
    BACKOFF,
    /** 重新出现，发一条无索取感的内容 */
    REENTRY,
    /** 用户说忙/别发，指定时间前不打扰 */
    DND
}
