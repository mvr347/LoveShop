# Patches

## Полный набор по ТЗ (караван + комиссионер)

```bash
git checkout feature/caravan-commission-rework && git pull
# если base ещё без forceStart — сначала старые патчи:
# bash docs/patches/apply-all.sh
bash docs/patches/apply-tz-remaining.sh
git add -A
git commit -m "feat: bossbar, commission PriceGui, crate quality"
git push
```

Или после apply: `python3 scripts/git-database-push-caravan.py` (нужен GH_TOKEN).

### tz-remaining включает
- BossBar отсчёт для зарегистрированных
- GUI регистрации: время до торгов
- Комиссионер: PriceGui (Shift=монета, ЛКМ/ПКМ ±) + drag на «Выставить»
- Ящики: bad/normal/good
- CoinFormat `<white>xN`
- Мягкая чистка префиксов lang
- docs/BARAHOLKA_PLAN.md (барахолка — следующий PR)
