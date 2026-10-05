package cn.kokonexus.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** asset-service：MediaAsset 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("media_asset")
public class MediaAsset {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 资源所有者用户 ID。 */
    private Long ownerId;
    /** 图片用途，AVATAR/BANNER/POST_COVER。 */
    private String purpose;
    /** 内部对象存储键，禁止直接暴露给客户端。 */
    private String objectKey;
    /** 归一化媒体 MIME 类型。 */
    private String contentType;
    /** 归一化资源字节数。 */
    private Long byteSize;
    /** 图像像素宽度。 */
    private Integer width;
    /** 图像像素高度。 */
    private Integer height;
    /** 归一化内容 SHA-256 摘要。 */
    private String sha256;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 下次可执行时间，Asia/Shanghai。 */
    private LocalDateTime readyAt;
}
