# Caravan + Commission rework

## Lost caravan
- Without deposit: lot GUIs do not open during OPEN phase
- Zero participants at open → leave (`leave-if-empty`)
- Admin: `/loveshopsadmin caravan lost start [--force] [base|auction|secret]`
- Heads: `caravan-lost-*` in `heads.yml`

## Commission
- GUI already gui-gen-5 54; confirm uses CoinFormat
- Coin lines: `%img_*_coin%` + white `xN` (`CoinFormat`)
- Heads: `commission-*` in `heads.yml`

## Not in this PR
- Full BossBar service (flag `bossbar-enabled` reserved)
- Full drag-list rewrite of commission (partial existing flow)
- TradePointManager restore (separate)

## Build
`mvn -q -DskipTests package`
