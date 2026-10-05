CREATE TABLE media_asset (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    object_key VARCHAR(180) NOT NULL,
    content_type VARCHAR(30) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ready_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_asset_object_key (object_key),
    KEY idx_media_asset_owner_created (owner_id, created_at DESC),
    KEY idx_media_asset_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
