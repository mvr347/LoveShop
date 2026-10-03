#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
if [[ -f docs/patches/tz-complete.part1.b64 && -f docs/patches/tz-complete.part2.b64 ]]; then
  cat docs/patches/tz-complete.part1.b64 docs/patches/tz-complete.part2.b64 | tr -d '\n' | base64 -d > /tmp/tz-complete.decoded.patch
elif [[ -n "${TZ_PATCH:-}" && -f "$TZ_PATCH" ]]; then
  cp "$TZ_PATCH" /tmp/tz-complete.decoded.patch
else
  echo "Need docs/patches/tz-complete.part{1,2}.b64 or TZ_PATCH=file"; exit 1
fi
git apply --check /tmp/tz-complete.decoded.patch
git apply /tmp/tz-complete.decoded.patch
echo OK
echo "git add -A && git commit -m 'feat: bossbar commission PriceGui crate quality' && git push"
