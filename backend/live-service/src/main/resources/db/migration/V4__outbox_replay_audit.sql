-- 仅向前扩展；累计次数独立于每轮预算，重放代次永不回退。
ALTER TABLE outbox_event
    ADD COLUMN total_attempts BIGINT NOT NULL DEFAULT 0 COMMENT '含人工重放在内的累计投递尝试次数',
    ADD COLUMN replay_generation BIGINT NOT NULL DEFAULT 0 COMMENT '单调递增的人工重放代次',
    ADD CONSTRAINT chk_outbox_total_attempts CHECK (total_attempts >= 0),
    ADD CONSTRAINT chk_outbox_replay_generation CHECK (replay_generation >= 0);

UPDATE outbox_event SET total_attempts = attempts;

CREATE TABLE outbox_replay_audit (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者指定的幂等受理请求 UUID',
    event_id CHAR(36) NOT NULL COMMENT '原通知事件 ID，重放不改写载荷或标识',
    operator_id BIGINT NOT NULL COMMENT '服务端确认的操作账号 ID',
    expected_generation BIGINT NOT NULL COMMENT '操作者确认时看到的原代次',
    accepted_generation BIGINT NOT NULL COMMENT '本次受理后的单调代次',
    reason VARCHAR(500) NOT NULL COMMENT '重放原因或工单说明，不得填写密钥',
    previous_attempts INT NOT NULL COMMENT '原投递轮次耗尽的尝试次数',
    total_attempts_snapshot BIGINT NOT NULL COMMENT '受理前累计次数，重放不清零',
    previous_error VARCHAR(500) NULL COMMENT '受理前最后失败，保留定位历史',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库受理时间，Asia/Shanghai',
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_outbox_replay_generation (event_id, accepted_generation),
    KEY idx_outbox_replay_event (event_id, created_at, request_id),
    CONSTRAINT fk_outbox_replay_event FOREIGN KEY (event_id) REFERENCES outbox_event(id),
    CONSTRAINT chk_outbox_replay_actor CHECK (operator_id > 0),
    CONSTRAINT chk_outbox_replay_advance CHECK (accepted_generation = expected_generation + 1),
    CONSTRAINT chk_outbox_replay_snapshot CHECK (previous_attempts >= 0 AND total_attempts_snapshot >= previous_attempts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加的人工重放审计；应用无修改或删除接口';
