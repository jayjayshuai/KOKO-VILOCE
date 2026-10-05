package cn.kokonexus.voice.application;

import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 关闭意图与确认各自短事务；媒体删除在事务外，归属在CLOSING冻结。 */
@Service
@RequiredArgsConstructor
public class VoiceClosureState {

    /** 关闭与转让共用当前读房间锁。 */ private final VoiceRoomMapper rooms;
    /** 受控房间关闭审计，失败与意图同事务回滚。 */ private final VoiceInteractionMapper interaction;

    @Transactional(timeout = 3)
    public VoiceRoom begin(long owner, long id) {
        VoiceRoom room = owned(owner, id);
        if ("CLOSED".equals(room.getStatus()) || "CLOSING".equals(room.getStatus())) return room;
        if (!"OPEN".equals(room.getStatus())) throw new IllegalStateException("当前房间状态不允许关闭");
        one(rooms.markClosing(id, owner));
        audit(room, owner, "CLOSE_REQUEST");
        room.setStatus("CLOSING");
        return room;
    }

    @Transactional(timeout = 3)
    public void finish(long owner, long id) {
        VoiceRoom room = owned(owner, id);
        if ("CLOSED".equals(room.getStatus())) return;
        if (!"CLOSING".equals(room.getStatus())) throw new IllegalStateException("关闭意图已变化，拒绝确认");
        one(rooms.markClosed(id, owner));
        audit(room, owner, "CLOSED");
    }

    private VoiceRoom owned(long owner, long id) {
        if (owner <= 0 || id <= 0) throw new IllegalArgumentException("身份或房间无效");
        VoiceRoom room = rooms.lockRoom(id);
        if (
            room == null || room.getOwnerId() == null || room.getOwnerId() != owner
        ) throw new ResourceNotFoundException("语音房不存在或无权操作");
        return room;
    }

    private void audit(VoiceRoom room, long owner, String type) {
        if (!"CONTROLLED".equals(room.getControlMode())) return;
        one(interaction.bumpVersion(room.getId()));
        one(
            interaction.audit(
                UUID.randomUUID().toString(),
                room.getId(),
                owner,
                type,
                Math.addExact(room.getInteractionVersion(), 1),
                interaction.databaseNow(),
                owner,
                null,
                null,
                null
            )
        );
    }

    private static void one(int changed) {
        if (changed != 1) throw new IllegalStateException("关闭状态未确认，请按原房间重试");
    }
}
