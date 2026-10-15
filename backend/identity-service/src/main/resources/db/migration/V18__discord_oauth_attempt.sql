-- 所有新授权时间显式使用UTC；不改写历史connected_at或已有迁移。
ALTER TABLE identity_discord_connection
    ADD COLUMN discord_username VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN connection_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN connection_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE identity_discord_link_cursor (
    user_id BIGINT NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    active_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_discord_cursor_user FOREIGN KEY (user_id) REFERENCES identity_user(id),
    CONSTRAINT ck_discord_generation CHECK (generation >= 0)
) ENGINE=InnoDB;

CREATE TABLE identity_discord_oauth_attempt (
    user_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    website_scope CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    attempt_status VARCHAR(24) NOT NULL,
    code_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    encrypted_state TEXT NULL,
    encrypted_verifier TEXT NULL,
    encrypted_refresh_token TEXT NULL,
    discord_user_id VARCHAR(32) NULL,
    discord_username VARCHAR(80) NULL,
    failure_code VARCHAR(24) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, request_id),
    UNIQUE KEY uk_discord_attempt_state (state_digest),
    KEY idx_discord_attempt_rate (user_id, created_at),
    KEY idx_discord_attempt_retention (attempt_status, expires_at, user_id),
    CONSTRAINT fk_discord_attempt_user FOREIGN KEY (user_id) REFERENCES identity_user(id),
    CONSTRAINT ck_discord_attempt_generation CHECK (generation > 0),
    CONSTRAINT ck_discord_attempt_status CHECK (attempt_status IN
        ('WAITING','EXCHANGING','VERIFIED','BOUND','DENIED','UNKNOWN','SUPERSEDED','CONFLICT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 业务用例只追加；审计失败必须回滚同事务的领取/绑定，不能宣称不可篡改存储。
CREATE TABLE identity_discord_link_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_discord_audit_user (user_id, id),
    CONSTRAINT fk_discord_audit_attempt FOREIGN KEY (user_id, request_id)
        REFERENCES identity_discord_oauth_attempt(user_id, request_id)
) ENGINE=InnoDB;
