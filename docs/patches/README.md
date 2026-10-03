# Caravan patches + Git Database API push

## Recommended (full sources on remote, no API size issues)

```bash
git fetch origin && git checkout feature/caravan-commission-rework && git pull
bash docs/patches/apply-all.sh          # patch local working tree
export GH_TOKEN=ghp_xxx                 # repo scope
python3 scripts/git-database-push-caravan.py   # blobs→tree→commit→ref
```

Uses GitHub **Git Database API** (not Contents API): base64 blobs, stable with UTF-8/Cyrillic, up to 100MB per blob.

## Alternative: only git apply + normal push

```bash
bash docs/patches/apply-all.sh
git add -A && git commit -m "feat(caravan): deposit gate, empty leave, force start, heads"
git push
```

## Verify on remote after push

```bash
curl -sL "https://raw.githubusercontent.com/mvr347/LoveShop/feature/caravan-commission-rework/src/main/java/dev/lovelace/loveshops/managers/LostCaravanManager.java" | grep -c forceStart
```
