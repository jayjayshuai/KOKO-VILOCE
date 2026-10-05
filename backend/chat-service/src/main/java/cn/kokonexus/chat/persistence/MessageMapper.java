package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 消息事实存储；唯一索引保护序号与重试键。 */
public interface MessageMapper extends BaseMapper<ChatMessage> {}
