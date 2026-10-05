ALTER TABLE creator_post
    ADD COLUMN cover_asset_id CHAR(36) NULL AFTER cover_url,
    ADD KEY idx_creator_post_public_cover (status, cover_asset_id);
