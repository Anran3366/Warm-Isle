package com.qqbot.account;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 协议端（QQ 接入实现）描述。
 *
 * <p>目的：把「怎么起一个实例、怎么扫码、怎么找二维码」从账号管理里抽出来，
 * 后台新建账号时可以选择用 NapCat 还是 SnowLuma，业务层（OneBotClient 及以上）不受影响。</p>
 *
 * <p>约定：每个实例一个容器，容器内的 OneBot WebSocket 固定是 3001，
 * 宿主 / Docker 网络内用「容器名:3001」，所以 wsUrl 一律是 {@code ws://<container>:3001}。</p>
 */
public interface ProtocolBackend {

    /** 存进 bot_accounts.backend 的稳定标识，如 napcat / snowluma */
    String key();

    /** 后台下拉里显示的名字 */
    String displayName();

    /** 实例 WS 端口的起始值（实际端口 = 起始值 + 账号 id） */
    int wsPortBase();

    /** 实例 WebUI / 远程桌面的起始端口 */
    int uiPortBase();

    /** 容器名前缀（实际容器名 = 前缀 + 账号 id） */
    String containerPrefix();

    /** 准备宿主目录（路径是 qqbot 容器内可见的挂载点） */
    void prepareDirs(Path base) throws IOException;

    /** 生成 Docker Engine API 的 /containers/create 请求体 */
    String createBody(String hostBase, int wsPort, int uiPort, String network);

    /**
     * 二维码图片的宿主路径；返回 null 表示这个协议端不落盘 PNG，
     * 后台只能引导用户去远程桌面/WebUI 扫码。
     */
    Path qrcodePath(Path base);

    /** 后台「扫码」区域展示给用户的提示 */
    String scanHint(int wsPort, int uiPort);

    /**
     * 在容器内抓一张二维码图片的 shell 命令（结果写到 /tmp/qr.png）。
     * 返回 null 表示这个协议端不需要截图（二维码已经落盘）。
     */
    default String qrcodeCaptureCmd() {
        return null;
    }

    /**
     * 容器启动后要执行的一次性命令（比如改配置）。返回 null 表示不需要。
     * 命令在容器内以 root 身份、sh -lc 执行。
     */
    default String postStartCmd() {
        return null;
    }
}
