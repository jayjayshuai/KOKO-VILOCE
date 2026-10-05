-- 新增有界人工代次，原 attempts 永久累计；禁止混合旧 worker 开启恢复。
ALTER TABLE asset_binding_release
 DROP CHECK ck_binding_release_attempts,
 ADD COLUMN replay_generation INT NOT NULL DEFAULT 0,
 ADD COLUMN generation_attempts INT NOT NULL DEFAULT 0;
UPDATE asset_binding_release SET generation_attempts=attempts;
ALTER TABLE asset_binding_release
 ADD CONSTRAINT ck_binding_release_attempts CHECK (attempts BETWEEN 0 AND 110),
 ADD CONSTRAINT ck_binding_release_generation CHECK (replay_generation BETWEEN 0 AND 10),
 ADD CONSTRAINT ck_binding_release_generation_attempts CHECK (generation_attempts BETWEEN 0 AND 10),
 ADD CONSTRAINT ck_binding_release_cumulative CHECK
  (attempts >= generation_attempts AND attempts <= (replay_generation+1)*10);

-- 原绑定存在且已提交才可排队；审计与重排同事务，单命令/单代次唯一。
CREATE TABLE asset_binding_release_audit (
 command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operator_id BIGINT NOT NULL,
 expected_generation INT NOT NULL,
 accepted_generation INT NOT NULL,
 previous_attempts INT NOT NULL,
 previous_generation_attempts INT NOT NULL,
 previous_failure VARCHAR(40) NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(command_id),
 UNIQUE KEY uk_binding_release_audit_generation(request_id,accepted_generation),
 CONSTRAINT fk_binding_release_audit_request FOREIGN KEY(request_id) REFERENCES asset_binding_release(request_id),
 CONSTRAINT ck_binding_release_audit_actor CHECK(operator_id>0),
 CONSTRAINT ck_binding_release_audit_generation CHECK(expected_generation BETWEEN 0 AND 9 AND accepted_generation=expected_generation+1),
 CONSTRAINT ck_binding_release_audit_attempts CHECK(previous_attempts BETWEEN 0 AND 110 AND previous_generation_attempts BETWEEN 0 AND 10),
 CONSTRAINT ck_binding_release_audit_reason CHECK(CHAR_LENGTH(reason) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
