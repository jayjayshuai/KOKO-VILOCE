package cn.kokonexus.asset.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 所有文件仅在运维配置的私有工作根目录，路径及进程参数不接受用户输入。 */
@Component
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class VideoFiles {

    /** 已规范化且非符号链接的私有工作根。 */ private final Path root;
    /** 受信任FFmpeg绝对路径，缺失时禁止开放能力。 */ private final String ffmpeg;
    /** 受信任ffprobe绝对路径。 */ private final String ffprobe;

    public VideoFiles(
        @Value("${koko.video.work-directory:}") String directory,
        @Value("${koko.video.ffmpeg:}") String ffmpeg,
        @Value("${koko.video.ffprobe:}") String ffprobe,
        org.springframework.boot.autoconfigure.web.servlet.MultipartProperties multipart
    ) throws IOException {
        if (
            multipart.getMaxFileSize().toBytes() < 32L * 1024 * 1024 ||
            multipart.getMaxRequestSize().toBytes() < 33L * 1024 * 1024
        ) throw new IllegalStateException("开启视频前须配置32MiB文件/33MiB请求上传上限");
        Path requested = Path.of(directory);
        if (
            directory.isBlank() || !requested.isAbsolute() || Files.isSymbolicLink(requested)
        ) throw new IllegalStateException("请配置私有视频工作目录绝对路径");
        Files.createDirectories(requested);
        root = requested.toRealPath();
        String multipartLocation = multipart.getLocation();
        if (
            multipartLocation == null || multipartLocation.isBlank() || !Path.of(multipartLocation).isAbsolute()
        ) throw new IllegalStateException("开启视频前须配置私有multipart临时目录");
        Files.createDirectories(Path.of(multipartLocation));
        if (!Path.of(multipartLocation).toRealPath().startsWith(root)) throw new IllegalStateException(
            "视频multipart临时目录须位于私有工作根内"
        );
        for (String executable : List.of(ffmpeg, ffprobe))
            if (
                executable.isBlank() ||
                !Path.of(executable).isAbsolute() ||
                !Files.isRegularFile(Path.of(executable), LinkOption.NOFOLLOW_LINKS) ||
                !Files.isExecutable(Path.of(executable))
            ) throw new IllegalStateException("视频处理程序绝对路径不存在或不可执行");
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    public Path create() throws IOException {
        return Files.createTempDirectory(root, "video-");
    }

    public String ffmpeg() {
        return ffmpeg;
    }

    public String ffprobe() {
        return ffprobe;
    }

    /** 只清理本组件创建的直属任务目录，不跟随符号链接，不触碰工作根或任意文件路径。 */
    public void cleanup(Path directory) throws IOException {
        if (
            !directory.getParent().equals(root) ||
            !directory.getFileName().toString().startsWith("video-") ||
            Files.isSymbolicLink(directory)
        ) throw new IllegalArgumentException("视频任务清理目录不匹配");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
