package com.qqbot.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

@Component
public class NapCatBackend implements ProtocolBackend {

    public static final String KEY = "napcat";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "NapCat";
    }

    @Override
    public int wsPortBase() {
        return 3000;
    }

    @Override
    public int uiPortBase() {
        return 6100;
    }

    @Override
    public String containerPrefix() {
        return "napcat-";
    }

    @Override
    public void prepareDirs(Path base) throws IOException {
        Files.createDirectories(base.resolve("config"));
        Files.createDirectories(base.resolve("qq"));
        Files.createDirectories(base.resolve("cache"));
    }

    @Override
    public String createBody(String hostBase, int wsPort, int uiPort, String network) {
        return """
                {
                  "Image": "mlikiowa/napcat-docker:latest",
                  "Env": ["NAPCAT_UID=1000", "NAPCAT_GID=1000"],
                  "ExposedPorts": {"3001/tcp": {}, "6099/tcp": {}},
                  "HostConfig": {
                    "Binds": [
                      "%s/config:/app/napcat/config",
                      "%s/qq:/app/.config/QQ",
                      "%s/cache:/app/napcat/cache"
                    ],
                    "PortBindings": {
                      "3001/tcp": [{"HostIp": "127.0.0.1", "HostPort": "%d"}],
                      "6099/tcp": [{"HostIp": "127.0.0.1", "HostPort": "%d"}]
                    },
                    "RestartPolicy": {"Name": "unless-stopped"},
                    "ShmSize": 268435456,
                    "Memory": 1073741824,
                    "NetworkMode": "%s"
                  }
                }
                """.formatted(hostBase, hostBase, hostBase, wsPort, uiPort, network);
    }

    @Override
    public Path qrcodePath(Path base) {
        return base.resolve("cache").resolve("qrcode.png");
    }

    @Override
    public String scanHint(int wsPort, int uiPort) {
        return "点「刷新二维码」，图片会显示在这里";
    }
}
