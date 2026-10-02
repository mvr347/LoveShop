# TradePoint / LoveShop rework plan

Ветка: `feature/tradepoint-complete`

## Статус

| # | Задача | Статус |
|---|--------|--------|
| 1 | NPC cleanup `destroyAllForPoint` + `releaseInternal` | ✅ |
| 2 | LoveClaims `TradePointDeletedEvent` + ClaimsBridge | ✅ (отдельная ветка LoveClaims) |
| 3 | Команды `/tradepoint` `/tp` `/tradepointadmin` | ✅ |
| 4 | Owner GUI: Торговля / Склад / Управление | ✅ |
| 5 | Drag-drop склад + витрина + bottom inventory | ✅ |
| 6 | PriceGui glyph (Shift/LMB/RMB) | ✅ уже было |
| 7 | Полный wizard create (session + клики) | ⏳ stub в admin |
| 8 | Феодал NPC (аренда GUI) | ⏳ |
| 9 | Барахольщик | ⏳ |
| 10 | Полные lang.yml строки | ⏳ частично |

## Команды

| Команда | Назначение |
|---|---|
| `/tradepoint` · `торговаяточка` | Меню владельца / returns / transfer / blacklist / discount / mode |
| `/tp` · `/тп` | ТП к своей точке (`[uuid]` для админа) |
| `/tradepointadmin` · `tpadmin` · `tpa` | npc / delete / owner / lvl / create / reconcile / info |

## GUI владельца

```
Торговля  →  [Продажа | Скупка | Касса]
Склад
Управление → [Режим | Стража | Скидки | ЧС | Передать | Улучшение | Открыта/Закрыта]
```

## Следующий PR

1. Wizard создания точки (session listener, 4 шага)
2. FeudalLandlordNpc + RentGui (неделя+, оплата, продажа точки)
3. Барахольщик daily catalog + player lots + 7% tax
4. lang.yml добить
