-- 永久完成标记与保护删除同事务；不能因幂等重试/迟到 RPC 再创建已结束的保护。
-- 不使用资产外键，允许未来按正确退役协议删除资产，不复用请求 UUID 或按 TTL 删除此标记。
CREATE TABLE asset_binding_completion (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    completed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_asset_binding_completion_asset (asset_id),
    CONSTRAINT ck_asset_binding_completion_owner CHECK (owner_id > 0),
    CONSTRAINT ck_asset_binding_completion_purpose CHECK (purpose IN ('AVATAR', 'BANNER', 'POST_COVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
