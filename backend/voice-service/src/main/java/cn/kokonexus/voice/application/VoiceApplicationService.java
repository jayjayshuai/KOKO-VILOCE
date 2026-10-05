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

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 10000, retries = 0)
    private IdentityRpcService identityRpcService;

    public VoiceApplicationService(VoiceRoomMapper roomMapper, VoiceMediaGateway mediaGateway) {
        this.roomMapper = roomMapper;
        this.mediaGateway = mediaGateway;
    }

    public VoiceRoom create(long ownerId, String slug, String title, String topic, int maxParticipants) {
        var owner = identityRpcService.findActiveUser(String.valueOf(ownerId));
        VoiceRoom room = new VoiceRoom();
        room.setOwnerId(ownerId);
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
        var identity = identityRpcService.findActiveUser(String.valueOf(userId));
        String token = mediaGateway.issueJoinToken(room.getProviderRoomName(), userId, identity.displayName());
        return new JoinCredential(mediaGateway.publicUrl(), token, room.getProviderRoomName());
    }

    public void close(long ownerId, long roomId) {
        VoiceRoom room = roomMapper.selectById(roomId);
        if (
            room == null ||
            room.getOwnerId() == null ||
            room.getOwnerId() != ownerId ||
            !"OPEN".equals(room.getStatus())
        ) {
            throw new IllegalArgumentException("语音房不存在、无权操作或已经关闭");
        }
        mediaGateway.delete(room.getProviderRoomName());
        if (roomMapper.markClosed(roomId, ownerId) != 1) {
            throw new IllegalStateException("语音房关闭状态更新失败");
        }
    }

    /** voice-service：JoinCredential 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record JoinCredential(
        @io.swagger.v3.oas.annotations.media.Schema(description = "客户端连接地址") String url,
        @io.swagger.v3.oas.annotations.media.Schema(description = "短期连接凭据，禁止日志输出") String token,
        @io.swagger.v3.oas.annotations.media.Schema(description = "媒体房间名称") String roomName
    ) {}
}
