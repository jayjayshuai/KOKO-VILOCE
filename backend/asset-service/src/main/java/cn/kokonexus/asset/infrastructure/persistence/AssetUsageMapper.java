package cn.kokonexus.asset.infrastructure.persistence;

import cn.kokonexus.asset.domain.AssetUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** asset-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface AssetUsageMapper extends BaseMapper<AssetUsage> {
    @Insert(
        "INSERT INTO asset_usage(owner_id,image_count,byte_size) VALUES(#{owner},0,0) " +
            "ON DUPLICATE KEY UPDATE owner_id=owner_id"
    )
    void ensureOwner(@Param("owner") long owner);

    @Update(
        "UPDATE asset_usage SET image_count=image_count+1,byte_size=byte_size+#{bytes} " +
            "WHERE owner_id=#{owner} AND image_count < #{maxImages} AND byte_size <= #{maxBytes}-#{bytes}"
    )
    int reserve(
        @Param("owner") long owner,
        @Param("bytes") long bytes,
        @Param("maxBytes") long maxBytes,
        @Param("maxImages") long maxImages
    );

    @Update(
        "UPDATE asset_usage SET image_count=image_count-1,byte_size=byte_size-#{bytes} " +
            "WHERE owner_id=#{owner} AND image_count >= 1 AND byte_size >= #{bytes}"
    )
    int release(@Param("owner") long owner, @Param("bytes") long bytes);
}
