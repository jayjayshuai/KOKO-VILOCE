-- 固定状态索引服务 DEAD 游标和最旧年龄；不更新原任务状态/尝试次数。
ALTER TABLE asset_binding_release
    ADD INDEX idx_binding_release_status_created (status, created_at, request_id);
