-- 独立资产恢复角色，不给真实账号赋权，不扩大通知/管理员现有权限。
INSERT INTO ops_role(code,enabled) VALUES('ASSET_BINDING_RECOVERY',TRUE);
INSERT INTO ops_role_permission(role_code,permission_code) VALUES
 ('ASSET_BINDING_RECOVERY','asset:binding:read'),('ASSET_BINDING_RECOVERY','asset:binding:replay');
-- 与通知确认表隔离；到期使用身份授权既有显式上海时钟，不保存明文密码或令牌。
CREATE TABLE ops_binding_release_confirmation (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL,
 session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 authority_version BIGINT NOT NULL,
 credential_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 PRIMARY KEY(token_hash), KEY idx_binding_confirmation_expiry(expires_at,token_hash),
 CONSTRAINT fk_binding_confirmation_user FOREIGN KEY(user_id) REFERENCES identity_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
