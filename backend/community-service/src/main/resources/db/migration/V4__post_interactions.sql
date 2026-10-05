ALTER TABLE creator_post
    ADD COLUMN like_count BIGINT NOT NULL DEFAULT 0 AFTER version,
    ADD COLUMN comment_count BIGINT NOT NULL DEFAULT 0 AFTER like_count;

CREATE TABLE creator_post_like (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (post_id, user_id),
    KEY idx_post_like_user (user_id, created_at),
    CONSTRAINT fk_post_like_post FOREIGN KEY (post_id) REFERENCES creator_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE creator_post_comment (
    id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    body VARCHAR(2000) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_post_comment_post (post_id, status, created_at),
    KEY idx_post_comment_user (user_id, status, created_at),
    CONSTRAINT fk_post_comment_post FOREIGN KEY (post_id) REFERENCES creator_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
