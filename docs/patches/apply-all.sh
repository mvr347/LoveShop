#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
PATCHDIR="docs/patches"
decode() {
  local name="$1"
  base64 -d < "$PATCHDIR/${name}.b64" > "/tmp/${name}.decoded.patch"
}
echo "Decoding and applying caravan patches in $ROOT"
for p in lost-caravan-manager.patch admin-caravan-force.patch head-textures-caravan.patch; do
  decode "$p"
  git apply --check "/tmp/${p}.decoded.patch"
done
for p in lost-caravan-manager.patch admin-caravan-force.patch head-textures-caravan.patch; do
  git apply "/tmp/${p}.decoded.patch"
  echo "  applied $p"
done
echo "OK — commit and push:"
echo "  git add -A && git commit -m 'feat(caravan): deposit gate, empty leave, force start, heads' && git push"
