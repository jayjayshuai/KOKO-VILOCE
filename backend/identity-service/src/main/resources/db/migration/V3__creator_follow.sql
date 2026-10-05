ALTER TABLE creator_profile
    ADD COLUMN follower_count BIGINT NOT NULL DEFAULT 0 AFTER version;

CREATE TABLE creator_follow (
    creator_id BIGINT NOT NULL,
    follower_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (creator_id, follower_id),
    KEY idx_creator_follow_follower (follower_id, created_at),
    CONSTRAINT fk_creator_follow_profile FOREIGN KEY (creator_id) REFERENCES creator_profile(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_creator_follow_user FOREIGN KEY (follower_id) REFERENCES identity_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_creator_follow_not_self CHECK (creator_id <> follower_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
