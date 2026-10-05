package cn.kokonexus.asset.infrastructure.persistence;

import cn.kokonexus.asset.domain.AssetBindingIntent;
import cn.kokonexus.asset.domain.MediaAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** 绑定意图持久化；领取和释放始终先锁同一资产行，清理必须遵守相同锁顺序。 */
public interface AssetBindingIntentMapper extends BaseMapper<AssetBindingIntent> {
    /** 锁定读取永久完成标记；不存在时也由请求主键唯一约束防止跨资产请求冲突。 */
    AssetBindingIntent lockCompletion(@Param("requestId") String requestId);

    /** 完整指纹不可更新，重复插入不同指纹由唯一约束拒绝并回滚。 */
    int insertCompletion(
        @Param("ownerId") long ownerId,
        @Param("assetId") String assetId,
        @Param("purpose") String purpose,
        @Param("requestId") String requestId
    );
    /** 事务内锁定资产，不执行跨服务调用或存储 I/O。 */
    MediaAsset lockAsset(@Param("assetId") String assetId);

    /** 精确匹配完整请求指纹，重复完成返回零，不允许仅凭 UUID 删除他人保护。 */
    int deleteMatching(
        @Param("ownerId") long ownerId,
        @Param("assetId") String assetId,
        @Param("purpose") String purpose,
        @Param("requestId") String requestId
    );
}
