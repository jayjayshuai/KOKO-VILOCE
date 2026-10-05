-- 仅定义能力与角色，不给任何现有/新注册账号赋权。
ALTER TABLE identity_user
    ADD COLUMN operations_version BIGINT NOT NULL DEFAULT 0 COMMENT '运营授权版本，角色变更必须递增',
    ADD CONSTRAINT chk_identity_operations_version CHECK (operations_version >= 0);

CREATE TABLE ops_role (
    code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端固定角色代码',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '角色启用状态'
) ENGINE=InnoDB;
CREATE TABLE ops_role_permission (
    role_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色代码',
    permission_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端能力代码',
    PRIMARY KEY(role_code, permission_code),
    CONSTRAINT fk_ops_role_permission FOREIGN KEY(role_code) REFERENCES ops_role(code)
) ENGINE=InnoDB;
CREATE TABLE ops_user_role (
    user_id BIGINT NOT NULL COMMENT '明确授权的现有账号，不按注册顺序初始化',
    role_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端角色',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE 或 REVOKED',
    version BIGINT NOT NULL COMMENT '关系单调版本，撤销后保留',
    expires_at DATETIME(6) NULL COMMENT '可空到期时间，Asia/Shanghai',
    PRIMARY KEY(user_id, role_code),
    CONSTRAINT fk_ops_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT fk_ops_user_role FOREIGN KEY(role_code) REFERENCES ops_role(code),
    CONSTRAINT chk_ops_user_role_status CHECK(status IN ('ACTIVE','REVOKED')),
    CONSTRAINT chk_ops_user_role_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ops_authority_guard (
    id INT PRIMARY KEY COMMENT '运营角色变更串行 guard，仅固定 1'
) ENGINE=InnoDB;
INSERT INTO ops_authority_guard VALUES (1);
INSERT INTO ops_role VALUES ('OPERATIONS_ADMIN',TRUE),('NOTIFICATION_OPERATOR',TRUE),('NOTIFICATION_AUDITOR',TRUE);
INSERT INTO ops_role_permission VALUES
    ('OPERATIONS_ADMIN','operations:roles:manage'),
    ('OPERATIONS_ADMIN','notification:outbox:read'),
    ('OPERATIONS_ADMIN','notification:outbox:replay'),
    ('NOTIFICATION_OPERATOR','notification:outbox:read'),
    ('NOTIFICATION_OPERATOR','notification:outbox:replay'),
    ('NOTIFICATION_AUDITOR','notification:outbox:read');

CREATE TABLE ops_confirmation_rate (
    user_id BIGINT PRIMARY KEY COMMENT '二次确认限速账号',
    window_started_at DATETIME(6) NOT NULL COMMENT '数据库一分钟窗口起点',
    attempts INT NOT NULL COMMENT '包括密码错误的尝试，独立事务提交',
    CONSTRAINT fk_ops_rate_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT chk_ops_confirmation_attempts CHECK(attempts BETWEEN 1 AND 5)
) ENGINE=InnoDB;
CREATE TABLE ops_replay_confirmation (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '随机秘密凭据 SHA256，不存原始值',
    user_id BIGINT NOT NULL COMMENT '确认账号 ID',
    session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证网关生成的会话摘要',
    command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整域与命令绑定摘要',
    authority_version BIGINT NOT NULL COMMENT '确认时账号运营授权版本',
    credential_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '确认时密码摘要的 SHA256',
    created_at DATETIME(6) NOT NULL COMMENT '数据库 UTC 时钟显式转上海时间，不依赖 session 时区',
    expires_at DATETIME(6) NOT NULL COMMENT '五分钟到期，数据库时钟',
    KEY idx_ops_confirmation_expiry(expires_at),
    CONSTRAINT fk_ops_confirmation_user FOREIGN KEY(user_id) REFERENCES identity_user(id)
) ENGINE=InnoDB;
