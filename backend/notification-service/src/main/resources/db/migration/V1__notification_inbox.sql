CREATE TABLE notification_inbox (
    id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    recipient_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(80) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event_recipient (event_id, recipient_id),
    KEY idx_notification_recipient_created (recipient_id, created_at DESC, id DESC),
    KEY idx_notification_recipient_unread (recipient_id, read_at, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_preference (
    user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_event_receipt (
    event_id CHAR(36) NOT NULL,
    recipient_id BIGINT NOT NULL,
    disposition VARCHAR(16) NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id, recipient_id),
    KEY idx_notification_receipt_processed (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_fanout_job (
    event_id CHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    resource_id VARCHAR(80) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    follower_cursor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until DATETIME(6) NULL,
    claim_token CHAR(36) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (event_id),
    KEY idx_notification_fanout_ready (status, next_attempt_at, created_at),
    KEY idx_notification_fanout_lease (status, lease_until),
    KEY idx_notification_fanout_claim (claim_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
