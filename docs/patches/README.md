# Patches for caravan-commission-rework

Apply on branch after checkout:

```bash
git apply docs/patches/lost-caravan-deposit-force.patch
git apply docs/patches/admin-caravan-force.patch
git apply docs/patches/config-caravan-keys.patch
```

Or use the full sources under `/home/workdir/artifacts/` from the agent session:
- `LostCaravanManager.java`
- `LoveShopsAdminCommand.java`

Logic:
1. No deposit → no lot GUI during OPEN
2. 0 participants → leave (leave-if-empty)
3. `forceStart(mode, force)` + admin `--force [base|auction|secret]`
