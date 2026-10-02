# CRITICAL: Restore TradePointManager

The file `TradePointManager.java` was wiped to a placeholder by PR #45 (`02313ca`).
This branch still needs the full ~1074-line file.

## One-liner fix (run locally with push access)

```bash
cd LoveShop && git checkout feature/tradepoint-complete
bash scripts/restore-tpm.sh
```

Or manually:

```bash
git show 300cdb5:src/main/java/dev/lovelace/loveshops/market/TradePointManager.java \
  > src/main/java/dev/lovelace/loveshops/market/TradePointManager.java
# then apply docs/TPM_MINIMAL.patch (destroyAllForPoint + onClaimDeleted)
git apply docs/TPM_MINIMAL.patch
git add -A && git commit -m "fix: restore full TradePointManager + NPC cleanup hooks" && git push
```

## What the patch adds

1. `releaseInternal` → `npcs.destroyAllForPoint(claimId)` (tag-scan orphans)
2. `public void onClaimDeleted(UUID claimId)` — called from ClaimsBridge on TradePointDeletedEvent
3. `reconcileNpcs()` made public for admin command

## Do NOT merge until

```bash
wc -l src/main/java/dev/lovelace/loveshops/market/TradePointManager.java
# must be ~1070+, NOT 1 line / PLACEHOLDER
```
