CREATE TABLE chat_safety_actor_lock (
    user_id BIGINT NOT NULL COMMENT '反滥用写入的用户级事务锁',
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='拉黑容量和举报配额的串行化锁';

CREATE TABLE chat_contact_lock (
    low_id BIGINT NOT NULL COMMENT '用户对中较小的用户ID',
    high_id BIGINT NOT NULL COMMENT '用户对中较大的用户ID',
    low_blocks_high TINYINT NOT NULL DEFAULT 0 COMMENT '较小ID用户是否主动拉黑，0/1',
    high_blocks_low TINYINT NOT NULL DEFAULT 0 COMMENT '较大ID用户是否主动拉黑，0/1',
    PRIMARY KEY (low_id, high_id),
    CONSTRAINT chk_chat_contact_order CHECK (low_id > 0 AND high_id > low_id),
    CONSTRAINT chk_chat_contact_flags CHECK (low_blocks_high IN (0,1) AND high_blocks_low IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='双方拉黑与联系授权共用的事务锁';

CREATE TABLE chat_block (
    id VARCHAR(36) NOT NULL COMMENT '拉黑记录UUID',
    owner_id BIGINT NOT NULL COMMENT '主动拉黑者ID',
    target_id BIGINT NOT NULL COMMENT '被拉黑用户ID',
    target_handle VARCHAR(32) NOT NULL COMMENT '目标公开用户名快照',
    target_name VARCHAR(80) NOT NULL COMMENT '目标显示名称快照',
    created_at DATETIME(6) NOT NULL COMMENT '服务端创建时间Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_block_owner_target (owner_id, target_id),
    KEY idx_chat_block_owner_cursor (owner_id, id),
    CONSTRAINT chk_chat_block_not_self CHECK (owner_id > 0 AND target_id > 0 AND owner_id <> target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='仅主动用户可查询和解除的拉黑关系';

CREATE TABLE chat_report (
    id VARCHAR(36) NOT NULL COMMENT '举报UUID',
    reporter_id BIGINT NOT NULL COMMENT '举报人ID，仅审核员可见',
    message_id VARCHAR(36) NOT NULL COMMENT '被举报真实消息UUID',
    conversation_id VARCHAR(36) NOT NULL COMMENT '所属真实会话UUID',
    reported_user_id BIGINT NOT NULL COMMENT '被举报消息发送者ID',
    reason VARCHAR(20) NOT NULL COMMENT 'HARASSMENT/SPAM/THREAT/OTHER',
    detail VARCHAR(500) NOT NULL COMMENT '举报人说明，纯文本',
    evidence_body VARCHAR(2000) NOT NULL COMMENT '提交时消息正文快照，仅审核员可读取',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/REJECTED',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '人工审核乐观锁版本',
    review_note VARCHAR(500) NULL COMMENT '审核结论说明；未审核为空',
    reviewed_at DATETIME(6) NULL COMMENT '审核时间；未审核为空',
    created_at DATETIME(6) NOT NULL COMMENT '举报提交时间Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_report_reporter_message (reporter_id, message_id),
    KEY idx_chat_report_reporter_cursor (reporter_id, id),
    KEY idx_chat_report_reporter_time (reporter_id, created_at),
    KEY idx_chat_report_status_cursor (status, id),
    CONSTRAINT fk_chat_report_message FOREIGN KEY (message_id) REFERENCES chat_message(id),
    CONSTRAINT fk_chat_report_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id),
    CONSTRAINT chk_chat_report_status CHECK (status IN ('PENDING','RESOLVED','REJECTED')),
    CONSTRAINT chk_chat_report_reason CHECK (reason IN ('HARASSMENT','SPAM','THREAT','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='消息举报事实与不可对普通账号公开的证据';

CREATE TABLE chat_report_review (
    id VARCHAR(36) NOT NULL COMMENT '审核审计UUID',
    report_id VARCHAR(36) NOT NULL COMMENT '审核的举报UUID',
    reviewer_id BIGINT NOT NULL COMMENT '服务端认证的审核员ID',
    decision VARCHAR(16) NOT NULL COMMENT 'RESOLVED或REJECTED',
    note VARCHAR(500) NOT NULL COMMENT '人工审核说明，纯文本',
    created_at DATETIME(6) NOT NULL COMMENT '服务端审核时间Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_report_review_once (report_id),
    CONSTRAINT fk_chat_review_report FOREIGN KEY (report_id) REFERENCES chat_report(id),
    CONSTRAINT chk_chat_review_decision CHECK (decision IN ('RESOLVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='只追加的人工审核决定，不提供修改或删除接口';
