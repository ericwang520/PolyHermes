# Deposit Wallet / POLY_1271 support

This fork adds the new Polymarket Deposit Wallet order flow while keeping the
existing Magic Proxy and Gnosis Safe flows intact.

## Implemented

- Discovers `proxyWallet` through `GET https://gamma-api.polymarket.com/public-profile`.
- Classifies it as a Deposit Wallet only when it differs from the legacy Magic
  and Safe addresses derived for the same owner EOA.
- Queries pUSD balance and positions at the discovered wallet address.
- Uses `signatureType = 3` (`POLY_1271`).
- Sets both order `maker` and order `signer` to the Deposit Wallet address.
- Produces the ERC-7739 wrapped order signature used by the official
  `@polymarket/clob-client-v2`.
- Refreshes the CLOB collateral balance cache with `signature_type=3` after
  account import.

The signature implementation is covered by a deterministic, byte-for-byte test
vector generated with `@polymarket/clob-client-v2@1.1.0`.

## Not implemented yet

Deposit Wallet on-chain actions require Builder Relayer `WALLET` batches. The
following actions deliberately return an unsupported-operation error instead of
incorrectly treating the wallet as a legacy Safe:

- token approvals
- USDC.e to pUSD wrapping
- redeem and merge operations
- WCOL unwrap

Complete initial funding and approvals on polymarket.com before importing the
account. CLOB buy and sell orders are the supported scope of this patch.

## Production image override

`docker-compose.prod.yml` now accepts an image override:

```bash
POLYHERMES_IMAGE=your-registry/polyhermes:deposit-wallet \
  docker compose -f docker-compose.prod.yml up -d
```

Keep copy trading disabled until a manual order with a small amount has been
accepted and filled successfully.
