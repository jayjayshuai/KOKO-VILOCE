CREATE INDEX idx_media_asset_library
    ON media_asset (owner_id, purpose, status, created_at DESC, id DESC);
