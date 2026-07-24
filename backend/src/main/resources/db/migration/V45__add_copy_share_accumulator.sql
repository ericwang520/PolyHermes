CREATE TABLE copy_share_accumulator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NULL,
    token_id VARCHAR(100) NOT NULL,
    side VARCHAR(10) NOT NULL,
    pending_quantity DECIMAL(20,8) NOT NULL DEFAULT 0,
    pending_leader_quantity DECIMAL(20,8) NOT NULL DEFAULT 0,
    event_count INT NOT NULL DEFAULT 0,
    first_event_at BIGINT NOT NULL,
    last_event_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uk_copy_share_accumulator UNIQUE (copy_trading_id, token_id, side),
    CONSTRAINT fk_copy_share_accumulator_config
        FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE,
    INDEX idx_copy_share_accumulator_config (copy_trading_id),
    INDEX idx_copy_share_accumulator_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
