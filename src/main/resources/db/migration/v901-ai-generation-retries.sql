-- One next attempt per generation. Old outputs remain queryable without appearing in conversation history.
CREATE TABLE IF NOT EXISTS ai_generation_retries (
    source_generation_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_generation_id),
    UNIQUE KEY uk_retry_generation (generation_id),
    CONSTRAINT fk_retry_source FOREIGN KEY (source_generation_id) REFERENCES ai_generation_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_retry_generation FOREIGN KEY (generation_id) REFERENCES ai_generation_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
