package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.AssetReferenceSnapshot;
import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.MediaAssetMapper;
import cn.kokonexus.asset.infrastructure.rpc.AssetPublicationLookup;
import cn.kokonexus.asset.infrastructure.storage.AssetStorage;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** asset-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class AssetApplicationService {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(AssetApplicationService.class);
    /** PURPOSES 服务端协议常量，不接受客户端覆盖。 */
    private static final Set<String> PURPOSES = Set.of("AVATAR", "BANNER", "POST_COVER");
    /** MediaAssetMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final MediaAssetMapper mapper;
    /** AssetStorage 外部或领域适配器，失败不伪装为业务成功。 */
    private final AssetStorage storage;
    /** ImageInspector 外部或领域适配器，失败不伪装为业务成功。 */
    private final ImageInspector inspector;
    /** AssetPublicationLookup 外部或领域适配器，失败不伪装为业务成功。 */
    private final AssetPublicationLookup publicationLookup;
    /** AssetRegistrationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final AssetRegistrationService registration;
    /** 单实例上传并发槽，控制图像解码内存峰值。 */
    private final Semaphore uploadSlot = new Semaphore(1);

    public AssetApplicationService(
        MediaAssetMapper mapper,
        AssetStorage storage,
        ImageInspector inspector,
        AssetPublicationLookup publicationLookup,
        AssetRegistrationService registration
    ) {
        this.mapper = mapper;
        this.storage = storage;
        this.inspector = inspector;
        this.publicationLookup = publicationLookup;
        this.registration = registration;
    }

    public MediaAsset upload(long ownerId, String purpose, MultipartFile file) {
        requireOwner(ownerId);
        String normalizedPurpose = normalizePurpose(purpose);
        if (!uploadSlot.tryAcquire()) throw new AssetUploadBusyException();
        try {
            return uploadWithinSlot(ownerId, normalizedPurpose, file);
        } finally {
            uploadSlot.release();
        }
    }

    private MediaAsset uploadWithinSlot(long ownerId, String normalizedPurpose, MultipartFile file) {
        ImageInspector.InspectedImage image = inspector.inspect(file);
        String id = UUID.randomUUID().toString();
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setOwnerId(ownerId);
        asset.setPurpose(normalizedPurpose);
        asset.setObjectKey("images/" + ownerId + "/" + id + "." + image.extension());
        asset.setContentType(image.contentType());
        asset.setByteSize((long) image.bytes().length);
        asset.setWidth(image.width());
        asset.setHeight(image.height());
        asset.setSha256(image.sha256());
        asset.setStatus("PENDING");
        asset.setCreatedAt(LocalDateTime.now());
        registration.register(asset);
        // MySQL and MinIO cannot share a transaction. A stale PENDING row is deliberately
        // retained for the reconciler if object writing or the READY transition fails.
        storage.put(asset.getObjectKey(), image.bytes(), image.contentType());
        LocalDateTime readyAt = LocalDateTime.now();
        int updated = mapper.update(
            null,
            Wrappers.<MediaAsset>update()
                .eq("id", id)
                .eq("status", "PENDING")
                .set("status", "READY")
                .set("ready_at", readyAt)
        );
        if (updated != 1) throw new IllegalStateException("媒体资产状态更新失败");
        asset.setStatus("READY");
        asset.setReadyAt(readyAt);
        return asset;
    }

    public MediaAsset ownedReady(long ownerId, String id) {
        requireOwner(ownerId);
        MediaAsset asset = mapper.selectById(parseId(id));
        if (asset == null || !asset.getOwnerId().equals(ownerId) || !"READY".equals(asset.getStatus())) {
            throw new ResourceNotFoundException("媒体资产不存在");
        }
        return asset;
    }

    public AssetSlice listOwned(long ownerId, String purpose, String before, int size) {
        requireOwner(ownerId);
        String normalizedPurpose = normalizePurpose(purpose);
        if (size < 1 || size > 24) throw new IllegalArgumentException("每次可读取 1 至 24 张图片");
        QueryWrapper<MediaAsset> query = Wrappers.<MediaAsset>query()
            .eq("owner_id", ownerId)
            .eq("purpose", normalizedPurpose)
            .eq("status", "READY");
        if (before != null && !before.isBlank()) {
            MediaAsset cursor = ownedReady(ownerId, before);
            if (!normalizedPurpose.equals(cursor.getPurpose())) {
                throw new IllegalArgumentException("分页游标的图片用途不匹配");
            }
            query.and(q ->
                q
                    .lt("created_at", cursor.getCreatedAt())
                    .or(tie -> tie.eq("created_at", cursor.getCreatedAt()).lt("id", cursor.getId()))
            );
        }
        // size is bounded above; the extra row determines whether another page exists.
        List<MediaAsset> rows = mapper.selectList(query.orderByDesc("created_at", "id").last("LIMIT " + (size + 1)));
        List<MediaAsset> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new AssetSlice(items, rows.size() > size ? items.getLast().getId() : null);
    }

    /** asset-service：AssetSlice 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record AssetSlice(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<MediaAsset> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "下一页游标；无更多记录时为空") String nextCursor
    ) {}

    public boolean isOwnedReady(long ownerId, String id, String purpose) {
        try {
            MediaAsset asset = ownedReady(ownerId, id);
            return asset.getPurpose().equals(purpose);
        } catch (ResourceNotFoundException exception) {
            return false;
        }
    }

    /** 所有权校验在 RPC 之前；只返回引用布尔值，不返回其他资源正文或对象键。 */
    public AssetReferenceSnapshot references(long ownerId, String id) {
        MediaAsset asset = ownedReady(ownerId, id);
        return publicationLookup.referenceSnapshot(asset.getId());
    }

    public MediaAsset readable(String id, Long viewerId) {
        MediaAsset asset = mapper.selectById(parseId(id));
        if (asset == null || !"READY".equals(asset.getStatus())) {
            throw new ResourceNotFoundException("媒体资产不存在");
        }
        if (viewerId != null && asset.getOwnerId().equals(viewerId)) return asset;
        if (!publicationLookup.isPublished(asset.getId())) {
            throw new ResourceNotFoundException("媒体资产不存在");
        }
        return asset;
    }

    public byte[] content(MediaAsset asset) {
        if (!"READY".equals(asset.getStatus())) throw new ResourceNotFoundException("媒体资产不存在");
        return storage.get(asset.getObjectKey());
    }

    @Scheduled(fixedDelayString = "${koko.asset.reconcile-delay-ms:3600000}")
    public void reconcileStaleUploads() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<MediaAsset> stale = mapper.selectList(
            Wrappers.<MediaAsset>query()
                .in("status", "PENDING", "CLEANING")
                .lt("created_at", cutoff)
                .orderByAsc("created_at")
                .last("LIMIT 50")
        );
        for (MediaAsset asset : stale) {
            try {
                if (
                    "PENDING".equals(asset.getStatus()) &&
                    mapper.update(
                        null,
                        Wrappers.<MediaAsset>update()
                            .eq("id", asset.getId())
                            .eq("status", "PENDING")
                            .set("status", "CLEANING")
                    ) != 1
                ) continue;
                storage.remove(asset.getObjectKey());
                registration.deleteCleaning(asset);
            } catch (RuntimeException exception) {
                log.warn("Stale media asset cleanup failed for id={}", asset.getId(), exception);
            }
        }
    }

    private String parseId(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (RuntimeException exception) {
            throw new ResourceNotFoundException("媒体资产不存在");
        }
    }

    private void requireOwner(long ownerId) {
        if (ownerId <= 0) throw new IllegalArgumentException("用户标识无效");
    }

    private String normalizePurpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        if (!PURPOSES.contains(normalized)) throw new IllegalArgumentException("媒体用途无效");
        return normalized;
    }
}
