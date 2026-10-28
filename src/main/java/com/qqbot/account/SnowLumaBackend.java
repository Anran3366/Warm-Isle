package com.qqbot.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * SnowLuma：一个账号一个容器。
 *
 * <p>和 NapCat 的区别：</p>
 * <ul>
 *   <li>需要 {@code SYS_PTRACE} + {@code seccomp=unconfined} 才能把 hook 注入 QQ 进程，缺了会起不来；</li>
 *   <li>QQ 基于 Chromium，必须给足够的 /dev/shm；</li>
 *   <li>二维码显示在容器内的 QQ 桌面窗口里，没有落盘 PNG，后台只能引导用户去远程桌面扫码。</li>
 * </ul>
 *
 * <p>端口分配（id 为账号 id）：WebSocket = 3200+id → 容器 3001；
 * WebUI = 6400+id → 容器 5099；noVNC 远程桌面 = 6500+id → 容器 6081。
 * 全部只绑 127.0.0.1，需要时自行开 SSH 隧道。</p>
 */
@Component
public class SnowLumaBackend implements ProtocolBackend {

    public static final String KEY = "snowluma";
    private static final String IMAGE = "motricseven7/snowluma:latest";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "SnowLuma";
    }

    @Override
    public int wsPortBase() {
        return 3200;
    }

    @Override
    public int uiPortBase() {
        return 6400;
    }

    @Override
    public String containerPrefix() {
        return "snowluma-";
    }

    @Override
    public void prepareDirs(Path base) throws IOException {
        Files.createDirectories(base.resolve("data"));
        Files.createDirectories(base.resolve("qq"));
        Files.createDirectories(base.resolve("local"));
    }

    @Override
    public String createBody(String hostBase, int wsPort, int uiPort, String network) {
        int noVncPort = uiPort + 100;
        String vncPass = vncPassword(wsPort);
        return """
                {
                  "Image": "%s",
                  "Env": [
                    "TZ=Asia/Shanghai",
                    "SNOWLUMA_WEBUI_HOST=0.0.0.0",
                    "SNOWLUMA_ACCEPT_EULA=1",
                    "SNOWLUMA_ACCEPT_PRIVACY=1",
                    "SNOWLUMA_HOOK_AUTOLOAD=1",
                    "VNC_PASSWD=%s"
                  ],
                  "ExposedPorts": {"3001/tcp": {}, "5099/tcp": {}, "6081/tcp": {}},
                  "HostConfig": {
                    "Binds": [
                      "%s/data:/app/data",
                      "%s/qq:/app/.config",
                      "%s/local:/app/.local/share"
                    ],
                    "PortBindings": {
                      "3001/tcp": [{"HostIp": "127.0.0.1", "HostPort": "%d"}],
                      "5099/tcp": [{"HostIp": "127.0.0.1", "HostPort": "%d"}],
                      "6081/tcp": [{"HostIp": "127.0.0.1", "HostPort": "%d"}]
                    },
                    "CapAdd": ["SYS_PTRACE"],
                    "SecurityOpt": ["seccomp=unconfined"],
                    "ShmSize": 536870912,
                    "Memory": 2147483648,
                    "MemorySwap": 3221225472,
                    "RestartPolicy": {"Name": "unless-stopped"},
                    "NetworkMode": "%s"
                  }
                }
                """.formatted(IMAGE, vncPass, hostBase, hostBase, hostBase, wsPort, uiPort, noVncPort, network);
    }

    @Override
    public Path qrcodePath(Path base) {
        // 二维码在容器内的 QQ 窗口里，没有文件可读
        return null;
    }

    @Override
    public String scanHint(int wsPort, int uiPort) {
        return "下面是容器桌面的截图，直接扫图里的二维码；显示「已过期」就点「刷新二维码」重新截。"
                + " 截不到时用远程桌面：127.0.0.1:" + (uiPort + 100)
                + "，密码 " + vncPassword(wsPort) + "。";
    }

    /**
     * 用 xwininfo 找 QQ 登录窗的绝对坐标，再按该区域截屏。
     * 找不到窗口时退回整屏（1920x1080），保证至少有图。
     */
    @Override
    public String qrcodeCaptureCmd() {
        // 取面积最大的 QQ 窗口（登录窗 720x640，另有 1x1/小工具窗，不能只看第一条），
        // 找不到就失败返回，由前端提示「正在启动」，避免把整个桌面糊上去
        return "rm -f /tmp/qr.png; "
                + "L=$(xwininfo -root -tree 2>/dev/null | grep '\"QQ\"' | awk '"
                + "{ area=0; for(i=1;i<=NF;i++){ if ($i ~ /^[0-9]+x[0-9]+\\+/) { "
                + "split($i,a,\"+\"); split(a[1],d,\"x\"); area=d[1]*d[2] } } "
                + "if (area>max) { max=area; line=$0 } } "
                + "END { if (max>=40000) print line }'); "
                + "[ -z \"$L\" ] && exit 3; "
                + "SZ=$(echo \"$L\" | grep -oE '[0-9]+x[0-9]+\\+' | head -1 | tr -d '+'); "
                + "W=${SZ%x*}; H=${SZ#*x}; "
                + "POS=$(echo \"$L\" | grep -oE '\\+[0-9]+\\+[0-9]+$' | tail -1); "
                + "X=$(echo \"$POS\" | cut -d+ -f2); Y=$(echo \"$POS\" | cut -d+ -f3); "
                + "ffmpeg -loglevel error -f x11grab -draw_mouse 0 -video_size ${W}x${H} "
                + "-i :1+${X},${Y} -frames:v 1 -y /tmp/qr.png";
    }

    /**
     * SnowLuma 会给每个账号自动生成 WS/HTTP 的 accessToken，而后端连的是 NapCat 那套配置（无 token）。
     * 这里在登录前把 token 清空，让后端能直接连上；容器端口只在回环/内部网络暴露。
     * 等并发账号变多、要跨主机时再改成「后端读取每账号 token」。
     */
    @Override
    public String postStartCmd() {
        return "for i in $(seq 1 30); do ls /app/data/config/onebot*.json >/dev/null 2>&1 && break; sleep 1; done; "
                + "python3 -c \"import json,glob;"
                + "ps=glob.glob('/app/data/config/onebot*.json');"
                + "[json.dump({**d,'networks':{**d['networks'],**{k:[{**n,'accessToken':''} for n in d['networks'].get(k,[])] "
                + "for k in ('wsServers','httpServers')}}},open(p,'w'),indent=2,ensure_ascii=False) "
                + "for p in ps for d in [json.load(open(p))]]\"";
    }

    private String vncPassword(int wsPort) {
        return "sl" + wsPort;
    }
}
