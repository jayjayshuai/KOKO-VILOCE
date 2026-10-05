package cn.kokonexus.asset.infrastructure.persistence;

import cn.kokonexus.asset.domain.MediaAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** asset-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface MediaAssetMapper extends BaseMapper<MediaAsset> {}
