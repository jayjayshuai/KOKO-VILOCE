CREATE TABLE creator_profile (
    user_id BIGINT NOT NULL,
    slug VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    headline VARCHAR(120) NOT NULL DEFAULT '',
    bio VARCHAR(1000) NOT NULL DEFAULT '',
    avatar_url VARCHAR(1000),
    banner_url VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_creator_profile_slug (slug),
    KEY idx_creator_profile_discovery (status, updated_at),
    CONSTRAINT fk_creator_profile_user FOREIGN KEY (user_id) REFERENCES identity_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_creator_profile_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_creator_profile_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
