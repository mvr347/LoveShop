#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# Prefer full single b64; else concat parts; else local path if provided
if [[ -f docs/patches/tz-remaining-bossbar-commission.patch.b64 ]] && [[ $(wc -c < docs/patches/tz-remaining-bossbar-commission.patch.b64) -gt 100 ]]; then
  # skip PLACEHOLDER
  if ! grep -q PLACEHOLDER docs/patches/tz-remaining-bossbar-commission.patch.b64 2>/dev/null; then
    base64 -d < docs/patches/tz-remaining-bossbar-commission.patch.b64 > /tmp/tz-remaining.decoded.patch
  fi
fi
if [[ ! -s /tmp/tz-remaining.decoded.patch ]] && [[ -f docs/patches/tz-remaining.part1.b64 && -f docs/patches/tz-remaining.part2.b64 ]]; then
  cat docs/patches/tz-remaining.part1.b64 docs/patches/tz-remaining.part2.b64 | tr -d '\n' | base64 -d > /tmp/tz-remaining.decoded.patch
fi
if [[ ! -s /tmp/tz-remaining.decoded.patch ]] && [[ -n "${TZ_PATCH:-}" && -f "$TZ_PATCH" ]]; then
  cp "$TZ_PATCH" /tmp/tz-remaining.decoded.patch
fi
if [[ ! -s /tmp/tz-remaining.decoded.patch ]]; then
  echo "No patch payload. Set TZ_PATCH=/path/to/tz-remaining.patch or restore b64 parts."
  exit 1
fi
git apply --check /tmp/tz-remaining.decoded.patch
git apply /tmp/tz-remaining.decoded.patch
echo "OK: bossbar, commission PriceGui+drag, crate quality, CoinFormat, lang"
echo "  git add -A && git commit -m 'feat: caravan bossbar + commission Duels price + crate quality' && git push"
