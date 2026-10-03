# Применить остаток ТЗ одной командой

```bash
git checkout feature/caravan-commission-rework && git pull
bash docs/patches/apply-me.sh
git add -A
git commit -m "feat: bossbar, commission PriceGui+drag, crate quality"
git push
mvn -q -DskipTests package
```

Патч: `docs/patches/APPLY_ME.part1.b64` + `part2.b64` (или `APPLY_ME.patch`).

Содержит: BossBar, PriceGui комиссионер, drag, quality ящиков, CoinFormat white, lang help.
