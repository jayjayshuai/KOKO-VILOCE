-- 只新增排障索引与独立只读角色，不给任何账号赋权，不修改旧迁移或重置尝试次数。
ALTER TABLE asset_binding_release
    ADD INDEX idx_binding_release_status_created (status, created_at, request_id);

INSERT INTO ops_role (code, enabled) VALUES ('ASSET_BINDING_AUDITOR', TRUE);
INSERT INTO ops_role_permission (role_code, permission_code)
    VALUES ('ASSET_BINDING_AUDITOR', 'asset:binding:read');
