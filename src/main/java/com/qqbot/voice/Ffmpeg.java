package com.qqbot.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg / ffprobe 的可执行文件定位与调用。
 * 服务在 Windows 上以 LocalSystem 运行，PATH 里可能没有 ffmpeg，因此支持 FFMPEG_PATH 覆盖。
 */
final class Ffmpeg {

    private Ffmpeg() {
    }

    /** ffmpeg 路径：优先环境变量，其次 E:\tools 下的固定安装，最后交给 PATH */
    static String ffmpeg() {
        String env = System.getenv("FFMPEG_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        Path fixed = Path.of("E:/tools/ffmpeg/ffmpeg.exe");
        return Files.exists(fixed) ? fixed.toString() : "ffmpeg";
    }

    static String ffprobe() {
        String env = System.getenv("FFPROBE_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String ffmpeg = ffmpeg();
        String name = Path.of(ffmpeg).getFileName().toString();
        if (name.startsWith("ffmpeg")) {
            Path sibling = Path.of(ffmpeg).resolveSibling(name.replace("ffmpeg", "ffprobe"));
            if (Files.exists(sibling)) {
                return sibling.toString();
            }
        }
        return "ffprobe";
    }

    record Exec(int exit, String output) {
        boolean ok() {
            return exit == 0;
        }
    }

    /** 执行命令，合并 stdout/stderr 返回（stderr 里才有 volumedetect/silencedetect 的信息） */
    static Exec run(int timeoutSeconds, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture<String> reader = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output;
        try {
            output = reader.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            output = "";
        }
        return new Exec(finished ? process.exitValue() : -1, output);
    }
}
