package cn.kokonexus.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** asset-service：AssetUsage 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("asset_usage")
public class AssetUsage {

    /** 资源所有者用户 ID。 */
    @TableId(type = IdType.INPUT)
    private Long ownerId;

    /** 占用的图片数量，包含待清理预留。 */
    private Long imageCount;
    /** 归一化资源字节数。 */
    private Long byteSize;
}
