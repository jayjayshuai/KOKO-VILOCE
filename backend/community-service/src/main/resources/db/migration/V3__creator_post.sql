CREATE TABLE creator_post (
    id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    slug VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    excerpt VARCHAR(300) NOT NULL DEFAULT '',
    body MEDIUMTEXT NOT NULL,
    cover_url VARCHAR(1000) NULL,
    kind VARCHAR(24) NOT NULL DEFAULT 'ARTICLE',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_creator_post_slug (slug),
    KEY idx_creator_post_owner_status (owner_id, status, updated_at),
    KEY idx_creator_post_discovery (status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
