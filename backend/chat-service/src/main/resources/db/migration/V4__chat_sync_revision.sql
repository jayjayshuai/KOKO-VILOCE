-- 用户级持久同步版本：与业务事实同事务提交，不是逐消息推送记录。
-- 不建全局热点行，不存正文/令牌；每次群消息最多影响 50 个现有成员。
CREATE TABLE chat_sync_revision (
    user_id BIGINT NOT NULL COMMENT '受影响用户 ID，非设备 ID',
    revision BIGINT NOT NULL COMMENT '单调提交版本；允许通知合并',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近登记时间，Asia/Shanghai',
    PRIMARY KEY (user_id),
    CONSTRAINT chk_chat_sync_user CHECK (user_id > 0),
    CONSTRAINT chk_chat_sync_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
