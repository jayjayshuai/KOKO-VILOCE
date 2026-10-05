CREATE TABLE asset_usage (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    image_count BIGINT NOT NULL DEFAULT 0,
    byte_size BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_asset_usage_count CHECK (image_count >= 0),
    CONSTRAINT ck_asset_usage_bytes CHECK (byte_size >= 0)
) ENGINE=InnoDB;

-- Account for all existing intents, including failed uploads awaiting reconciliation.
INSERT INTO asset_usage (owner_id, image_count, byte_size)
    SELECT owner_id, COUNT(*), COALESCE(SUM(byte_size), 0) FROM media_asset GROUP BY owner_id;
-- owner 0 represents the KOKO bucket budget, never an identity account.
INSERT INTO asset_usage (owner_id, image_count, byte_size)
    SELECT 0, COUNT(*), COALESCE(SUM(byte_size), 0) FROM media_asset;
