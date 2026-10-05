-- 全状态引用核验不能使用仅以发布状态开头的索引；保留原公开查询索引。
ALTER TABLE creator_profile
    ADD KEY idx_creator_profile_avatar_reference (avatar_asset_id),
    ADD KEY idx_creator_profile_banner_reference (banner_asset_id);
