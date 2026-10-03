#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
base64 -d < docs/patches/tz-remaining-bossbar-commission.patch.b64 > /tmp/tz-remaining.decoded.patch
git apply --check /tmp/tz-remaining.decoded.patch
git apply /tmp/tz-remaining.decoded.patch
echo "OK: bossbar, commission PriceGui+drag, crate quality, CoinFormat white, lang prefixes, BARAHOLKA plan"
echo "  git add -A && git commit -m 'feat: caravan bossbar, commission Duels price, crate quality' && git push"
# or: python3 scripts/git-database-push-caravan.py after extending FILES
