package cn.kokonexus.voice.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内部持久投影，不直接作为HTTP响应或生成敏感会话toString。 */
public final class VoiceInteraction {

    /** 应用只追加、不更新审计；不是数据库管理员防篡改证明。 */
    @Getter
    @Setter
    public static class Action {

        /** 原行为版本，房间内唯一。 */ private Long resultVersion;
        /** 系统回收为空。 */ private Long actorId;
        /** 原命令名。 */ private String commandType;
        /** 原目标，不靠后来占用推断。 */ private Long targetUserId;
        /** 原麦位，可空。 */ private Integer seatNo;
        /** 原预约UUID，可空。 */ private String seatRequestId;
        /** 原希望值，可空。 */ private Boolean desiredValue;
        /** 数据库时间。 */ private LocalDateTime createdAt;
    }

    private VoiceInteraction() {}

    /** 房主/房管权限与席位ON_MIC独立；HOST是普通成员的展示派生态。 */
    public enum CommandType {
        APPLY,
        INVITE,
        PULL,
        ACCEPT,
        REJECT,
        CANCEL,
        DOWN,
        KICK,
        MUTE,
        LOCK,
        LEAVE,
        ADMIN,
        TRANSFER,
    }

    @Getter
    @Setter
    public static class Member {

        /** 所属房间。 */ private Long roomId;
        /** 可信账号。 */ private Long userId;
        /** 目录确认的名称快照。 */ private String displayName;
        /** OWNER/ADMIN/LISTENER。 */ private String roomRole;
        /** ACTIVE/LEFT；仍需租约时间检查。 */ private String memberState;
        /** 服务端会话UUID，旧轮次不能控制新成员。 */ private String sessionId;
        /** 数据库时钟租约期限。 */ private LocalDateTime leaseUntil;
    }

    @Getter
    @Setter
    public static class Seat {

        /** 所属房间。 */ private Long roomId;
        /** 固定1～8。 */ private Integer seatNo;
        /** EMPTY/LOCKED/RESERVED/ON_MIC，SQL事实不是媒体实际权限。 */ private String seatState;
        /** 占用账号，空麦为null。 */ private Long userId;
        /** 占用成员会话，防迟到请求影响新一代。 */ private String sessionId;
        /** 预约请求UUID，非预约为null。 */ private String requestId;
        /** 希望闭麦状态；媒体未开放不等于真实音轨。 */ private Boolean muted;
    }

    @Getter
    @Setter
    public static class SeatRequest {

        /** 服务端请求UUID。 */ private String id;
        /** 所属房间。 */ private Long roomId;
        /** 目标席位。 */ private Integer seatNo;
        /** 申请或受邀成员。 */ private Long userId;
        /** 成员会话轮次。 */ private String sessionId;
        /** APPLY/INVITE。 */ private String requestType;
        /** PENDING及终态。 */ private String requestState;
        /** 数据库时钟预约期限。 */ private LocalDateTime expiresAt;
    }

    @Getter
    @Setter
    public static class Receipt {

        /** 绑定全部命令参数的SHA-256，不是密码哈希。 */ private String fingerprint;
        /** JOIN或受约束命令名。 */ private String commandType;
        /** 原提交版本，不冒充当前快照。 */ private Long resultVersion;
        /** JOIN原会话UUID，其余为空。 */ private String resultSessionId;
    }
}
