CREATE TABLE IF NOT EXISTS quote_provider_symbol (
    provider VARCHAR(24) NOT NULL,
    symbol VARCHAR(24) NOT NULL,
    sec_type TINYINT NOT NULL,
    region VARCHAR(16) NOT NULL,
    code VARCHAR(32) NOT NULL,
    `exchange` VARCHAR(32) NOT NULL,
    PRIMARY KEY (provider, symbol, sec_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
