ALTER TABLE creator_post
    ADD COLUMN favorite_count BIGINT NOT NULL DEFAULT 0 AFTER comment_count;

CREATE TABLE creator_post_favorite (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (post_id, user_id),
    KEY idx_post_favorite_user (user_id, created_at),
    CONSTRAINT fk_post_favorite_post FOREIGN KEY (post_id) REFERENCES creator_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
