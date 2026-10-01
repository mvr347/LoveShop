# TradePoint / LoveShop rework plan

Ветка: `feature/tradepoint-rework-npc-gui`

## 1. Баг: NPC не удаляется после удаления плота

**Сделано:** `StallNpcService.destroyAllForPoint(UUID)` + `hardDestroy` (despawn → destroy).
Вызывать из `TradePointManager.releaseInternal` вместо/в дополнение к `destroy(id)`.

Проверить также LoveClaims: событие `TradePointReleasedEvent` / удаление claim должно доходить до `TradePointManager.onReleased`.

## 2. Команды (целевая схема)

| Команда | Назначение |
|---|---|
| `/tradepoint` / `/торговаяточка` | Управление **своим** магазином (меню владельца) |
| `/tp` / `/тп` `<id>` | Телепорт к точке (публичный warp точки) |
| `/tradepointadmin` | Админ |

### tradepointadmin

```
npc create|remove|tp taxer|tradepointseller <id>
create <id>          → wizard (зона → NPC → табличка «закрыто» → TP → done/cancel)
delete <id>
owner set|remove <nick> <id>
lvl set|add|remove <id> [n]
```

Wizard по образцу создания арены LoveDuels (пошаговый session + клики блоков).

## 3. Схема аренды (Феодал / tradepointseller)

1. На точке — табличка с **id**.
2. Игрок запоминает id, идёт к NPC **tradepointseller** («Феодал»).
3. GUI: список точек (свободные / занятые), аренда от 1 недели, можно больше.
4. Оплата сразу за выбранный срок.
5. У феодала же: продать свою точку, нанять стражу.

## 4. Owner GUI (StallOwnerGui)

```
Торговля  →  [Продажа | Скупка | Касса]
Склад
Управление → [Режим (default SELL_ONLY) | Улучшение | Передать | Открыта/Закрыта]
```

## 5. PriceGui (эталон LoveDuels ставки)

- Слот лота
- Кнопка номиналов: глифы `%img_…%`, Shift = смена, ЛКМ +, ПКМ −
- Подтвердить (disabled пока price == 0)
- Закрыть в последнем слоте footer
- gui-gen-5 borders

## 6. Склад / витрина

Разрешить drag-and-drop в рабочие слоты (сейчас `setCancelled(true)` на всё).

## 7. Аренда: оповещения + конфискация

- warn за N часов (`onExpiryWarning` уже есть)
- grace → release → destroyAllForPoint + pending_returns

## 8. Барахольщик

- Ежедневный ротируемый список серверных лотов
- Игроки выставляют свои → забирают деньги
- Налог 7% на лоты игроков

## Порядок PR

1. NPC cleanup (этот коммит) ✅
2. Команды + aliases + skeleton admin wizard
3. Owner GUI + Trade submenu + PriceGui glyph
4. Drag-drop storage/listings
5. Feudal NPC rent GUI
6. Flea market (Барахольщик)
7. LoveClaims: plot delete → event + sign id
