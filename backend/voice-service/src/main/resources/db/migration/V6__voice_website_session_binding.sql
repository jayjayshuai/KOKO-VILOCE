-- 只保存不可登录的网站会话摘要；NULL历史绑定在可信签发前不能进入受控媒体。
ALTER TABLE voice_media_binding
    ADD COLUMN website_session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '网站高熵会话的域隔离摘要，不是令牌',
    ADD INDEX idx_voice_website_binding(user_id,website_session_hash,binding_state,room_id);
