ALTER TABLE copy_trading
    ADD COLUMN use_fak_for_small_orders BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '零碎订单按 1 USDC 门槛使用 FAK，而不是等待市场最低 shares';
