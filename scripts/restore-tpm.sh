#!/bin/bash
# restore-tpm.sh — restore TradePointManager on feature/tradepoint-complete
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git show 300cdb5:src/main/java/dev/lovelace/loveshops/market/TradePointManager.java \
  > src/main/java/dev/lovelace/loveshops/market/TradePointManager.java

python3 - <<'PY2'
from pathlib import Path
p = Path("src/main/java/dev/lovelace/loveshops/market/TradePointManager.java")
text = p.read_text()
old = """        p.rentedAt(0);
        p.version(reset.version());
        npcs.destroy(npc);
        npcs.destroy(guard);
    }"""
new = """        p.rentedAt(0);
        p.version(reset.version());
        // Tag-scan: remove every Citizens NPC bound to this point (orphans too).
        npcs.destroyAllForPoint(p.claimId());
        if (npc != null) npcs.destroy(npc);
        if (guard != null) npcs.destroy(guard);
    }

    /**
     * Claim fully deleted in LoveClaims. Clean market row + NPCs even without a tenant.
     */
    public void onClaimDeleted(UUID claimId) {
        TradePoint p = points.get(claimId);
        if (p != null) {
            if (p.hasOwner()) {
                releaseInternal(p, "CLAIM_DELETED");
            } else {
                closeViewers(claimId);
                npcs.destroyAllForPoint(claimId);
            }
            try {
                repo.deletePoint(claimId);
            } catch (SQLException e) {
                plugin.getLogger().warning("Не удалось удалить строку точки " + claimId + ": " + e.getMessage());
            }
            points.remove(claimId);
        } else {
            npcs.destroyAllForPoint(claimId);
        }
    }"""
if old not in text:
    raise SystemExit("pattern not found — file layout changed")
text = text.replace(old, new, 1)
text = text.replace("    private void reconcileNpcs()", "    public void reconcileNpcs()", 1)
p.write_text(text)
print("OK", p.stat().st_size, "bytes")
PY2

git add src/main/java/dev/lovelace/loveshops/market/TradePointManager.java
git commit -m "fix: restore full TradePointManager + destroyAllForPoint/onClaimDeleted"
git push origin HEAD
echo "Done. Verify: wc -l src/main/java/dev/lovelace/loveshops/market/TradePointManager.java"
