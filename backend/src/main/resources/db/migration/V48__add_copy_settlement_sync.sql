ALTER TABLE copy_simulation_session
    ADD COLUMN origin_at BIGINT NULL AFTER status;

UPDATE copy_simulation_session
SET origin_at = created_at
WHERE origin_at IS NULL;

ALTER TABLE copy_simulation_session
    MODIFY origin_at BIGINT NOT NULL;

CREATE TABLE copy_settlement_sync_cursor (
    leader_id BIGINT PRIMARY KEY,
    last_timestamp BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_copy_settlement_cursor_leader
        FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
);

CREATE TABLE copy_settlement_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    leader_id BIGINT NOT NULL,
    stable_event_key VARCHAR(180) NOT NULL,
    leader_trade_id VARCHAR(100) NOT NULL,
    action VARCHAR(10) NOT NULL,
    transaction_hash VARCHAR(100) NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NULL,
    amount DECIMAL(20,8) NOT NULL DEFAULT 0,
    event_time BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_copy_settlement_stable_event (stable_event_key),
    KEY idx_copy_settlement_pending (status, event_time),
    KEY idx_copy_settlement_leader_time (leader_id, event_time),
    CONSTRAINT fk_copy_settlement_event_leader
        FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
);
