package cn.kokonexus.voice.application;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.media.VoiceMediaGateway;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Locale;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** voice-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class VoiceApplicationService {

    /** VoiceRoomMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final VoiceRoomMapper roomMapper;
    /** VoiceMediaGateway 外部或领域适配器，失败不伪装为业务成功。 */
    private final VoiceMediaGateway mediaGateway;
    /** 意图/确认短事务，不在SQL锁内调用媒体。 */
    private final VoiceClosureState closure;

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 10000, retries = 0)
    private IdentityRpcService identityRpcService;

    public VoiceApplicationService(
        VoiceRoomMapper roomMapper,
        VoiceMediaGateway mediaGateway,
        VoiceClosureState closure
    ) {
        this.roomMapper = roomMapper;
        this.mediaGateway = mediaGateway;
        this.closure = closure;
    }

    public VoiceRoom create(long ownerId, String slug, String title, String topic, int maxParticipants) {
        return create(ownerId, slug, title, topic, maxParticipants, "LEGACY");
    }

    /** 仅新房间受控，不转换可能持有旧发布JWT的房间；Controller先检查候选开关。 */
    public VoiceRoom createControlled(long ownerId, String slug, String title, String topic, int maxParticipants) {
        return create(ownerId, slug, title, topic, maxParticipants, "CONTROLLED");
    }

    private VoiceRoom create(long ownerId, String slug, String title, String topic, int maxParticipants, String mode) {
        var owner = identityRpcService.findActiveUser(String.valueOf(ownerId));
        VoiceRoom room = new VoiceRoom();
        room.setOwnerId(ownerId);
        room.setControlMode(mode);
        room.setOwnerName(owner.displayName());
        room.setSlug(slug.toLowerCase(Locale.ROOT));
        room.setTitle(title.trim());
        room.setTopic(topic == null ? null : topic.trim());
        room.setStatus("PROVISIONING");
        room.setMaxParticipants(maxParticipants);
        try {
            if (roomMapper.insert(room) != 1) {
                throw new IllegalStateException("语音房创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("语音房地址已被占用", exception);
        }

        String providerRoomName = "koko-voice-" + room.getId();
        try {
            mediaGateway.provision(providerRoomName, maxParticipants);
            if (roomMapper.markOpen(room.getId(), providerRoomName) != 1) {
                mediaGateway.delete(providerRoomName);
                throw new IllegalStateException("语音房状态更新失败");
            }
            room.setProviderRoomName(providerRoomName);
            room.setStatus("OPEN");
            return room;
        } catch (RuntimeException exception) {
            roomMapper.markFailed(room.getId());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<VoiceRoom> discover(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        Page<VoiceRoom> page = Page.of(1, safeLimit, false);
        page.addOrder(OrderItem.desc("created_at"));
        return roomMapper
            .selectPage(
                page,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<VoiceRoom>lambdaQuery().eq(VoiceRoom::getStatus, "OPEN")
            )
            .getRecords();
    }

    @Transactional(readOnly = true)
    public JoinCredential join(long userId, long roomId) {
        VoiceRoom room = roomMapper.selectById(roomId);
        if (room == null || !"OPEN".equals(room.getStatus())) {
            throw new IllegalArgumentException("语音房不存在或已关闭");
        }
        if ("CONTROLLED".equals(room.getControlMode())) {
            throw new cn.kokonexus.common.api.ExternalDependencyUnavailableException(
                "受控房间媒体授权尚未开放，不签发原发布凭据",
                null
            );
        }
        var identity = identityRpcService.findActiveUser(String.valueOf(userId));
        String token = mediaGateway.issueJoinToken(room.getProviderRoomName(), userId, identity.displayName());
        return new JoinCredential(mediaGateway.publicUrl(), token, room.getProviderRoomName());
    }

    /** 本人的全部状态房间；独占ID游标，不计总数、不读取供应商名称。 */
    @Transactional(readOnly = true, timeout = 3)
    public OwnedRoomPage ownedRooms(long ownerId, String before, int size) {
        if (ownerId <= 0 || size < 1 || size > 50) throw new IllegalArgumentException("房主或分页大小无效");
        Long beforeId = null;
        if (before != null) {
            if (!before.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("房间游标无效");
            try {
                beforeId = Long.valueOf(before);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("房间游标超出范围");
            }
        }
        List<VoiceRoom> found = roomMapper.ownedRooms(ownerId, beforeId, size + 1);
        boolean more = found.size() > size;
        List<VoiceRoom> items = List.copyOf(found.subList(0, Math.min(found.size(), size)));
        String nextBefore = more ? String.valueOf(items.getLast().getId()) : null;
        return new OwnedRoomPage(items, nextBefore);
    }

    /** 关闭意图先提交冻结归属；媒体失败保留CLOSING，原房间重试，外部I/O不持SQL锁。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void close(long ownerId, long roomId) {
        if (ownerId <= 0 || roomId <= 0) throw new IllegalArgumentException("房主或房间标识无效");
        VoiceRoom room = closure.begin(ownerId, roomId);
        if ("CLOSED".equals(room.getStatus())) return;
        mediaGateway.delete(room.getProviderRoomName());
        closure.finish(ownerId, roomId);
    }

    /** 内部查询投影，Controller另映射为无供应商字段的公开响应。 */
    public record OwnedRoomPage(
        /** 本页本人房间；不会暴露给其他账号。 */ List<VoiceRoom> items,
        /** 下一页独占ID游标；null表示本轮末页。 */ String nextBefore
    ) {}

    /** voice-service：JoinCredential 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record JoinCredential(
        /** 客户端媒体连接地址，不作为日志上下文。 */
        @io.swagger.v3.oas.annotations.media.Schema(description = "客户端连接地址") String url,
        /** 短期JWT，仅HTTP响应中交给当前用户，禁止日志。 */
        @io.swagger.v3.oas.annotations.media.Schema(description = "短期连接凭据，禁止日志输出") String token,
        /** 当前凭据限定的媒体房间名。 */
        @io.swagger.v3.oas.annotations.media.Schema(description = "媒体房间名称") String roomName
    ) {
        /** 记录类默认toString会泄漏JWT，HTTP字段序列化不受此脱敏影响。 */
        @Override
        public String toString() {
            return "JoinCredential[redacted]";
        }
    }
}
