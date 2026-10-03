#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
if [[ -f docs/patches/tz-remaining.part1.b64 && -f docs/patches/tz-remaining.part2.b64 ]]; then
  cat docs/patches/tz-remaining.part1.b64 docs/patches/tz-remaining.part2.b64 | base64 -d > /tmp/tz-remaining.decoded.patch
elif [[ -f docs/patches/tz-remaining-bossbar-commission.patch.b64 ]]; then
  base64 -d < docs/patches/tz-remaining-bossbar-commission.patch.b64 > /tmp/tz-remaining.decoded.patch
else
  echo "missing patch payload"; exit 1
fi
git apply --check /tmp/tz-remaining.decoded.patch
git apply /tmp/tz-remaining.decoded.patch
echo "OK applied TZ remaining (bossbar, commission PriceGui, crate quality)"
echo "  git add -A && git commit -m 'feat: caravan bossbar + commission Duels price + crate quality' && git push"
