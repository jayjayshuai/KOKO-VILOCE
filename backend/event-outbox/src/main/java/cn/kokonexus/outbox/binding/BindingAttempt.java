package cn.kokonexus.outbox.binding;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 写域原请求凭据；OPEN不是终态，只有同行锁下的状态迁移才构成结束证明。 */
@Getter
@Setter
@TableName("asset_binding_attempt")
public class BindingAttempt {

    /** 原绑定UUID，与资产保护/释放任务共享，不是人工命令ID。 */
    @TableId(type = IdType.INPUT)
    private String requestId;

    /** 不可变资产所有者，仅用于内部指纹校验。 */
    private Long ownerId;
    /** 不可变受管资产UUID，不含对象键。 */
    private String assetId;
    /** AVATAR/BANNER/POST_COVER，不允许跨域改用途。 */
    private String purpose;
    /** OPEN/COMMITTED/ABORTED，终态不可返回OPEN。 */
    private String status;
    /** 业务库创建时点，仅筛选候选，不是过期判决。 */
    private LocalDateTime createdAt;
    /** 业务库最近迁移时点，微秒精度。 */
    private LocalDateTime updatedAt;
}
