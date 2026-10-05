package cn.kokonexus.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 跨服务提交前的持久化保护，不代表最终资料引用，也不是可超时失效的租约。 */
@Getter
@Setter
@TableName("asset_binding_intent")
public class AssetBindingIntent {

    /** 调用者生成的规范小写 UUID；相同请求不允许更换资产、所有者或用途。 */
    @TableId(type = IdType.INPUT)
    private String requestId;

    /** 被保护的媒体资产 UUID；外键阻止遗漏意图的资产删除。 */
    private String assetId;
    /** 资产所有者用户 ID，必须大于零。 */
    private Long ownerId;
    /** 绑定用途：AVATAR、BANNER 或 POST_COVER。 */
    private String purpose;
    /** 创建时间，数据库时区 Asia/Shanghai；仅用于观察，不作为到期删除依据。 */
    private LocalDateTime createdAt;
}
