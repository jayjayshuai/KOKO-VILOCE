-- 仅本地领域事务提交后的任务可见；不把未知事务/已回滚事务伪装成可释放凭据。
CREATE TABLE asset_binding_release (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    asset_id CHAR(36) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_failure VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_binding_release_due (status, next_attempt_at, created_at),
    KEY idx_binding_release_lease (status, lease_until),
    KEY idx_binding_release_token (lease_token),
    CONSTRAINT ck_binding_release_owner CHECK (owner_id > 0),
    CONSTRAINT ck_binding_release_attempts CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT ck_binding_release_status CHECK (status IN ('PENDING','LEASED','SENT','DEAD')),
    CONSTRAINT ck_binding_release_purpose CHECK (purpose IN ('AVATAR','BANNER','POST_COVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
