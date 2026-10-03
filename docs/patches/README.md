# Caravan patches (bypass large-file API limits)

These are **valid `git apply` patches** against `feature/caravan-commission-rework` (or main after rebase).

## One-shot (from repo root)

```bash
git checkout feature/caravan-commission-rework
git pull
bash docs/patches/apply-all.sh
git add -A
git commit -m "feat(caravan): deposit gate, empty leave, force start, heads"
git push
```

## What they do

| Patch | Effect |
|-------|--------|
| `lost-caravan-manager.patch` | leave-if-empty, forceStart, deposit gate on lot GUI |
| `admin-caravan-force.patch` | `caravan lost start [--force] [base\|auction\|secret]` |
| `head-textures-caravan.patch` | CARAVAN_LOST_* / COMMISSION_* constants |

Config keys optional (code defaults):
`caravan.lost.leave-if-empty: true`

## Verify after apply

```bash
grep -n forceStart\|leave-if-empty\|Меню лотов src/main/java/.../LostCaravanManager.java
grep -n forceStart src/main/java/.../LoveShopsAdminCommand.java
grep CARAVAN_LOST src/main/java/.../HeadTextures.java
mvn -q -DskipTests package
```
