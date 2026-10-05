CREATE TABLE community (
    id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    badge VARCHAR(8) NOT NULL,
    member_count BIGINT NOT NULL DEFAULT 1,
    visibility VARCHAR(24) NOT NULL DEFAULT 'PUBLIC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_slug (slug),
    KEY idx_community_discovery (visibility, member_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_member (
    community_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (community_id, user_id),
    KEY idx_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_channel (
    id BIGINT NOT NULL,
    community_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    channel_type VARCHAR(24) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_name (community_id, name),
    KEY idx_channel_position (community_id, position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
