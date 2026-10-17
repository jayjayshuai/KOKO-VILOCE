package cn.kokonexus.asset.interfaces;

import cn.kokonexus.asset.application.AssetUploadBusyException;
import cn.kokonexus.asset.application.VideoDeletionState;
import cn.kokonexus.asset.application.VideoDraftService;
import cn.kokonexus.asset.application.VideoDraftState;
import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 私有草稿接口；不对外公开对象键、源文件、进程日志或分发URL。 */
@RestController
@RequestMapping("/api/assets/videos")
@Tag(name = "私有视频草稿")
@RequiredArgsConstructor
public class VideoDraftController {

    /** 能力关闭时不实例化视频处理/存储资源。 */ private final ObjectProvider<VideoDraftService> uploads;
    /** 逐请求检查SQL所有者，而非仅在页面路由鉴权。 */ private final ObjectProvider<VideoDraftState> states;
    /** 私有有界流式存储。 */ private final ObjectProvider<VideoStorage> storage;
    /** 独立上线门禁；全部写入节点升级且旧任务排空后才能启用删除。 */ private final ObjectProvider<VideoDeletionState> deletions;
    /** 单实例最多4个播放流；不按视频长度分配byte数组。 */ private final Semaphore streams = new Semaphore(4);

    @GetMapping("/capabilities")
    @Operation(
        summary = "查询私有视频草稿能力",
        description = "未部署或未配置FFmpeg/上传限制时不开放，不代表视频公开发布"
    )
    public Capability capability() {
        boolean enabled = uploads.getIfAvailable() != null;
        return new Capability(enabled, 33554432, 180, 5, 100663296, enabled && deletions.getIfAvailable() != null);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "上传或恢复原私有视频草稿",
        description = "原UUID、文件及资料必须固定；202仅表示登记或排队，READY后才可本人预览，不等于发布"
    )
    public View upload(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long owner,
        @RequestParam String requestId,
        @RequestParam String title,
        @RequestParam(defaultValue = "") String description,
        @RequestParam MultipartFile file
    ) throws Exception {
        var service = uploads.getIfAvailable();
        if (service == null) throw unavailable();
        return View.from(service.upload(owner, requestId, title, description, file));
    }

    @GetMapping
    @Operation(
        summary = "读取本人视频草稿",
        description = "固定额度最多5条，包括失败和未完成上传；失败不释放未核验的存储预算"
    )
    public List<View> list(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long owner) {
        return state().owned(owner).stream().map(View::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取本人草稿真实处理状态", description = "他人或不存在均404；READY只证明私有产物处理完成")
    public View read(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long owner, @PathVariable String id) {
        return View.from(state().owned(owner, id));
    }

    @PostMapping("/{id}/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "受理本人视频删除意图或重试原请求",
        description = "立即撤回后续预览；202不代表对象已删除或额度已释放。DELETED才证明已核验所有版本不存在并回收额度。未知写入及旧版写入需人工核验，不自动清额度。"
    )
    public View delete(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long owner,
        @PathVariable String id,
        @RequestBody Deletion input
    ) {
        var service = deletions.getIfAvailable();
        if (service == null) throw unavailable();
        if (input == null) throw new IllegalArgumentException("缺少原删除请求");
        return View.from(service.request(owner, id, input.requestId()));
    }

    public record Deletion(@Schema(description = "首次删除UUID；重试不可变更") String requestId) {}

    @RequestMapping(value = "/{id}/content", method = { RequestMethod.GET, RequestMethod.HEAD })
    @Operation(
        summary = "本人私有MP4预览",
        description = "READY且所有者匹配才可读；支持单Range/HEAD，非法区间416；不提供源文件或匿名分发"
    )
    public void content(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long owner,
        @PathVariable String id,
        @RequestHeader(value = "Range", required = false) String range,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        var row = state().owned(owner, id);
        if (!row.getState().equals("READY")) throw new ResourceNotFoundException("视频尚未处理完成");
        VideoByteRange selected;
        try {
            // RFC Range只应用于GET；HEAD返回完整表示的响应头，不因Range触发媒体读取。
            // 未提供缓存校验器，If-Range不能证明匹配；未知范围单位也须忽略而非泄露错误区间。
            String effective =
                request.getMethod().equals("HEAD") ||
                request.getHeader("If-Range") != null ||
                (range != null && !range.startsWith("bytes="))
                    ? null
                    : range;
            selected = VideoByteRange.parse(effective, row.getOutputBytes());
        } catch (IllegalArgumentException invalid) {
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + row.getOutputBytes());
            return;
        }
        if (request.getMethod().equals("HEAD")) {
            playbackHeaders(response, selected, row.getOutputBytes());
            return;
        }
        if (!streams.tryAcquire()) throw new AssetUploadBusyException();
        try (
            var input = storage.getObject().open(VideoDraftService.outputKey(row), selected.start(), selected.length())
        ) {
            // 成功打开对象后再设置媒体长度，避免错误JSON继承视频Content-Length。
            playbackHeaders(response, selected, row.getOutputBytes());
            byte[] buffer = new byte[16384];
            long remaining = selected.length();
            var out = response.getOutputStream();
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new java.io.IOException("Incomplete video object");
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } finally {
            streams.release();
        }
    }

    private static void playbackHeaders(HttpServletResponse response, VideoByteRange selected, long size) {
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType("video/mp4");
        response.setHeader("Content-Disposition", "inline");
        if (selected.partial()) {
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + selected.start() + "-" + selected.end() + "/" + size);
        }
        response.setContentLengthLong(selected.length());
    }

    private VideoDraftState state() {
        var state = states.getIfAvailable();
        if (state == null) throw unavailable();
        return state;
    }

    private static ExternalDependencyUnavailableException unavailable() {
        return new ExternalDependencyUnavailableException("视频草稿尚未开放", null);
    }

    public record Capability(
        @Schema(description = "是否可上传私有视频草稿") boolean enabled,
        @Schema(description = "最大原文件字节数") long maxBytes,
        @Schema(description = "最大时长秒数") int maxSeconds,
        @Schema(description = "本人最多预留草稿数，含失败") int maxDrafts,
        @Schema(description = "每条固定预留字节数，含源文件与产物") long reservedBytesPerDraft,
        @Schema(description = "全部写入节点已升级并单独启用删除核验门禁") boolean deletionEnabled
    ) {}

    public record View(
        @Schema(description = "私有视频UUID") String id,
        @Schema(description = "本人原上传请求UUID") String requestId,
        @Schema(description = "标题") String title,
        @Schema(description = "私有说明") String description,
        @Schema(description = "UPLOADING/QUEUED/PROCESSING/READY/FAILED/DELETING/DELETED") String state,
        @Schema(description = "原文件实际字节数") long sourceBytes,
        @Schema(description = "READY产物字节数，可空") Long outputBytes,
        @Schema(description = "READY产物时长毫秒，可空") Long durationMillis,
        @Schema(description = "READY产物像素宽，可空") Integer width,
        @Schema(description = "READY产物像素高，可空") Integer height,
        @Schema(description = "固定失败代码，可空") String failureCode,
        @Schema(description = "UTC创建时间") LocalDateTime createdAtUtc,
        @Schema(description = "UTC最近变化时间") LocalDateTime updatedAtUtc,
        @Schema(description = "已受理的原删除请求UUID，可空") String deleteRequestId,
        @Schema(description = "删除等待原因固定枚举，不包含对象路径或供应商信息") String deletionWaitReason
    ) {
        static View from(VideoDraft row) {
            return new View(
                row.getId(),
                row.getRequestId(),
                row.getTitle(),
                row.getDescription(),
                row.getState(),
                row.getSourceBytes(),
                row.getOutputBytes(),
                row.getDurationMillis(),
                row.getWidth(),
                row.getHeight(),
                row.getFailureCode(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeleteRequestId(),
                row.getDeletionWaitReason()
            );
        }
    }
}
