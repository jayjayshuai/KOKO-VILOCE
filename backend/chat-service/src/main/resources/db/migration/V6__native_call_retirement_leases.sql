-- 网络清退在事务外执行；租约及确认CAS避免旧工作轮次覆盖新轮次。
ALTER TABLE chat_call_media_retirement
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN next_attempt_at DATETIME(6) NULL,
    ADD COLUMN confirmed_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_native_retirement_status CHECK(status IN ('READY','PROCESSING','DONE','DEAD')),
    ADD CONSTRAINT ck_native_retirement_attempts CHECK(attempts BETWEEN 0 AND 10);
CREATE INDEX idx_native_retirement_due ON chat_call_media_retirement(status,next_attempt_at,created_at,job_key);
