CREATE TABLE chat_bookmark (
    id VARCHAR(36) NOT NULL COMMENT '本人收藏引用UUID，不复制消息正文',
    owner_id BIGINT NOT NULL COMMENT '主动收藏用户ID，不对其他成员公开',
    conversation_id VARCHAR(36) NOT NULL COMMENT '真实消息所属会话UUID',
    message_id VARCHAR(36) NOT NULL COMMENT '收藏的真实消息UUID',
    message_seq BIGINT NOT NULL COMMENT '真实消息会话内序号，倒序游标',
    created_at DATETIME(6) NOT NULL COMMENT '服务端收藏时间Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_bookmark_owner_message (owner_id, message_id),
    KEY idx_chat_bookmark_owner_conversation_seq (owner_id, conversation_id, message_seq),
    CONSTRAINT fk_chat_bookmark_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id),
    CONSTRAINT fk_chat_bookmark_message FOREIGN KEY (message_id) REFERENCES chat_message(id),
    CONSTRAINT chk_chat_bookmark_owner_seq CHECK (owner_id > 0 AND message_seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='个人消息收藏引用，读取时重新验证当前成员及入群历史边界';
