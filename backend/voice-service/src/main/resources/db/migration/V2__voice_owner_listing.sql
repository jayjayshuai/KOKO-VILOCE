-- 本人房间ID降序游标；等值房主后反向扫描ID，不扫描其他人的历史。
ALTER TABLE voice_room ADD INDEX idx_voice_owner_cursor (owner_id, id);
