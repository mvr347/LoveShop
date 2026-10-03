#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
if [[ -f docs/patches/APPLY_ME.part1.b64 && -f docs/patches/APPLY_ME.part2.b64 ]]; then
  cat docs/patches/APPLY_ME.part1.b64 docs/patches/APPLY_ME.part2.b64 | tr -d '\n' | base64 -d > /tmp/APPLY_ME.decoded.patch
elif [[ -f docs/patches/APPLY_ME.patch ]]; then
  cp docs/patches/APPLY_ME.patch /tmp/APPLY_ME.decoded.patch
else
  echo "missing docs/patches/APPLY_ME.*"; exit 1
fi
git apply --check /tmp/APPLY_ME.decoded.patch
git apply /tmp/APPLY_ME.decoded.patch
echo "OK applied"
echo "  git add -A && git commit -m 'feat: bossbar, commission PriceGui, crate quality' && git push"
echo "  mvn -q -DskipTests package"
