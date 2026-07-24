## Why

现有跟单配置只有实盘模式，用户无法在不承担真钱风险的情况下验证 leader 是否可复制。研究模块虽有 paper ledger，但它绑定研究候选，不能直接供普通跟单配置使用。

同时 Deposit Wallet 只支持 CLOB 下单；赎回、合并仍走旧 Safe/Proxy 路径或被拒绝。链上 fallback 还可能把 MERGE/REDEEM 误判为 SELL，导致错误跟单。

## What Changes

- 为普通跟单配置增加 `LIVE` / `PAPER` 执行模式，现有配置迁移后保持 `LIVE`。
- 新增独立模拟跟单账本、持仓和汇总接口；模拟模式绝不读取账户私钥或提交订单。
- 模拟 BUY/SELL 使用同一金额和风控配置，并记录虚拟成交、拒绝原因、持仓和已实现/未实现 PnL。
- 扩展 leader activity 类型，明确区分 TRADE、MERGE、REDEEM，禁止把结算行为当成普通 SELL。
- 为 Deposit Wallet 增加官方 Builder Relayer `WALLET` batch 执行能力，支持 merge/redeem calldata。
- 实盘 on-chain 跟随默认关闭，必须由配置显式启用，并受持仓数量、市场状态和 Builder 凭据检查约束。

## Impact

- 数据库新增 copy execution mode、on-chain opt-in 字段和模拟账本表。
- 后端新增模拟执行服务、查询接口、WALLET batch 编码与签名。
- 前端跟单表单新增执行模式与明显的实盘风险提示，列表展示模拟统计。
- 现有实盘配置行为保持不变；新功能不自动启用真钱 on-chain 操作。
