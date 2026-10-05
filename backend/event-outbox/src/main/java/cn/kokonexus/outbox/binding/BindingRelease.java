package cn.kokonexus.outbox.binding;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 与领域提交同事务的资产释放任务；不代表回滚或未知提交的自动恢复凭据。 */
@Getter
@Setter
@TableName("asset_binding_release")
public class BindingRelease {

    /** 不可复用的绑定请求 UUID，也是释放幂等键。 */
    @TableId(type = IdType.INPUT)
    private String requestId;

    /** 资产所有者用户 ID。 */
    private Long ownerId;
    /** 受管资产规范 UUID。 */
    private String assetId;
    /** AVATAR/BANNER/POST_COVER，与原领取一致。 */
    private String purpose;
    /** PENDING/LEASED/SENT/DEAD；DEAD 仍需核对，不删除资产保护。 */
    private String status;
    /** 单调累计领取次数，上限110次，人工代次和进程崩溃也不清零。 */
    private Integer attempts;
    /** 人工受理代次，初始0、上限10；旧代次不能重新覆盖当前状态。 */
    private Integer replayGeneration;
    /** 当前代次已用领取次数，0～10，只有追加审计受理新代次时归零。 */
    private Integer generationAttempts;
    /** 当前执行随机令牌，防止旧工作者确认新一轮。 */
    private String leaseToken;
    /** 租约截止时间，数据库会话时间原值，不带时区。 */
    private LocalDateTime leaseUntil;
    /** 最早重试时间，数据库会话时间原值，不带时区。 */
    private LocalDateTime nextAttemptAt;
    /** 有界固定失败类别，不包含原始 RPC 错误或用户正文。 */
    private String lastFailure;
    /** 持久化任务创建时间，数据库会话时间原值，不带时区。 */
    private LocalDateTime createdAt;
    /** 最近状态变化时间，数据库会话时间原值，不带时区。 */
    private LocalDateTime updatedAt;
}
