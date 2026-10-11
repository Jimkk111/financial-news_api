-- Durable database queue: committing a queued row is also committing its dispatch intent.
CREATE TABLE IF NOT EXISTS ai_generation_tasks (
    id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id INT NOT NULL,
    session_id INT NOT NULL,
    public_session_id VARCHAR(50) NOT NULL,
    message_id INT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_json JSON NOT NULL,
    web_search BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL DEFAULT 'queued',
    last_seq BIGINT NOT NULL DEFAULT 0,
    worker_token VARCHAR(50) DEFAULT NULL,
    lease_until DATETIME DEFAULT NULL,
    cancel_requested_at DATETIME DEFAULT NULL,
    error_code VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_request (user_id, idempotency_key),
    UNIQUE KEY uk_generation_message (message_id),
    KEY idx_generation_dispatch (status, created_at),
    KEY idx_generation_lease (status, lease_until),
    KEY idx_generation_session (session_id, status),
    CONSTRAINT fk_generation_session FOREIGN KEY (session_id) REFERENCES ai_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_message FOREIGN KEY (message_id) REFERENCES ai_messages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_generation_events (
    generation_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    type VARCHAR(24) NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, seq),
    CONSTRAINT fk_generation_event FOREIGN KEY (generation_id) REFERENCES ai_generation_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
