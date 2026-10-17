-- 未登记旧PUT不能被推断为不存在；开启删除前须升级全部写入节点并排空旧节点。
ALTER TABLE video_draft
    DROP CHECK ck_video_state,
    ADD CONSTRAINT ck_video_state CHECK (state IN ('UPLOADING','QUEUED','PROCESSING','READY','FAILED','DELETING','DELETED')),
    ADD COLUMN write_tracking BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN processing_settled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN delete_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN delete_requested_at DATETIME(6) NULL,
    ADD COLUMN delete_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN delete_lease_until DATETIME(6) NULL,
    ADD COLUMN delete_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN delete_next_at DATETIME(6) NULL,
    ADD COLUMN delete_error VARCHAR(32) NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN active_owner_id BIGINT GENERATED ALWAYS AS (CASE WHEN state <> 'DELETED' THEN owner_id ELSE NULL END) STORED,
    ADD KEY idx_video_active_owner(active_owner_id,created_at,id),
    ADD KEY idx_video_deletion(state,delete_next_at,id),
    ADD CONSTRAINT ck_video_delete_attempts CHECK (delete_attempts BETWEEN 0 AND 5),
    ADD CONSTRAINT ck_video_deleted CHECK (state <> 'DELETED' OR (deleted_at IS NOT NULL AND delete_request_id IS NOT NULL));

CREATE TABLE video_object_write (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(8) NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(12) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6) NULL,
    UNIQUE KEY uk_video_write_kind(video_id,kind),
    KEY idx_video_write_pending(video_id,state),
    CONSTRAINT fk_video_write FOREIGN KEY (video_id) REFERENCES video_draft(id),
    CONSTRAINT ck_video_write_kind CHECK (kind IN ('SOURCE','OUTPUT')),
    CONSTRAINT ck_video_write_state CHECK (state IN ('WRITING','SETTLED','UNCERTAIN'))
) ENGINE=InnoDB;
