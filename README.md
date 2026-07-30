[![](https://jitpack.io/v/mvr347/LoveShop.svg)](https://jitpack.io/#mvr347/LoveShop)

# LoveShops

Продвинутая система торговли с NPC-торговцами (Скупщик, Продавец, Аукционист).

## Общая структура

LoveShops предоставляет три основных типа торговцев-NPC:

- **Скупщик (Buyer)** — выкупает предметы у игроков по ценам, указанным в конфигурации. Цена зависит от репутации игрока.
- **Продавец-барахолка (Seller)** — приходит по расписанию и продаёт предметы, выкупленные скупщиком. Цены динамичные, зависят от спроса/предложения.
- **Аукционист (Auctioneer)** — проводит аукционы редких предметов (дорогие покупки скупщика). Есть система ставок с anti-snipe (продление при поздней ставке).

Все торговцы используют единую валюту LoveCore (`LoveEconomy`) — физические монеты в инвентаре.

## Команды

| Команда | Алиасы | Описание | Пермишин |
|---|---|---|---|
| `/loveshops` | `shops`, `loveshop`, `lshops`, `lshop`, `buyer`, `seller`, `auction`, `auctioneer` | Основная команда торговли и администрирования | `loveshops.admin` для подкоманд администратора |

### Подкоманды `/loveshops`

- `/loveshops create <type>` — создать NPC-торговца (type: buyer, seller, auctioneer)
- `/loveshops delete` — удалить торговца (смотреть на NPC)
- `/loveshops reload` — перезагрузить конфигурацию
- `/loveshops open <player> <type>` — открыть GUI торговца другому игроку
- `/loveshops buyer <player>` — установить статус скупщика (override для теста)

## Пермишины

### Администраторские команды (default: op)

| Пермишин | Описание |
|---|---|
| `loveshops.admin` | Доступ ко всем администраторским командам |
| `loveshops.admin.create` | Создание новых NPC-торговцев |
| `loveshops.admin.delete` | Удаление торговцев |
| `loveshops.admin.buyer` | Установка статуса скупщика игроку |
| `loveshops.admin.reload` | Перезагрузка конфигурации |
| `loveshops.admin.open` | Открытие GUI другому игроку |

## Конфигурация

### Валюта

Валюта настраивается в **LoveCore** `config.yml` в разделе `economy.denominations`. LoveShops требует LoveCore и без него не включится.

```yaml
# Пример в LoveCore/config.yml
economy:
  denominations:
    copper:
      item-id: 'itemsadder:copper_coin'
      value: 1
    iron:
      item-id: 'itemsadder:iron_coin'
      value: 10
    gold:
      item-id: 'itemsadder:gold_coin'
      value: 50
    diamond:
      item-id: 'itemsadder:diamond_coin'
      value: 100
    netherite:
      item-id: 'itemsadder:netherite_coin'
      value: 1000
```

### Скупщик (Buyer)

```yaml
buyer:
  enabled: true

  # Источник цен: 'config' (из этого файла) или 'nbt' (NBT-тег предмета)
  base-price-config:
    type: "config"
    default-price: 100  # если цена не найдена в конфиге

  # Вариация цены при выкупе
  price-variance:
    min-percent: -30  # мин скидка
    max-percent: 30   # макс коэффициент

  # Штраф за повторное предложение одного и того же предмета
  repetition-penalty:
    enabled: true
    penalty-per-submit: 5   # -5% за каждую сдачу
    max-penalty: 50         # максимум -50%

  # Бонус/штраф на основе репутации игрока
  reputation-bonus:
    good-status: 20     # +20% к цене
    bad-status: -50     # отказ в покупке
    aggressive-status: -30  # отказ или сниженная цена

  # Сообщения
  messages:
    reject-bad-reputation:
      - "Скупщик: Про тебя ходят плохие слухи... Катись своей дорогой!"
      - "Скупщик: Мне не нравится твоё имя в городе. Уходи."
    reject-aggressive:
      - "Скупщик: Дружок, я не хочу проблем, иди своей дорогой."
    accept: "&aСкупщик: Отличный товар! Вот тебе &b{price} &aмонет!"
    accept-with-bonus: "&aСкупщик: У тебя хорошая репутация! Вот ещё &b{bonus} &aмонет сверху."
```

### Продавец-барахолка (Seller)

```yaml
seller:
  enabled: true

  # День недели и время прибытия/убытия
  arrival-day: "SUNDAY"        # день недели
  arrival-time: "10:00"        # HH:MM
  departure-time: "18:00"

  # Минимум предметов в казне для прибытия торговца
  min-items-to-spawn: 10

  # Базовая наценка на предметы из скупки
  markup-percent: 15

  # Динамическое ценообразование
  dynamic-pricing:
    enabled: true
    demand-weight-percent: 3    # +% за каждую покупку
    supply-weight-percent: 2    # -% за единицу в наличии
    noise-min-percent: -10      # случайный шум
    noise-max-percent: 10
    min-multiplier: 0.3         # мин итоговый множитель
    trend-threshold-percent: 5  # порог для отображения тренда в меню

  messages:
    arrival:
      - "&e[Барахолка] &fТорговец приехал! &6Сейчас с 10:00 до 18:00."
    departure:
      - "&e[Барахолка] &fТорговец уезжает! Встретимся в следующее воскресенье."
```

### Аукционист (Auctioneer)

```yaml
auctioneer:
  enabled: true

  # Длительность аукциона
  auction-duration-hours: 24

  # Предметы выше этой цены идут на аукцион, а не на барахолку
  price-threshold: 400

  # Цена мгновенного выкупа = стартовая цена × множитель
  buyout-multiplier: 2.5

  # Anti-snipe — продление при поздней ставке
  anti-snipe:
    enabled: true
    threshold-seconds: 30   # ставка позже этого времени продлевает
    extension-seconds: 30   # на сколько продлевать

  # Минимальный размер следующей ставки
  bid-step:
    type: "percentage"  # 'percentage' или 'fixed'
    value: 5            # 5% от текущей ставки

  messages:
    auction-started: "&6Аукцион! &eПредмет: {item_name} &eна сумму {starting_price}"
    auction-ended: "&6Аукцион завершён! &eПобедитель: {winner} за {final_price}"
```

### Защита и системные параметры

```yaml
protection:
  gui-update-on-purchase: true
  item-already-sold-message: "&cЭтот предмет уже куплен другим игроком."
  insufficient-funds: "&cНедостаточно средств!"

placeholders:
  enabled: true  # интеграция PlaceholderAPI
```

### Конкретные цены предметов

```yaml
prices-config:
  # Пример: предметы могут быть указаны по Material или по NBT-тегам
  # Material-based
  DIAMOND:
    base-price: 5000
  EMERALD:
    base-price: 3000

  # NBT/Custom Item-based (примеры из LoveClans, LoveBrew и т.д.)
  # Артефакты кланов
  clan_artifact:
    base-price: 10000
```

## Экономика

### Монеты и платежи

Все платежи идут через LoveCore `LoveEconomy`:

- **Выкуп у игрока**: монеты выдаются в инвентарь, переполнение → монеты падают на землю
- **Продажа на барахолке**: монеты берутся из инвентаря, недостаток → отказ в покупке
- **Ставка на аукционе**: монеты зарезервированы в памяти (не удаляются до конца аукциона)
- **Победитель аукциона**: монеты списываются, предмет выдаётся

## PlaceholderAPI

| Плейсхолдер | Описание |
|---|---|
| `%loveshops_seller_arrival%` | Дата прибытия барахолки |
| `%loveshops_seller_stock%` | Количество предметов на барахолке |
| `%loveshops_auction_count%` | Количество активных аукционов |
| `%loveshops_player_balance%` | Баланс игрока (монеты в инвентаре) |

## Зависимости

### Обязательные

- **LoveCore** — единая экономика (`LoveEconomy`), служба репутации
- **Paper 1.21.11** — базовый Minecraft сервер

### Мягкие зависимости

- **Citizens** — NPC для торговцев
- **LoveBehavior** — система репутации (влияет на цены скупщика)
- **PlaceholderAPI** — интеграция плейсхолдеров
- **ItemsAdder** — кастомные предметы
- **LoveClans** — поддержка клановых артефактов

## Установка и сборка

### Зависимость в Maven

```xml
<dependency>
    <groupId>com.github.mvr347.LoveShop</groupId>
    <artifactId>loveshops</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>

<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```

### Компиляция

```bash
mvn package
```

Java 21, Paper 1.21.11. Сборка на пуш настроена в `.github/workflows/build.yml`.

## Структура данных

### Таблицы базы данных

- `inventory_buyer` — выкупленные предметы (ждут барахолки)
- `auction_items` — активные аукционы
- `auction_bids` — ставки на аукционах
- `sold_items` — история проданных предметов

Используется SQLite по умолчанию; возможна настройка на MySQL в `config.yml`.

## Примечания

- Если скупщик отказывает в покупке (плохая репутация, статус aggressive), предмет остаётся в инвентаре игрока.
- Если инвентарь игрока полон при выкупе, монеты падают на землю и подбираются игроком вручную.
- Аукционист автоматически продлевает время аукциона, если ставка поступила в последние 30 секунд (можно отключить через anti-snipe).
- Динамическое ценообразование учитывает спрос и предложение, но минимальная цена ограничена `min-multiplier`.
