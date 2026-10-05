ALTER TABLE creator_profile
    ADD COLUMN avatar_asset_id CHAR(36) NULL AFTER avatar_url,
    ADD COLUMN banner_asset_id CHAR(36) NULL AFTER banner_url,
    ADD KEY idx_creator_profile_public_avatar (status, avatar_asset_id),
    ADD KEY idx_creator_profile_public_banner (status, banner_asset_id);
