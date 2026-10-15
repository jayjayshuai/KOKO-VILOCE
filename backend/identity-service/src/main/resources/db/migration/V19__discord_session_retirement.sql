-- 网关后续须在注销完成前持久登记本域会话退役；不存网站令牌原文。
CREATE TABLE identity_discord_session_retirement (
    user_id BIGINT NOT NULL,
    website_scope CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retired_at DATETIME(6) NOT NULL,
    PRIMARY KEY(user_id, website_scope),
    KEY idx_discord_session_retention(retired_at,user_id),
    CONSTRAINT fk_discord_session_user FOREIGN KEY (user_id) REFERENCES identity_user(id)
) ENGINE=InnoDB;
