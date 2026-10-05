-- 新协议写域凭据；旧协议没有行不能据此推断结束，禁止回填假终态。
CREATE TABLE asset_binding_attempt (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变原绑定UUID',
    owner_id BIGINT NOT NULL COMMENT '资产所有者ID',
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原资产UUID',
    purpose VARCHAR(32) NOT NULL COMMENT '原用途AVATAR/BANNER/POST_COVER',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/COMMITTED/ABORTED不可逆',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '业务库创建时点',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '业务库状态变化时点',
    PRIMARY KEY (request_id),
    KEY idx_binding_attempt_open (status, created_at, request_id),
    CONSTRAINT ck_binding_attempt_owner CHECK (owner_id > 0),
    CONSTRAINT ck_binding_attempt_status CHECK (status IN ('OPEN','COMMITTED','ABORTED')),
    CONSTRAINT ck_binding_attempt_purpose CHECK (purpose IN ('AVATAR','BANNER','POST_COVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新协议绑定结果核对凭据；同行锁阻止迟到业务提交';

