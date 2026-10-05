ALTER TABLE community
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' AFTER visibility,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at,
    ADD CONSTRAINT chk_community_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT chk_community_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

ALTER TABLE community
    DROP INDEX idx_community_discovery,
    ADD KEY idx_community_discovery (status, visibility, member_count DESC),
    ADD KEY idx_community_owner (owner_id, status, updated_at DESC);
