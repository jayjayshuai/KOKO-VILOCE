CREATE TABLE ops_role_change_audit (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '幂等操作 UUID',
    operator_id BIGINT NULL COMMENT '认证操作账号，SERVER_BOOTSTRAP 时空',
    source VARCHAR(24) NOT NULL COMMENT 'OPERATOR 或 SERVER_BOOTSTRAP，初始化必须有明确授权',
    user_id BIGINT NOT NULL COMMENT '目标账号',
    role_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定角色',
    expected_version BIGINT NOT NULL COMMENT '原确认版本',
    accepted_version BIGINT NOT NULL COMMENT '新关系版本',
    previous_status VARCHAR(16) NOT NULL COMMENT 'NONE/ACTIVE/REVOKED',
    accepted_status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/REVOKED',
    previous_expires_at DATETIME(6) NULL COMMENT '原可空到期时间',
    expires_at DATETIME(6) NULL COMMENT '本次可空到期时间',
    reason VARCHAR(500) NOT NULL COMMENT '审批原因，不填密钥',
    created_at DATETIME(6) NOT NULL COMMENT '数据库 UTC 时钟显式转上海受理时间',
    KEY idx_ops_role_audit_user(user_id,created_at,request_id),
    UNIQUE KEY uk_ops_role_audit_version(user_id,role_code,accepted_version),
    CONSTRAINT fk_ops_role_audit_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT fk_ops_role_audit_operator FOREIGN KEY(operator_id) REFERENCES identity_user(id),
    CONSTRAINT fk_ops_role_audit_role FOREIGN KEY(role_code) REFERENCES ops_role(code),
    CONSTRAINT chk_ops_role_audit_version CHECK(accepted_version = expected_version + 1),
    CONSTRAINT chk_ops_role_audit_source CHECK((source='OPERATOR' AND operator_id IS NOT NULL)
        OR (source='SERVER_BOOTSTRAP' AND operator_id IS NULL)),
    CONSTRAINT chk_ops_role_audit_state CHECK(previous_status IN ('NONE','ACTIVE','REVOKED') AND accepted_status IN ('ACTIVE','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运营角色只追加审计，应用无更新删除入口';
