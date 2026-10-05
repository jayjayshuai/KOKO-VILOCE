-- 绑定意图与 READY -> RETIRING 必须竞争同一 media_asset 行锁。
-- 领取 RPC 或调用者提交结果未知时保留意图，不能按时间到期直接删除。
CREATE TABLE asset_binding_intent (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_asset_binding_intent_asset (asset_id),
    KEY idx_asset_binding_intent_created (created_at, request_id),
    CONSTRAINT fk_asset_binding_intent_asset FOREIGN KEY (asset_id) REFERENCES media_asset (id),
    CONSTRAINT ck_asset_binding_intent_owner CHECK (owner_id > 0),
    CONSTRAINT ck_asset_binding_intent_purpose CHECK (purpose IN ('AVATAR', 'BANNER', 'POST_COVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
