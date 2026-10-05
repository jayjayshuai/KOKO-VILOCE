-- 离线首管理员审批仅允许一次；启动与迁移不会自动赋权。
CREATE TABLE ops_bootstrap_approval (
    id TINYINT NOT NULL COMMENT '固定单例 1，禁止作为重复自举或紧急恢复入口',
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联追加角色审计',
    command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实例/库/账号/标识/完整命令审批摘要，不是登录凭据',
    approved_handle VARCHAR(80) NOT NULL COMMENT '负责人确认时的账号标识快照',
    created_at DATETIME(6) NOT NULL COMMENT '数据库 UTC 显式转上海时间',
    PRIMARY KEY(id),
    UNIQUE KEY uk_ops_bootstrap_request(request_id),
    CONSTRAINT chk_ops_bootstrap_singleton CHECK(id=1),
    CONSTRAINT fk_ops_bootstrap_audit FOREIGN KEY(request_id) REFERENCES ops_role_change_audit(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
