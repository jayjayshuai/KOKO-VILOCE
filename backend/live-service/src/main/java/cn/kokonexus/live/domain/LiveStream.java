package cn.kokonexus.live.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** live-service：LiveStream 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("live_stream")
public class LiveStream {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 创作者用户 ID。 */
    private Long creatorId;
    /** 创作者名称快照。 */
    private String creatorName;
    /** 公开访问路径标识。 */
    private String slug;
    /** 业务标题。 */
    private String title;
    /** 内容分类。 */
    private String category;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 真实媒体供应商名称。 */
    private String provider;
    /** 媒体供应商输入 ID，不代表已经开播。 */
    private String providerInputId;
    /** 是否允许互动连麦。 */
    private Boolean interactive;
    /** 观看人数。 */
    private Long viewerCount;
    /** 乐观锁版本，修改必须携带当前值。 */
    private Integer version;
    /** 开始时间；未开始为空，Asia/Shanghai。 */
    private LocalDateTime startedAt;
    /** 结束时间；未结束为空，Asia/Shanghai。 */
    private LocalDateTime endedAt;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
