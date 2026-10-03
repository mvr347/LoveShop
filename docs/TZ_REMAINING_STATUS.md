# Статус доработки по ТЗ (остаток)

## Сделано в рабочей копии / артефактах (нужен apply + push)

1. **BossBar** для зарегистрированных в Потерянном караване (отсчёт до торгов)
2. **GUI регистрации** — строка «До торгов: M:SS»
3. **Комиссионер** — меню цены `PriceGui` (как ставка LoveDuels: Shift=цикл монет, ЛКМ/ПКМ ±1)
4. **Drag** предмета на кнопку «Выставить»
5. **Ящики** — качество bad / normal / good
6. **CoinFormat** — `%img_…% <white>xN</white>`
7. **lang.yml** — убраны лишние «LoveShops» в help-заголовках
8. **BARAHOLKA_PLAN.md** — барахолка вынесена в следующий PR

## Как залить на remote

```bash
git checkout feature/caravan-commission-rework && git pull
# патч из артефактов агента или локальной сборки:
export TZ_PATCH=/path/to/tz-remaining.patch
bash docs/patches/apply-tz-remaining.sh
# либо после apply:
export GH_TOKEN=...
python3 scripts/git-database-push-caravan.py
git add -A && git commit -m "feat: bossbar + commission price + crate quality" && git push
```

## Ещё не в этом патче
- Полный gui-gen-5 рестайл всех меню
- Барахолка (реализация)
- Полная зачистка всех префиксов во всех сообщениях
