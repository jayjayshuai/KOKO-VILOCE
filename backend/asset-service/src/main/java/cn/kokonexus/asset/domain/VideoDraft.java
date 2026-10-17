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
    /** UPLOADING/QUEUED/PROCESSING/READY/FAILED。 */ private String state;
    /** 领取轮次随机UUID，过期任务不能覆盖新事实。 */ private String runId;
    /** 数据库UTC处理截止，不由客户端时间续期。 */ private LocalDateTime leaseUntil;
    /** 成功产物字节数，上限64MiB。 */ private Long outputBytes;
    /** 完整检查的产物时长，毫秒。 */ private Long durationMillis;
    /** 产物像素宽度。 */ private Integer width;
    /** 产物像素高度。 */ private Integer height;
    /** 固定失败代码，不保存供应商原始错误。 */ private String failureCode;
    /** 数据库UTC创建时间。 */ private LocalDateTime createdAt;
    /** 数据库UTC最近状态变化时间。 */ private LocalDateTime updatedAt;
}
