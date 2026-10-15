-- 原生私聊/群聊通话控制面，不创建公开voice_room；时间均显式UTC。
CREATE TABLE chat_call (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    initiator_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    end_reason VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL,
    ring_expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    active_conversation_id VARCHAR(36) GENERATED ALWAYS AS (CASE WHEN state IN ('RINGING','ACTIVE') THEN conversation_id ELSE NULL END) STORED,
    PRIMARY KEY(id),
    UNIQUE KEY uk_native_call_request(conversation_id,initiator_id,client_request_id),
    UNIQUE KEY uk_native_call_active_conversation(active_conversation_id),
    KEY idx_native_call_scan(state,id),
    CONSTRAINT fk_native_call_conversation FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id),
    CONSTRAINT ck_native_call_state CHECK(state IN ('RINGING','ACTIVE','ENDED')),
    CONSTRAINT ck_native_call_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE chat_call_participant (
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    state VARCHAR(16) NOT NULL,
    website_scope CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    media_identity CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_seen_at DATETIME(6) NULL,
    busy_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN state IN ('INVITED','ACCEPTED') THEN user_id ELSE NULL END) STORED,
    PRIMARY KEY(call_id,user_id),
    UNIQUE KEY uk_native_call_busy_user(busy_user_id),
    UNIQUE KEY uk_native_call_media_identity(media_identity),
    CONSTRAINT fk_native_call_participant FOREIGN KEY(call_id) REFERENCES chat_call(id),
    CONSTRAINT ck_native_call_participant_state CHECK(state IN ('INVITED','ACCEPTED','DECLINED','LEFT','CANCELLED','MISSED')),
    CONSTRAINT ck_native_call_accepted_scope CHECK(state <> 'ACCEPTED' OR (website_scope IS NOT NULL AND media_identity IS NOT NULL AND last_seen_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE chat_call_command (
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(call_id,actor_id,request_id),
    CONSTRAINT fk_native_call_command FOREIGN KEY(call_id) REFERENCES chat_call(id),
    CONSTRAINT ck_native_call_command CHECK(command_type IN ('ACCEPT','DECLINE','LEAVE','END'))
) ENGINE=InnoDB;

-- 媒体接入前不消费；结束/退场和命令收据同事务持久化，不靠浏览器主动断开。
CREATE TABLE chat_call_media_retirement (
    job_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    media_identity CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(job_key),
    KEY idx_native_call_retirement(status,created_at,job_key),
    CONSTRAINT fk_native_call_retirement FOREIGN KEY(call_id) REFERENCES chat_call(id),
    CONSTRAINT ck_native_call_retirement_action CHECK(action IN ('PARTICIPANT','ROOM'))
) ENGINE=InnoDB;
