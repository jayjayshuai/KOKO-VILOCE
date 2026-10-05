CREATE TABLE voice_room (
    id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    owner_name VARCHAR(80) NOT NULL,
    slug VARCHAR(80) NOT NULL,
    title VARCHAR(120) NOT NULL,
    topic VARCHAR(300) NULL,
    status VARCHAR(24) NOT NULL,
    provider_room_name VARCHAR(120) NULL,
    max_participants INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_voice_room_slug (slug),
    UNIQUE KEY uk_voice_room_provider (provider_room_name),
    KEY idx_voice_room_discovery (status, created_at DESC),
    CONSTRAINT chk_voice_room_status CHECK (status IN ('PROVISIONING', 'OPEN', 'CLOSED', 'FAILED')),
    CONSTRAINT chk_voice_room_capacity CHECK (max_participants BETWEEN 2 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
