ALTER TABLE copy_trading
    ADD COLUMN execution_mode VARCHAR(10) NOT NULL DEFAULT 'LIVE' COMMENT 'LIVE=实盘，PAPER=模拟',
    ADD COLUMN follow_onchain_actions BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否允许实盘跟随 MERGE/REDEEM',
    ADD COLUMN paper_initial_balance DECIMAL(20,8) NOT NULL DEFAULT 1000 COMMENT '模拟跟单初始 USDC';

CREATE TABLE copy_simulation_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    copy_trading_id BIGINT NOT NULL,
    initial_cash DECIMAL(20,8) NOT NULL,
    cash_balance DECIMAL(20,8) NOT NULL,
    realized_pnl DECIMAL(20,8) NOT NULL DEFAULT 0,
    total_fees DECIMAL(20,8) NOT NULL DEFAULT 0,
    trade_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_copy_simulation_session_config (copy_trading_id),
    CONSTRAINT fk_copy_simulation_session_config
        FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
);

CREATE TABLE copy_simulation_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    copy_trading_id BIGINT NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NOT NULL,
    token_id VARCHAR(100) NULL,
    quantity DECIMAL(20,8) NOT NULL DEFAULT 0,
    leader_quantity DECIMAL(20,8) NOT NULL DEFAULT 0,
    average_cost DECIMAL(20,8) NOT NULL DEFAULT 0,
    realized_pnl DECIMAL(20,8) NOT NULL DEFAULT 0,
    last_price DECIMAL(20,8) NULL,
    valuation_status VARCHAR(20) NOT NULL DEFAULT 'LEADER_PRICE',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_copy_simulation_position (copy_trading_id, market_id, outcome_index),
    KEY idx_copy_simulation_position_session (session_id),
    CONSTRAINT fk_copy_simulation_position_session
        FOREIGN KEY (session_id) REFERENCES copy_simulation_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_copy_simulation_position_config
        FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
);

CREATE TABLE copy_simulation_trade (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    copy_trading_id BIGINT NOT NULL,
    leader_trade_id VARCHAR(120) NOT NULL,
    action VARCHAR(20) NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    outcome_index INT NULL,
    token_id VARCHAR(100) NULL,
    price DECIMAL(20,8) NULL,
    quantity DECIMAL(20,8) NOT NULL DEFAULT 0,
    notional DECIMAL(20,8) NOT NULL DEFAULT 0,
    fee DECIMAL(20,8) NOT NULL DEFAULT 0,
    realized_pnl DECIMAL(20,8) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NULL,
    fill_assumption VARCHAR(30) NOT NULL DEFAULT 'LEADER_PRICE',
    event_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_copy_simulation_trade_event (copy_trading_id, leader_trade_id, action),
    KEY idx_copy_simulation_trade_session_time (session_id, event_time),
    CONSTRAINT fk_copy_simulation_trade_session
        FOREIGN KEY (session_id) REFERENCES copy_simulation_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_copy_simulation_trade_config
        FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
);
