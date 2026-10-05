CREATE INDEX idx_notification_fanout_status_created
    ON notification_fanout_job (status, created_at);
