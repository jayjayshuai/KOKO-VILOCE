CREATE INDEX idx_outbox_status_created ON outbox_event (status, created_at);
