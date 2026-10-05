package cn.kokonexus.voice.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 非媒体快照，成员会话只返回本人，不返回内部供应商名称。 */
public final class VoiceInteractionViews {

    public record ActionView(
        @Schema(description = "原房间版本字符串，房间内唯一") String version,
        @Schema(description = "操作者ID字符串，系统回收为null", nullable = true) String actorId,
        @Schema(description = "原操作名") String type,
        @Schema(description = "原目标ID字符串，可空", nullable = true) String targetUserId,
        @Schema(description = "原麦位，可空", nullable = true) Integer seatNo,
        @Schema(description = "原预约UUID，可空", nullable = true) String seatRequestId,
        @Schema(description = "原闭麦/锁定/房管目标值，可空", nullable = true) Boolean value,
        @Schema(description = "数据库时间") LocalDateTime createdAt
    ) {}

    public record ActionPage(
        @Schema(description = "仅房主/现任有效房管可读，最多50") List<ActionView> items,
        @Schema(description = "下一页独占版本字符串，末页null", nullable = true) String nextBefore
    ) {}

    private VoiceInteractionViews() {}

    /** 条件同步仍执行当前授权与租约回收；不是媒体事件或免授权缓存。 */
    public record SyncView(
        @Schema(description = "回收后的房间版本字符串，禁转JS number") String version,
        @Schema(description = "本次数据库核验时间，Asia/Shanghai") LocalDateTime checkedAt,
        @Schema(description = "版本相同为null；变化时返回完整当前授权快照", nullable = true) Snapshot snapshot
    ) {
        @Override
        public String toString() {
            return "VoiceSync[redacted]";
        }
    }

    /** 只查询登录用户自己的原提交，不能把未找到解释为原操作已取消。 */
    public record ReceiptView(
        @Schema(description = "是否已查到本人同事务持久收据；false不证明在途命令失败") boolean committed,
        @Schema(description = "原已提交事实，不代表当前房间或会话仍有效", nullable = true) Ack ack
    ) {
        @Override
        public String toString() {
            return "VoiceReceipt[redacted]";
        }
    }

    public record Capabilities(
        @Schema(description = "互动核心是否已配置启用；不等于正式运营") boolean enabled,
        @Schema(description = "媒体授权未接入，当前固定false") boolean mediaReady,
        @Schema(description = "单调版本字符串，禁转JS number；禁用时null", nullable = true) String version,
        @Schema(description = "本人是否有效成员或房主，可读取私有快照；服务端仍重新授权") boolean canInspect
    ) {}

    public record Ack(
        @Schema(description = "原提交类型，不代表当前房间状态") String type,
        @Schema(description = "原提交单调版本字符串") String version,
        @Schema(description = "JOIN本人会话UUID，其他为null；不是登录凭据", nullable = true) String sessionId
    ) {
        @Override
        public String toString() {
            return "VoiceAck[redacted]";
        }
    }

    public record MemberView(
        @Schema(description = "公开用户ID字符串") String userId,
        @Schema(description = "身份目录名称快照") String displayName,
        @Schema(description = "OWNER/ADMIN/LISTENER；HOST为普通成员在麦派生态") String role,
        @Schema(description = "本人当前SQL麦位号，未占用为null", nullable = true) Integer seatNo
    ) {}

    public record SeatView(
        @Schema(description = "麦位1～8") int seatNo,
        @Schema(description = "EMPTY/LOCKED/RESERVED/ON_MIC；SQL事实非音轨权限") String state,
        @Schema(description = "占用用户ID字符串，空位为null", nullable = true) String userId,
        @Schema(description = "希望闭麦，不代表LiveKit音轨状态") boolean muted
    ) {}

    public record RequestView(
        @Schema(description = "服务端申请/邀请UUID") String id,
        @Schema(description = "目标麦位1～8") int seatNo,
        @Schema(description = "申请或受邀用户ID字符串") String userId,
        @Schema(description = "APPLY/INVITE") String type,
        @Schema(description = "数据库时区期限，Asia/Shanghai") LocalDateTime expiresAt
    ) {}

    public record Snapshot(
        @Schema(description = "房间ID字符串") String roomId,
        @Schema(description = "单调房间版本字符串") String version,
        @Schema(description = "真实数据库时钟，非客户端墙钟") LocalDateTime serverTime,
        @Schema(description = "成员会话租约秒数") int leaseSeconds,
        @Schema(description = "本人会话UUID，未加入为null", nullable = true) String mySessionId,
        @Schema(description = "本人角色，未加入房主可观察但为null", nullable = true) String myRole,
        @Schema(description = "媒体权限尚未接入，固定false") boolean mediaReady,
        @Schema(description = "有有效租约的成员，最多100；非媒体服务器在线数") List<MemberView> members,
        @Schema(description = "仅管理方可见的长期房管名单，最多8，可能离线；不是在线人数")
        List<MemberView> administrators,
        @Schema(description = "八个SQL麦位") List<SeatView> seats,
        @Schema(description = "仅房管/本人可见的有效预约，最多8") List<RequestView> requests
    ) {
        @Override
        public String toString() {
            return "VoiceSnapshot[redacted]";
        }
    }
}
