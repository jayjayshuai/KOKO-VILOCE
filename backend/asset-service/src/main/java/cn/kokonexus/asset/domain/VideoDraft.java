package cn.kokonexus.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 私有视频控制事实；不直接作为HTTP响应，存储路径仅由服务端按ID推导。 */
@Getter
@Setter
@TableName("video_draft")
public class VideoDraft {

    /** 服务端生成的草稿UUID。 */ @TableId(type = IdType.INPUT)
    private String id;

    /** 所有者平台ID，不能使用客户端身份替代。 */ private Long ownerId;
    /** 所有者范围内的原上传请求UUID。 */ private String requestId;
    /** 规范化标题，最多100字。 */ private String title;
    /** 私有说明，最多1000字。 */ private String description;
    /** 原始字节SHA-256，用于同请求内容围栏。 */ private String sourceSha256;
    /** 原文件实际字节数，上限32MiB。 */ private Long sourceBytes;
    /** UPLOADING/QUEUED/PROCESSING/READY/FAILED/DELETING/DELETED。 */ private String state;
    /** V7写入协议；旧行false不推断不存在迟到PUT。 */ private Boolean writeTracking;
    /** 原处理线程已退出，删除期间不提前让出有效处理槽。 */ private Boolean processingSettled;
    /** 领取轮次随机UUID，过期任务不能覆盖新事实。 */ private String runId;
    /** 数据库UTC处理截止，不由客户端时间续期。 */ private LocalDateTime leaseUntil;
    /** 成功产物字节数，上限64MiB。 */ private Long outputBytes;
    /** 完整检查的产物时长，毫秒。 */ private Long durationMillis;
    /** 产物像素宽度。 */ private Integer width;
    /** 产物像素高度。 */ private Integer height;
    /** 固定失败代码，不保存供应商原始错误。 */ private String failureCode;
    /** 数据库UTC创建时间。 */ private LocalDateTime createdAt;
    /** 数据库UTC最近状态变化时间。 */ private LocalDateTime updatedAt;
    /** 原删除请求UUID。 */ private String deleteRequestId;
    /** UTC删除请求时间，不表示对象已清理。 */ private LocalDateTime deleteRequestedAt;
    /** 内部清理领取令牌，不返回页面。 */ private String deleteToken;
    /** UTC清理领取期限。 */ private LocalDateTime deleteLeaseUntil;
    /** 领取次数，上限5。 */ private Integer deleteAttempts;
    /** 下次清理时间，耗尽为空。 */ private LocalDateTime deleteNextAt;
    /** 固定错误代码，不含存储原文。 */ private String deleteError;
    /** 真正核验清理及释放额度的UTC时间。 */ private LocalDateTime deletedAt;

    /** 当前等待原因的SQL投影，不参与MP写入。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String deletionWaitReason;
}
