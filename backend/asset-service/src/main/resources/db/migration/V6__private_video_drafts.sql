-- 视频使用独立固定预留额度；失败、未知上传及旧任务产物在核验清理前仍占预算。
CREATE TABLE video_capacity (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    draft_count INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_video_capacity CHECK (draft_count >= 0)
) ENGINE=InnoDB;
INSERT INTO video_capacity(owner_id) VALUES (0);

CREATE TABLE video_draft (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_bytes BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    output_bytes BIGINT NULL,
    duration_millis BIGINT NULL,
    width INT NULL,
    height INT NULL,
    failure_code VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_video_original(owner_id,request_id),
    KEY idx_video_owner(owner_id,created_at,id),
    KEY idx_video_work(state,created_at,id),
    CONSTRAINT ck_video_state CHECK (state IN ('UPLOADING','QUEUED','PROCESSING','READY','FAILED')),
    CONSTRAINT ck_video_source CHECK (source_bytes BETWEEN 16 AND 33554432),
    CONSTRAINT ck_video_owner CHECK (owner_id > 0),
    CONSTRAINT ck_video_ready CHECK (state <> 'READY' OR
      (output_bytes BETWEEN 16 AND 67108864 AND output_bytes IS NOT NULL AND
       duration_millis BETWEEN 100 AND 180000 AND duration_millis IS NOT NULL AND
       width BETWEEN 2 AND 1280 AND width IS NOT NULL AND height BETWEEN 2 AND 720 AND height IS NOT NULL AND run_id IS NOT NULL))
) ENGINE=InnoDB;
