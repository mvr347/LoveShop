#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
echo "Applying caravan patches in $ROOT"
git apply --check docs/patches/lost-caravan-manager.patch
git apply --check docs/patches/admin-caravan-force.patch
git apply --check docs/patches/head-textures-caravan.patch
git apply docs/patches/lost-caravan-manager.patch
git apply docs/patches/admin-caravan-force.patch
git apply docs/patches/head-textures-caravan.patch
echo "OK — commit and push:"
echo "  git add -A && git commit -m 'feat(caravan): deposit gate, empty leave, force start, heads' && git push"
