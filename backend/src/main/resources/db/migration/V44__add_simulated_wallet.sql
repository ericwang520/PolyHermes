ALTER TABLE wallet_accounts
    ADD COLUMN simulated_balance DECIMAL(20, 8) NULL
    COMMENT 'PAPER 模式模拟钱包的初始 USDC 余额';
