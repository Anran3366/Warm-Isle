package com.qqbot.account;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

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
        return "下面是容器里 QQ 登录窗的二维码（已裁好放大）。码约 2 分钟过期，"
                + "点「刷新二维码」会自动换一张，不用重启实例。"
                + " 实在扫不到时再用远程桌面：127.0.0.1:" + (uiPort + 100)
                + "，密码 " + vncPassword(wsPort) + "。";
    }

    /**
     * 容器里跑 qr_helper.py：它会把窗口从「自动登录」切到扫码页、点掉 s26 弹窗、
     * 过期自动点「刷新」，最后把二维码区域裁出来放大到 320x320。
     *
     * <p>脚本以 base64 内嵌在命令里写到容器 /tmp，避免依赖容器里的仓库文件。
     * {out} 由 {@code AccountService.captureInContainer} 换成一次性文件路径。</p>
     */
    @Override
    public String qrcodeCaptureCmd() {
        return "mkdir -p /tmp/.slqr && printf %s '" + QR_HELPER_B64 + "'"
                + " | base64 -d > /tmp/.slqr/qr_helper.py"
                + " && python3 /tmp/.slqr/qr_helper.py {out}";
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

    /** 内嵌的二维码辅助脚本（见 resources/snowluma/qr_helper.py） */
    private static final String QR_HELPER_B64 = loadHelper();

    private static String loadHelper() {
        try (InputStream in = SnowLumaBackend.class.getResourceAsStream("/snowluma/qr_helper.py")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /snowluma/qr_helper.py");
            }
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("读取 qr_helper.py 失败", e);
        }
    }

    private String vncPassword(int wsPort) {
        return "sl" + wsPort;
    }
}
