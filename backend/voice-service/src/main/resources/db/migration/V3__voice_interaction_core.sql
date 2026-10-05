-- 仅新受控房间使用；已有房间保持LEGACY，不转换已签发的媒体权限。
-- 关闭意图先于媒体I/O，归属冻结；失败保留CLOSING，不能继续签入会凭据。
ALTER TABLE voice_room DROP CHECK chk_voice_room_status;
ALTER TABLE voice_room ADD CONSTRAINT chk_voice_room_status CHECK(status IN ('PROVISIONING','OPEN','CLOSING','CLOSED','FAILED'));
ALTER TABLE voice_room
    ADD COLUMN control_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN interaction_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_voice_control_mode CHECK (control_mode IN ('LEGACY','CONTROLLED')),
    ADD CONSTRAINT chk_voice_interaction_version CHECK (interaction_version >= 0);

CREATE TABLE voice_room_member (
    room_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    room_role VARCHAR(16) NOT NULL,
    member_state VARCHAR(16) NOT NULL,
    session_id CHAR(36) NOT NULL,
    lease_until DATETIME(3) NOT NULL,
    joined_at DATETIME(3) NOT NULL,
    owner_slot TINYINT GENERATED ALWAYS AS (CASE WHEN room_role='OWNER' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY(room_id,user_id), UNIQUE KEY uk_voice_one_owner(room_id,owner_slot),
    KEY idx_voice_active_member(room_id,member_state,lease_until),
    KEY idx_voice_member_role(room_id,room_role,user_id),
    CONSTRAINT fk_voice_member_room FOREIGN KEY(room_id) REFERENCES voice_room(id),
    CONSTRAINT chk_voice_member_role CHECK(room_role IN ('OWNER','ADMIN','LISTENER')),
    CONSTRAINT chk_voice_member_state CHECK(member_state IN ('ACTIVE','LEFT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE voice_seat (
    room_id BIGINT NOT NULL, seat_no INT NOT NULL,
    seat_state VARCHAR(16) NOT NULL DEFAULT 'EMPTY',
    user_id BIGINT NULL, session_id CHAR(36) NULL, request_id CHAR(36) NULL,
    muted BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(room_id,seat_no), UNIQUE KEY uk_voice_one_seat(room_id,user_id),
    CONSTRAINT fk_voice_seat_room FOREIGN KEY(room_id) REFERENCES voice_room(id),
    CONSTRAINT fk_voice_seat_member FOREIGN KEY(room_id,user_id) REFERENCES voice_room_member(room_id,user_id),
    CONSTRAINT chk_voice_seat_no CHECK(seat_no BETWEEN 1 AND 8),
    CONSTRAINT chk_voice_seat_state CHECK(seat_state IN ('EMPTY','LOCKED','RESERVED','ON_MIC')),
    CONSTRAINT chk_voice_seat_occupant CHECK(
        (seat_state IN ('EMPTY','LOCKED') AND user_id IS NULL AND session_id IS NULL AND request_id IS NULL)
        OR (seat_state='RESERVED' AND user_id IS NOT NULL AND session_id IS NOT NULL AND request_id IS NOT NULL)
        OR (seat_state='ON_MIC' AND user_id IS NOT NULL AND session_id IS NOT NULL AND request_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE voice_seat_request (
    id CHAR(36) NOT NULL, room_id BIGINT NOT NULL, seat_no INT NOT NULL,
    user_id BIGINT NOT NULL, session_id CHAR(36) NOT NULL,
    request_type VARCHAR(16) NOT NULL, request_state VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL,
    PRIMARY KEY(id), KEY idx_voice_pending_request(room_id,request_state,expires_at),
    CONSTRAINT fk_voice_request_seat FOREIGN KEY(room_id,seat_no) REFERENCES voice_seat(room_id,seat_no),
    CONSTRAINT chk_voice_request_type CHECK(request_type IN ('APPLY','INVITE')),
    CONSTRAINT chk_voice_request_state CHECK(request_state IN ('PENDING','ACCEPTED','REJECTED','CANCELLED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE voice_command_receipt (
    room_id BIGINT NOT NULL, user_id BIGINT NOT NULL, request_id CHAR(36) NOT NULL,
    fingerprint CHAR(64) NOT NULL, command_type VARCHAR(24) NOT NULL,
    result_version BIGINT NOT NULL, result_session_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY(room_id,user_id,request_id), KEY idx_voice_command_rate(room_id,user_id,created_at),
    CONSTRAINT fk_voice_receipt_room FOREIGN KEY(room_id) REFERENCES voice_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE voice_room_action (
    id CHAR(36) NOT NULL, room_id BIGINT NOT NULL, actor_id BIGINT NULL,
    command_type VARCHAR(24) NOT NULL, result_version BIGINT NOT NULL,
    target_user_id BIGINT NULL, seat_no INT NULL, seat_request_id CHAR(36) NULL, desired_value BOOLEAN NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY(id), UNIQUE KEY idx_voice_action_cursor(room_id,result_version),
    CONSTRAINT fk_voice_action_room FOREIGN KEY(room_id) REFERENCES voice_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
