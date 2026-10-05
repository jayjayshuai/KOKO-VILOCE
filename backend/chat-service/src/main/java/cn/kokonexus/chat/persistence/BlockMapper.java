package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 本人拉黑关系 CRUD，查询必须包含 ownerId。 */
public interface BlockMapper extends BaseMapper<ChatBlock> {}
