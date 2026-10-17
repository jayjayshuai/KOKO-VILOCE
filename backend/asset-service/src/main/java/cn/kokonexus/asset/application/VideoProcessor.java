package cn.kokonexus.asset.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 真正解码并归一化为H.264/AAC；探测与转码共用100秒预算，不接受外部URL或用户命令。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class VideoProcessor {

    /** 固定私有路径及受信任工具。 */ private final VideoFiles files;
    /** 只解析有界ffprobe JSON，不读取供应商错误正文。 */ private final ObjectMapper json;

    public Result process(Path source, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        Result before = probe(source, deadline);
        List<String> command = new ArrayList<>(
            List.of(
                files.ffmpeg(),
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-max_alloc",
                "67108864",
                "-xerror",
                "-threads",
                "2"
            )
        );
        command.addAll(input(source));
        command.addAll(
            List.of(
                "-map",
                "0:v:0",
                "-map",
                "0:a:0?",
                "-sn",
                "-dn",
                "-map_metadata",
                "-1",
                "-map_chapters",
                "-1",
                "-filter_threads",
                "1",
                "-vf",
                "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=30",
                "-c:v",
                "libx264",
                "-threads",
                "2",
                "-preset",
                "veryfast",
                "-crf",
                "25",
                "-maxrate",
                "2000k",
                "-bufsize",
                "4000k",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-ac",
                "2",
                "-ar",
                "48000",
                "-b:a",
                "96k",
                "-t",
                "181",
                "-fs",
                "66060288",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                "-y",
                output.toString()
            )
        );
        run(command, source.getParent(), deadline);
        if (
            !Files.isRegularFile(output) || Files.size(output) < 16 || Files.size(output) > 64L * 1024 * 1024
        ) throw new InvalidVideoException();
        Result after = probe(output, deadline);
        if (
            !after.codec().equals("h264") || after.durationMillis() + 1500 < before.durationMillis()
        ) throw new InvalidVideoException();
        return after;
    }

    private Result probe(Path source, long deadline) throws Exception {
        var command = new ArrayList<>(
            List.of(files.ffprobe(), "-v", "error", "-max_alloc", "67108864", "-threads", "2")
        );
        command.addAll(input(source).subList(0, input(source).size() - 2));
        command.addAll(
            List.of(
                "-select_streams",
                "v:0",
                "-show_entries",
                "format=duration:stream=codec_name,width,height",
                "-of",
                "json",
                source.toString()
            )
        );
        JsonNode root = json.readTree(run(command, source.getParent(), deadline));
        var streams = root.path("streams");
        double seconds = root.path("format").path("duration").asDouble(Double.NaN);
        int width = streams.path(0).path("width").asInt(),
            height = streams.path(0).path("height").asInt();
        if (
            streams.size() != 1 ||
            !Double.isFinite(seconds) ||
            seconds < 0.1 ||
            seconds > 180 ||
            width < 2 ||
            height < 2 ||
            width > 1920 ||
            height > 1920 ||
            (long) width * height > 1920L * 1080
        ) throw new InvalidVideoException();
        return new Result(Math.round(seconds * 1000), width, height, streams.path(0).path("codec_name").asText());
    }

    private List<String> input(Path source) {
        // mov解复用禁用外部轨引用；仅开放file协议，不允许播放列表、网络或管道输入。
        return List.of(
            "-protocol_whitelist",
            "file",
            "-f",
            "mov",
            "-enable_drefs",
            "0",
            "-use_absolute_path",
            "0",
            "-i",
            source.toString()
        );
    }

    private byte[] run(List<String> command, Path directory, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Video processing deadline");
        var builder = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        String windows = System.getenv("SystemRoot");
        builder.environment().clear();
        if (windows != null) builder.environment().put("SystemRoot", windows);
        Process process = builder.start();
        var collected = new FutureTask<byte[]>(() -> {
            try (var stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(8193);
                if (bytes.length > 8192) {
                    process.destroyForcibly();
                    throw new IOException("Bounded process output exceeded");
                }
                return bytes;
            }
        });
        Thread reader = Thread.ofVirtual().name("video-output").start(collected);
        try {
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new java.util.concurrent.TimeoutException(
                "Video processing deadline"
            );
            byte[] bytes = collected.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new InvalidVideoException();
            return bytes;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            collected.cancel(true);
            reader.interrupt();
        }
    }

    /** 只包含已校验的产物事实。 */
    public record Result(
        /** 时长毫秒。 */ long durationMillis,
        /** 像素宽。 */ int width,
        /** 像素高。 */ int height,
        /** 实际视频编码器名。 */ String codec
    ) {}

    /** 固定非法媒体错误，不携带本地路径或解码器输出。 */
    public static class InvalidVideoException extends RuntimeException {}
}
