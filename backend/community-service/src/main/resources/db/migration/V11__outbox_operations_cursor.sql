-- DEAD 复合游标页按创建时间/UUID 排序，不使用无限 offset 或客户端动态排序。
CREATE INDEX idx_outbox_operations_dead ON outbox_event(status,created_at,id);
