-- 仅记录新CONTROLLED房间的媒体授权计划；不转换LEGACY或开放媒体入口。
CREATE TABLE voice_media_binding (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id CHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    media_identity CHAR(36) NOT NULL,
    binding_state VARCHAR(16) NOT NULL,
    publish_desired BOOLEAN NOT NULL,
    seat_no INT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY(room_id,user_id),
    UNIQUE KEY uk_voice_media_identity(media_identity),
    KEY idx_voice_media_active(room_id,binding_state,user_id),
    CONSTRAINT fk_voice_media_member FOREIGN KEY(room_id,user_id) REFERENCES voice_room_member(room_id,user_id),
    CONSTRAINT chk_voice_media_generation CHECK(generation>0),
    CONSTRAINT chk_voice_media_state CHECK(binding_state IN ('ACTIVE','INACTIVE')),
    CONSTRAINT chk_voice_media_seat CHECK(seat_no IS NULL OR seat_no BETWEEN 1 AND 8),
    CONSTRAINT chk_voice_media_inactive CHECK(binding_state='ACTIVE' OR (publish_desired=FALSE AND seat_no IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每个旧轮次一个不可变退场目标；任务不授予新权限，旧执行不会删除新身份。
CREATE TABLE voice_media_retirement (
    id CHAR(36) NOT NULL,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    provider_room_name VARCHAR(120) NOT NULL,
    media_identity CHAR(36) NOT NULL,
    job_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_voice_retire_generation(room_id,user_id,generation),
    UNIQUE KEY uk_voice_retire_identity(media_identity),
    KEY idx_voice_retire_due(job_state,next_attempt_at,id),
    KEY idx_voice_retire_expired(job_state,lease_until,id),
    KEY idx_voice_retire_room(room_id,job_state,user_id),
    CONSTRAINT fk_voice_retire_room FOREIGN KEY(room_id) REFERENCES voice_room(id),
    CONSTRAINT chk_voice_retire_generation CHECK(generation>0),
    CONSTRAINT chk_voice_retire_state CHECK(job_state IN ('PENDING','PROCESSING','DONE','DEAD')),
    CONSTRAINT chk_voice_retire_attempts CHECK(attempts BETWEEN 0 AND 10),
    CONSTRAINT chk_voice_retire_lease CHECK(
        (job_state='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (job_state<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
