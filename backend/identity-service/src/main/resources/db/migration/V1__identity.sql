CREATE TABLE identity_user (
    id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    handle VARCHAR(32) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    avatar_url VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_user_email (email),
    UNIQUE KEY uk_identity_user_handle (handle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE identity_discord_connection (
    user_id BIGINT NOT NULL,
    discord_user_id VARCHAR(32) NOT NULL,
    encrypted_refresh_token TEXT,
    connected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_discord_user (discord_user_id),
    CONSTRAINT fk_discord_identity_user FOREIGN KEY (user_id) REFERENCES identity_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
