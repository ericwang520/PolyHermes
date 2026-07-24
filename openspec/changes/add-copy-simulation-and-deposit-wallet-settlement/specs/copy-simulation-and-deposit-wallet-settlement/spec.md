## ADDED Requirements

### Requirement: 普通跟单配置支持模拟执行
系统 SHALL 允许用户将普通跟单配置设为 PAPER，并使用独立模拟账本处理 leader 事件。

#### Scenario: 模拟配置收到交易
- **WHEN** 启用的 PAPER 配置收到可处理的 leader 交易
- **THEN** 系统 MUST 记录模拟结果，并 MUST NOT 解密私钥、提交 CLOB 订单或调用 Relayer

#### Scenario: 现有配置升级
- **WHEN** 数据库迁移已有跟单配置
- **THEN** 系统 MUST 将其 execution mode 设为 LIVE，保持原有语义

### Requirement: 模拟账本可审计
系统 SHALL 独立记录模拟现金、交易、持仓、过滤原因和 PnL。

#### Scenario: 模拟买卖
- **WHEN** PAPER 配置处理 BUY 或 SELL
- **THEN** 系统 MUST 幂等更新模拟现金和持仓，并保存 leader event id、fill assumption、数量、价格和 realized PnL

#### Scenario: 估值不可用
- **WHEN** 无法取得可靠市场价格
- **THEN** 系统 MUST 标记估值未知，不得把未知值计为零损益

### Requirement: 区分链上结算行为
系统 SHALL 区分 TRADE、MERGE 与 REDEEM。

#### Scenario: 检测到 merge
- **WHEN** 同一交易从钱包移出互补 outcome token 并收到 collateral
- **THEN** 系统 MUST 将事件标记为 MERGE，不得交给普通 SELL 跟单

#### Scenario: 事件类型不确定
- **WHEN** 链上证据不足以区分 SELL 与 REDEEM
- **THEN** 系统 MUST 标记或跳过不确定事件，不得猜测为 SELL

### Requirement: Deposit Wallet 使用 WALLET batch
系统 SHALL 通过 Builder Relayer WALLET batch 执行 Deposit Wallet 链上操作。

#### Scenario: 提交 batch
- **WHEN** 用户明确请求 Deposit Wallet merge 或 redeem 且 Builder 凭据有效
- **THEN** 系统 MUST 获取新 WALLET nonce、签名完整 batch、提交并返回可追踪交易 id/hash

#### Scenario: 缺少 Builder 凭据
- **WHEN** Deposit Wallet 链上操作缺少有效 Builder 凭据
- **THEN** 系统 MUST 失败关闭，且 MUST NOT 改走 EOA 或 Safe 路径

### Requirement: 实盘链上跟随默认关闭
系统 SHALL 默认禁止自动跟随 leader 的 MERGE/REDEEM。

#### Scenario: 未显式启用
- **WHEN** LIVE 配置收到 MERGE/REDEEM 且 follow_onchain_actions=false
- **THEN** 系统 MUST 记录跳过原因，不得提交链上交易
