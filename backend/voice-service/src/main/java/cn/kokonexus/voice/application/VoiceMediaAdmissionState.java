package cn.kokonexus.voice.application;

import cn.kokonexus.voice.infrastructure.media.LiveKitJoinTokenVerifier.VerifiedJoin;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 准入只核对当前房间，SQL锁内不访问媒体或身份RPC；不把核验视为WebSocket全会话授权。 */
@Service
@RequiredArgsConstructor
public class VoiceMediaAdmissionState {

    /** 与关闭意图/转让共用的当前房间行锁。 */ private final VoiceRoomMapper rooms;

    /** 已提交CLOSING即拒绝；CONTROLLED尚无完整媒体协议，始终拒绝旧发布凭据。 */
    @Transactional(timeout = 2)
    public boolean allows(VerifiedJoin join) {
        var room = rooms.lockRoom(join.roomId());
        return (
            room != null &&
            "OPEN".equals(room.getStatus()) &&
            "LEGACY".equals(room.getControlMode()) &&
            join.providerRoomName().equals(room.getProviderRoomName())
        );
    }
}
