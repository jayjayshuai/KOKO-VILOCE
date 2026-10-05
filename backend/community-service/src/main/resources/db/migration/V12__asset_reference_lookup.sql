-- 草稿/归档引用核验以资产 ID 查找，不扫描所有文章。
ALTER TABLE creator_post
    ADD KEY idx_creator_post_cover_reference (cover_asset_id);
