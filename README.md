[![](https://jitpack.io/v/mvr347/LoveShop.svg)](https://jitpack.io/#mvr347/LoveShop)

# LoveShops

Продвинутая система торговли с NPC-торговцами (Скупщик, Продавец, Аукционист, Банкир).

## Общая структура

LoveShops предоставляет торговцев-NPC:

- **Скупщик (Buyer)** — выкупает предметы у игроков по ценам из конфигурации. Цена зависит от репутации.
- **Продавец-барахолка (Seller)** — по расписанию продаёт предметы, выкупленные скупщиком.
- **Аукционист (Auctioneer)** — аукционы редких предметов с anti-snipe.
- **Банкир (Banker)** — обмен физических монет LoveCore: укрупнение мелочи или размен крупных номиналов.
- **Военный торговец (WarMerchant)** / **Странник (Wanderer)** — отдельные роли.

Все используют валюту LoveCore (`LoveEconomy`) — физические монеты в инвентаре.

## Банкир

ПКМ по NPC типа `banker` открывает GUI:
- **Укрупнить** — весь баланс списывается и выдаётся крупными номиналами
- **Разменять** — одна монета выбранного номинала → более мелкие

Создание:
```
/npc create banker Банкир
/loveshopsadmin npc create banker
```

Комиссия: `banker.fee-percent` в `config.yml` (по умолчанию 0).

## Команды

| Команда | Описание |
|---|---|
| `/loveshopsadmin npc create <type>` | type: buyer, seller, auctioneer, warmerchant, wanderer, **banker** |
| `/loveshopsadmin npc delete` | отвязать NPC |
| `/loveshopsadmin reload` | перезагрузка конфига |

## Зависимости

- **LoveCore** (обязательно) — LoveEconomy
- **Paper 1.21**
- **Citizens** (мягкая) — NPC
