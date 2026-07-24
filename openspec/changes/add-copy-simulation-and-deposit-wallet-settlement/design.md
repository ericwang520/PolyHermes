## Safety model

- `execution_mode=PAPER` 是硬隔离：不得解密私钥、检查 CLOB API key、调用下单或 Relayer。
- 数据迁移对已有记录使用 `LIVE`，避免升级后语义变化；UI 新建配置默认推荐 `PAPER`。
- `follow_onchain_actions=false` 为数据库和 DTO 默认值。MERGE/REDEEM 实盘必须同时满足 LIVE、显式 opt-in、Deposit Wallet、Builder 凭据可用。
- 模拟与实盘使用不同表，统计不得混合。

## Data model

- `copy_trading.execution_mode`: `LIVE` 或 `PAPER`。
- `copy_trading.follow_onchain_actions`: 是否允许实盘跟随 MERGE/REDEEM。
- `copy_simulation_session`: 每个 paper 配置的资金、PnL 和状态汇总。
- `copy_simulation_trade`: append-only 模拟事件，保存 leader event、动作、价格、数量、费用、状态和原因。
- `copy_simulation_position`: 按配置、market、outcome 保存数量、成本和估值。

## Execution

BUY/SELL 先复用现有金额计算与配置风控。PAPER 分支在任何账户凭据检查之前进入模拟服务。模拟成交价格默认使用 leader price，并明确标记 fill assumption，后续可升级为 orderbook quote。

MERGE 仅减少 follower 同一市场成对 outcome 的最小可用数量，并按每对 1 USDC 增加模拟现金。REDEEM 只在市场已结算且 follower 持有中奖 outcome 时结算；无法可靠判断结果时记录 SKIPPED，不猜测。

## Deposit Wallet batch

按官方协议：

1. `GET /v1/account/transactions/params?address=<signer>&type=WALLET` 获取新 nonce。
2. EIP-712 domain 为 `DepositWallet/1/137/<depositWallet>`。
3. `Call(address target,uint256 value,bytes data)` 逐项 hash；`Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)` 对 call hash 拼接后 hash。
4. 使用控制 Deposit Wallet 的 EOA 私钥签名 digest。
5. `POST /submit`，请求 `type=WALLET` 并包含 `depositWalletParams`。

每个 nonce 只提交一次。超时、INVALID/FAILED 和未知响应必须失败关闭，不能自动改走普通 EOA。

## Rollout

先发布 PAPER 与事件分类；Deposit Wallet merge/redeem 只开放手动账户赎回。自动跟随 on-chain action 保持关闭，待模拟证据与独立测试通过后再开放。
