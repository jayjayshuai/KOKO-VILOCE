package cn.kokonexus.asset.interfaces;

import cn.kokonexus.asset.application.AssetApplicationService;
import cn.kokonexus.asset.application.AssetRegistrationService;
import cn.kokonexus.asset.domain.AssetReferenceSnapshot;
import cn.kokonexus.asset.domain.MediaAsset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** asset-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@RestController
@RequestMapping("/api/assets/images")
@Tag(name = "受管媒体资产")
public class AssetController {

    /** AssetApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final AssetApplicationService service;
    /** AssetRegistrationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final AssetRegistrationService registration;

    public AssetController(AssetApplicationService service, AssetRegistrationService registration) {
        this.service = service;
        this.registration = registration;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "上传私有图片",
        description = "仅接受本人 JPEG/PNG，最大 5 MiB；返回的资产 ID 需经所属业务域再次验证后才能绑定。"
    )
    public AssetView upload(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam String purpose,
        @RequestParam MultipartFile file
    ) {
        return AssetView.from(service.upload(userId, purpose, file));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取本人资产元数据", description = "非所有者返回 404；对象键和存储凭据不对外返回。")
    public AssetView metadata(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id
    ) {
        return AssetView.from(service.ownedReady(userId, id));
    }

    @GetMapping
    @Operation(
        summary = "分页读取本人图片库",
        description = "按用途筛选 READY 图片；游标必须属于本人且用途一致，单页最大 24 条。"
    )
    public AssetSliceView list(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam String purpose,
        @RequestParam(required = false) String before,
        @RequestParam(defaultValue = "12") int size
    ) {
        var slice = service.listOwned(userId, purpose, before, size);
        return new AssetSliceView(slice.items().stream().map(AssetView::from).toList(), slice.nextCursor());
    }

    /** asset-service：AssetSliceView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record AssetSliceView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<AssetView> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "下一页游标；无更多记录时为空") String nextCursor
    ) {}

    @GetMapping("/quota")
    @Operation(
        summary = "读取本人图片库额度",
        description = "统计所有用途及等待清理的上传，不暴露其他用户或存储内部信息。"
    )
    public AssetRegistrationService.QuotaView quota(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId
    ) {
        return registration.quota(userId);
    }

    @GetMapping("/{id}/content")
    @Operation(
        summary = "读取图片内容",
        description = "所有者可读私有草稿；其他用户和匿名访客仅能读取已发布主页或文章正在引用的资产。"
    )
    public ResponseEntity<byte[]> content(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(
            value = "X-Koko-User-Id",
            required = false
        ) Long userId,
        @PathVariable String id
    ) {
        MediaAsset asset = service.readable(id, userId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asset.getContentType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(service.content(asset));
    }

    @GetMapping("/{id}/references")
    @Operation(
        summary = "核验本人图片的全状态引用",
        description = "包括主页草稿/停用、文章草稿/归档。" +
            "非所有者返回404；任一引用域不可用返回503。不返回引用资源身份，也不授权物理删除。"
    )
    public AssetReferenceSnapshot references(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @io.swagger.v3.oas.annotations.Parameter(description = "本人 READY 图片的 UUID") @PathVariable String id,
        HttpServletResponse response
    ) {
        // 错误响应也禁止缓存，避免保留跨会话引用快照或将故障当成稳定结果。
        response.setHeader("Cache-Control", "no-store");
        return service.references(userId, id);
    }

    /** asset-service：AssetView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record AssetView(
        @Schema(description = "服务端生成的 UUID") String id,
        @Schema(description = "AVATAR、BANNER 或 POST_COVER") String purpose,
        @io.swagger.v3.oas.annotations.media.Schema(description = "归一化媒体 MIME 类型") String contentType,
        @Schema(description = "归一化后字节数") long byteSize,
        @Schema(description = "像素宽度") int width,
        @Schema(description = "像素高度") int height,
        @io.swagger.v3.oas.annotations.media.Schema(description = "归一化内容 SHA-256 摘要") String sha256,
        @io.swagger.v3.oas.annotations.media.Schema(description = "服务端创建时间，数据库时区 Asia/Shanghai")
        LocalDateTime createdAt
    ) {
        static AssetView from(MediaAsset asset) {
            return new AssetView(
                asset.getId(),
                asset.getPurpose(),
                asset.getContentType(),
                asset.getByteSize(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getSha256(),
                asset.getCreatedAt()
            );
        }
    }
}
