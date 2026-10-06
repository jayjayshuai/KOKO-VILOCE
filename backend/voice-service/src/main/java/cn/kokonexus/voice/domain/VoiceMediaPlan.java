package cn.kokonexus.voice.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内部媒体计划，不代表已经生效的SFU权限；不生成会话/身份日志。 */
public final class VoiceMediaPlan {

    private VoiceMediaPlan() {}

    @Getter
    @Setter
    public static class Binding {

        /** 受控房间ID。 */ private Long roomId;
        /** 可信成员账号，仅后端持有。 */ private Long userId;
        /** 对应成员会话，不能复用新成员。 */ private String sessionId;
        /** 单调媒体授权轮次，不重置或溢出。 */ private Long generation;
        /** 独立不含PII的随机UUID，授权变化必须更换。 */ private String mediaIdentity;
        /** ACTIVE/INACTIVE，希望接入状态。 */ private String bindingState;
        /** 当前SQL席位事实希望是否允许发声，非SFU确认。 */ private Boolean publishDesired;
        /** 仅ON_MIC的本人授权麦位号；未在麦为null，变更也推进轮次。 */ private Integer seatNo;
    }

    @Getter
    @Setter
    public static class Retirement {

        /** 不可变任务UUID。 */ private String id;
        /** 原受控房间。 */ private Long roomId;
        /** 原成员账号，不用作LiveKit身份。 */ private Long userId;
        /** 被淘汰的原轮次。 */ private Long generation;
        /** 固定原供应商房间，禁止根据当前所有者重算。 */ private String providerRoomName;
        /** 固定原随机媒体身份，永不指向新轮次。 */ private String mediaIdentity;
        /** PENDING/PROCESSING/DONE/DEAD。 */ private String jobState;
        /** 领取即消耗预算，上限10；重启不归零。 */ private Integer attempts;
        /** 本次执行租约UUID。 */ private String leaseToken;
        /** 数据库时钟租约截止。 */ private LocalDateTime leaseUntil;
    }
}
