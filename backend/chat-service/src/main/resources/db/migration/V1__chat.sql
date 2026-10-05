CREATE TABLE chat_conversation (
    id VARCHAR(36) NOT NULL COMMENT '服务端 UUID',
    kind VARCHAR(10) NOT NULL COMMENT 'DIRECT 或 GROUP',
    direct_key VARCHAR(42) NULL COMMENT '排序后的用户对，私信唯一',
    owner_id BIGINT NOT NULL COMMENT '创建者；群主有管理权限',
    title VARCHAR(80) NOT NULL COMMENT '群名称或私信标识',
    last_seq BIGINT NOT NULL DEFAULT 0 COMMENT '本会话最后已提交序号',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 或 CLOSED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_chat_direct (direct_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chat_member (
    id VARCHAR(36) NOT NULL COMMENT '成员关系 UUID',
    conversation_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(80) NOT NULL COMMENT '加入时的公开显示名称快照',
    handle VARCHAR(32) NOT NULL COMMENT '公开用户名快照',
    joined_seq BIGINT NOT NULL COMMENT '新成员不能读取此序号及以前的群历史',
    read_seq BIGINT NOT NULL COMMENT '只前进的已读序号',
    PRIMARY KEY(id), UNIQUE KEY uk_chat_member(conversation_id,user_id),
    KEY ix_chat_member_user(user_id,conversation_id),
    CONSTRAINT fk_chat_member_conversation FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chat_message (
    id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL COMMENT '会话内单调递增序号',
    sender_id BIGINT NOT NULL,
    sender_name VARCHAR(80) NOT NULL COMMENT '发送时公开名称快照',
    client_message_id VARCHAR(36) NOT NULL COMMENT '客户端重试 UUID',
    body VARCHAR(2000) NOT NULL COMMENT '纯文本，不解释 HTML',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id), UNIQUE KEY uk_chat_message_seq(conversation_id,seq),
    UNIQUE KEY uk_chat_message_retry(conversation_id,sender_id,client_message_id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
