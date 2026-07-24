# LoveShops — Полная техническая спецификация

**Версия:** 1.0.0  
**Статус:** Ready for implementation  
**Дата:** July 2026  

---

## 1. ОБЗОР СИСТЕМЫ

**LoveShops** — система торговли, построенная вокруг трёх типов NPC-торговцев:

1. **Скупщик (Buyer)** — *всегда присутствует* на сервере. Скупает предметы у игроков, платит валютой (ItemsAdder/ExecutableItems coins).
2. **Торговец-барахолка (Seller)** — приходит в **воскресенье с 10:00 по 18:00**, продаёт предметы, которые были сданы скупщику на протяжении недели.
3. **Аукционер (Auctioneer)** — специальный NPC для редких предметов. Аукционы длятся **24 часа** (добавляются в воскресенье, завершаются в понедельник).

### Ключевые механики:
- **Репутация** (из LoveBehavior) влияет на цену покупки скупщиком.
- **Случайная вариативность цены** (±30% от базовой).
- **Ослабление цены** при повторной сдаче одинаковых предметов.
- **Статусы игроков** (good/bad/aggressive) с кастомными сообщениями отказа.
- **Защита от race conditions** — обновление GUI по событию при покупке, красная/зелёная голова для feedback.
- **Плейсхолдеры** для интеграции с другими плагинами.

---

## 2. АРХИТЕКТУРА ДАННЫХ (SQLite)

### Таблица: `shops_npcs`
Хранит описание всех NPC-торговцев.

```sql
CREATE TABLE IF NOT EXISTS shops_npcs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT UNIQUE NOT NULL,                -- UUID NPC (кастомный UUID для идентификации)
    type TEXT NOT NULL,                       -- 'buyer', 'seller', 'auctioneer'
    world TEXT NOT NULL,                      -- мир
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    yaw FLOAT,
    pitch FLOAT,
    name TEXT NOT NULL,                       -- отображаемое имя NPC
    display_name TEXT,                        -- MiniMessage formatted name
    skin_owner TEXT,                          -- игрок для скина NPC
    created_at INTEGER DEFAULT (strftime('%s', 'now')),
    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
);
```

### Таблица: `buyer_inventory`
Хранит предметы, которые игроки сдали скупщику (для последующей продажи на барахолке).

```sql
CREATE TABLE IF NOT EXISTS buyer_inventory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    npc_id INTEGER NOT NULL REFERENCES shops_npcs(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL,                -- кто сдал
    item_data TEXT NOT NULL,                  -- ItemStack as JSON (NBT)
    base_price INTEGER NOT NULL,              -- базовая цена из config
    quantity INTEGER DEFAULT 1,
    received_at INTEGER DEFAULT (strftime('%s', 'now')),
    sold_at INTEGER,                          -- когда продано на барахолке (NULL = не продано)
    INDEX idx_npc_player (npc_id, player_uuid),
    INDEX idx_sold_at (sold_at)
);
```

### Таблица: `buyer_prices_history`
Отслеживает сколько раз игрок сдавал конкретный предмет (для ослабления цены).

```sql
CREATE TABLE IF NOT EXISTS buyer_prices_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    item_type TEXT NOT NULL,                  -- Material name или custom item ID
    submit_count INTEGER DEFAULT 1,           -- кол-во сдач
    last_submitted_at INTEGER DEFAULT (strftime('%s', 'now')),
    price_penalty_percent REAL DEFAULT 0,    -- накопленный штраф (%)
    UNIQUE(player_uuid, item_type)
);
```

### Таблица: `buyer_reputation_overrides`
Кастомные статусы игроков (good/bad/aggressive) для скупщика.

```sql
CREATE TABLE IF NOT EXISTS buyer_reputation_overrides (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,                     -- 'good', 'bad', 'aggressive'
    set_by TEXT NOT NULL,                     -- кто установил (OP UUID)
    set_at INTEGER DEFAULT (strftime('%s', 'now')),
    custom_message TEXT,                      -- кастомное сообщение отказа
    reason TEXT                               -- причина (для логирования)
);
```

### Таблица: `auctions`
Хранит активные и завершённые аукционы редких предметов.

```sql
CREATE TABLE IF NOT EXISTS auctions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auctioneer_npc_id INTEGER NOT NULL REFERENCES shops_npcs(id) ON DELETE CASCADE,
    item_data TEXT NOT NULL,                  -- ItemStack as JSON
    starting_price INTEGER NOT NULL,
    current_highest_bid INTEGER DEFAULT 0,
    highest_bidder_uuid TEXT,                 -- NULL пока нет ставок
    starts_at INTEGER NOT NULL,               -- timestamp начала
    ends_at INTEGER NOT NULL,                 -- timestamp конца (24 часа спустя)
    status TEXT DEFAULT 'active',             -- 'active', 'completed', 'cancelled'
    winner_uuid TEXT,                         -- победитель (после завершения)
    completed_at INTEGER,
    INDEX idx_auctioneer (auctioneer_npc_id),
    INDEX idx_status (status),
    INDEX idx_ends_at (ends_at)
);
```

### Таблица: `auction_bids`
История ставок на аукционе.

```sql
CREATE TABLE IF NOT EXISTS auction_bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    bidder_uuid TEXT NOT NULL,
    bid_amount INTEGER NOT NULL,
    placed_at INTEGER DEFAULT (strftime('%s', 'now')),
    INDEX idx_auction (auction_id),
    INDEX idx_bidder (bidder_uuid)
);
```

### Таблица: `reserved_currency`
Зарезервированная валюта игроков (при ставках на аукцион).

```sql
CREATE TABLE IF NOT EXISTS reserved_currency (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL UNIQUE,
    reserved_amount INTEGER DEFAULT 0,        -- сколько зарезервировано
    last_bid_auction_id INTEGER,              -- последний активный аукцион, на который ставил
    INDEX idx_player (player_uuid)
);
```

---

## 3. CONFIG.YML СТРУКТУРА

```yaml
# ===== ВАЛЮТА И ИНТЕГРАЦИЯ =====
currency:
  type: "itemsadder"                          # 'itemsadder' или 'executableitems'
  currency-item: "coins"                      # ID кастомного предмета
  currency-display-name: "Монеты"             # Название для сообщений

# ===== СКУПЩИК =====
buyer:
  enabled: true
  base-price-config:                          # как определяется базовая цена
    type: "config"                            # 'config' = из config, 'nbt' = из NBT предмета
    default-price: 100                        # если не найдена в config
  
  price-variance:
    min-percent: -30                          # минимальная вариативность
    max-percent: 30                           # максимальная вариативность
  
  repetition-penalty:
    enabled: true
    penalty-per-submit: 5                     # штраф % за каждую сдачу одинакового
    max-penalty: 50                           # максимум штрафа
  
  reputation-bonus:
    good-status: 20                           # + % к цене
    bad-status: -50                           # отказ в покупке
    aggressive-status: -30                    # отказ или сниженная цена
  
  messages:
    reject-bad-reputation:
      - "Скупщик: Про тебя ходят плохие слухи... Катись своей дорогой!"
      - "Скупщик: Мне не нравится твоё имя в городе. Уходи."
      - "Скупщик: С тобой лучше не иметь дел."
    reject-aggressive:
      - "Скупщик: Дружок, я не хочу проблем, иди своей дорогой."
      - "Скупщик: Твой агрессивный тон мне не нравится. Ступай!"
      - "Скупщик: Спокойнее! Или попросить у меня нечего."
    accept: "&aСкупщик: Отличный товар! Вот тебе &b{price}&a!"
    accept-with-bonus: "&aСкупщик: У тебя хорошая репутация! Вот ещё &b{bonus}&a."

# ===== ТОРГОВЕЦ-БАРАХОЛКА =====
seller:
  enabled: true
  arrival-day: "SUNDAY"                       # день недели
  arrival-time: "10:00"                       # формат HH:MM (по серверному времени)
  departure-time: "18:00"
  min-items-to-spawn: 10                      # минимум предметов для прибытия торговца
  
  markup-percent: 15                          # наценка на предметы от скупщика (%)
  messages:
    arrival:
      - "&e[Барахолка] &fТорговец приехал! &6Сейчас с 10:00 до 18:00."
      - "&e[Барахолка] &fВот это новый товар! Приходите покупать!"
    departure:
      - "&e[Барахолка] &fТорговец уезжает! Встретимся в следующее воскресенье."

# ===== АУКЦИОН (РЕДКИЕ ПРЕДМЕТЫ) =====
auctioneer:
  enabled: true
  auction-duration-hours: 24                  # длительность аукциона
  
  bid-step:
    type: "percentage"                        # 'percentage' или 'fixed'
    value: 5                                  # 5% от текущей ставки (если percentage)
    # или fixed: 50 (если fixed)
  
  messages:
    auction-started: "&6Аукцион! &eПредмет: {item_name}&e на сумму {starting_price}"
    auction-ended: "&6Аукцион завершён! &eПобедитель: {winner} за {final_price}"
    bid-placed: "&a✓ Ставка принята: {bid_amount}"
    bid-outbid: "&c✗ Ваша ставка перебита на {new_amount}"

# ===== ЗАЩИТА И СИСТЕМНЫЕ ПАРАМЕТРЫ =====
protection:
  gui-update-on-purchase: true                # обновлять GUI по событию при покупке
  item-already-sold-message: "&cЭтот предмет уже куплен другим игроком."
  
placeholders:
  enabled: true                               # включить PlaceholderAPI интеграцию
  
# ===== ПРАВА ДОСТУПА =====
permissions:
  create-npc: "loveshops.admin.create"
  delete-npc: "loveshops.admin.delete"
  buyer-status: "loveshops.admin.buyer"
  reload: "loveshops.admin.reload"

# ===== ПОЗИЦИИ ПРЕДМЕТОВ В CONFIG =====
prices-config:
  DIAMOND: 500
  GOLD_INGOT: 150
  EMERALD: 300
  NETHERITE_INGOT: 2000
  # ... и т.д.

# ===== ЯЗЫК =====
lang:
  commands:
    npc-created: "&a✓ NPC '{name}' (тип: {type}) создан."
    npc-deleted: "&a✓ NPC удалён."
    buyer-status-set: "&a✓ Статус игрока {player} изменён на {status}."
    invalid-args: "&cНеправильные аргументы."
    player-not-found: "&cИгрок не найден."
    only-players: "&cТолько для игроков."
    reloading: "&eПеregрузка конфига..."
    reload-complete: "&a✓ Перезагрузка завершена."
```

---

## 4. КОМАНДЫ

### `/loveshops npc create <type> <name>`
Создаёт NPC по позиции игрока.
- `<type>`: `buyer` | `seller` | `auctioneer`
- `<name>`: отображаемое имя
- **Пермиссия:** `loveshops.admin.create`

**Пример:**  
`/loveshops npc create buyer Торговец`

### `/loveshops npc delete`
Удаляет NPC, на которого смотрит игрок (в радиусе ~5 блоков).
- **Пермиссия:** `loveshops.admin.delete`

### `/loveshops buyer <player> <status> [message]`
Устанавливает статус игрока для скупщика.
- `<player>`: имя или UUID
- `<status>`: `default` | `good` | `bad` | `aggressive`
- `[message]`: кастомное сообщение отказа (опционально)
- **Пермиссия:** `loveshops.admin.buyer`

**Примеры:**  
```
/loveshops buyer steve bad
/loveshops buyer steve good
/loveshops buyer steve aggressive Слишком агрессивный поведение
```

### `/loveshops reload`
Перезагружает конфиг и перезапускает планировщик событий.
- **Пермиссия:** `loveshops.admin.reload`

---

## 5. GUI ДИЗАЙН

### Скупщик GUI (Inventory 27 слотов)
```
[Закрыть]      [   -   ]      [Название скупщика]      [   +   ]      [Инфо]
[Инструмент]   [    Предметы из инвентаря игрока    ]                [Прокрутка ↓]
[Инструмент]   [    Предметы из инвентаря игрока    ]                [Прокрутка ↑]
[Инструмент]   [    Предметы из инвентаря игрока    ]                [Принять]
```

**Слоты:**
- 0: Кнопка закрытия (красная голова)
- 4: Название + статистика (инфо предмета)
- 8: Инфо кнопка (жёлтая шерсть)
- 1, 10, 19: Кнопки навигации (стрелки)
- 2-3, 5-7, 11-16, 20-25: Слоты предметов (max 16 на странице)
- 26: Кнопка "Принять" (зелёная голова при успехе)

**Итем-лор скупщика:**
```
§eДорогой герой!
§7Сдаёшь вещи — получишь монеты.
§f
§7Цена: §b{base_price} ±{variance}%
§7Статус: §a{reputation_status}
§7Штраф: §c-{penalty}%
§f
§7§oЛКМ чтоб выбрать предмет
```

---

### Торговец-барахолка GUI (Inventory 45 слотов — 5 строк)
Аналогично скупщику, но:
- Слоты 1-44: предметы из `buyer_inventory` (со скидкой/наценкой)
- При клике: попытка покупки + обновление GUI для всех игроков
- Если предмет уже куплен: красная голова + сообщение

---

### Аукционер GUI (Inventory 27 слотов)
```
[Назад]        [Лот 1]        [Лот 2]        [Лот 3]        [Лот 4]
[Лот 5]        [Лот 6]        [Лот 7]        [Лот 8]        [Лот 9]
[Мои ставки]   [Активные]     [История]      [Завершённые]   [Перезагрузить]
```

**Лот-итем:**
```
§6{item_name}
§f
§7Начальная цена: §b{starting_price}
§7Текущая ставка: §b{current_bid} §7({bidder_name})
§7Осталось: §c{time_left}
§f
§7§oЛКМ чтоб поставить ставку
```

---

## 6. ПЛЕЙСХОЛДЕРЫ (PlaceholderAPI)

Для использования в других плагинах (например, в табов или гадах):

```
%loveshops_seller_arrival%          → "В 10:00" или "Завтра в 10:00"
%loveshops_seller_active%           → "true" / "false"
%loveshops_auction_count%           → количество активных аукционов
%loveshops_player_reserved%<player> → сумма зарезервированной валюты игрока
%loveshops_item_<type>_price%       → базовая цена предмета
```

---

## 7. СИСТЕМНЫЕ СОБЫТИЯ

### GuiBuyerItemClickEvent
Кастомное событие при клике на предмет в GUI скупщика.
```java
public class GuiBuyerItemClickEvent extends Event {
    private final Player player;
    private final ItemStack item;
    private final int basePrice;
    private final double actualPrice;  // с вариативностью и штрафами
    // ...
}
```

### GUISellerItemClickEvent
При клике на предмет в GUI торговца.

### AuctionBidPlacedEvent
При размещении ставки.

---

## 8. RACE CONDITION PROTECTION

### Сценарий:
Игрок А открыл GUI торговца, видит предмет X.  
Игрок Б в то же время покупает предмет X.

### Решение:
1. **По событию** при покупке (GUISellerItemClickEvent):
   - Обновить все открытые GUI торговцев (`InventoryView.getTopInventory()`)
   - Удалить проданный предмет из слота, заменить на красную голову (§c✗ Продано)
   - Показать сообщение: `§c✓ Товар куплен`

2. **Fallback** при клике на проданный:
   - Проверить статус в БД перед транзакцией
   - Если уже продан: отправить сообщение и закрыть попытку

---

## 9. ИНТЕГРАЦИЯ С LOVEBEHAVIOR

**LoveShops** обращается к `LoveBehaviorAPI` через `ServicesManager`:

```java
LoveBehaviorAPI behaviorAPI = Bukkit.getServicesManager()
    .load(LoveBehaviorAPI.class);

// Получить уровень репутации игрока
int reputation = behaviorAPI.getReputation(playerUUID);
String level = behaviorAPI.getReputationLevel(reputation);  // "excellent", "good", "neutral", "bad"
```

**На основе уровня репутации:**
- `excellent` / `good`: +20% бонус к цене
- `neutral`: без изменений
- `bad`: отказ (если override = "bad")
- `aggressive`: отказ или -30% (если override = "aggressive")

---

## 10. ФАЙЛОВАЯ СТРУКТУРА

```
LoveShops/
├── pom.xml
├── plugin.yml
└── src/main/java/dev/lovelace/loveshops/
    ├── LoveShops.java                    ← main class
    ├── api/
    │   └── LoveShopsAPI.java             ← public API
    ├── commands/
    │   ├── ShopsCommand.java             ← /loveshops
    │   └── TabCompleter.java
    ├── listeners/
    │   ├── InventoryClickListener.java
    │   └── ScheduleListener.java         ← запуск торговца по расписанию
    ├── managers/
    │   ├── NpcManager.java
    │   ├── BuyerManager.java
    │   ├── SellerManager.java
    │   ├── AuctionManager.java
    │   ├── PriceCalculator.java
    │   └── CurrencyManager.java
    ├── database/
    │   ├── DatabaseManager.java
    │   └── migrations/                   ← версионирование БД
    ├── gui/
    │   ├── BuyerGui.java
    │   ├── SellerGui.java
    │   ├── AuctionGui.java
    │   └── GuiUpdater.java
    ├── models/
    │   ├── NpcData.java
    │   ├── AuctionData.java
    │   ├── BidData.java
    │   └── ReservedCurrency.java
    ├── events/
    │   ├── GuiBuyerItemClickEvent.java
    │   ├── GUISellerItemClickEvent.java
    │   └── AuctionBidPlacedEvent.java
    ├── placeholders/
    │   └── LoveShopsPlaceholder.java      ← PlaceholderAPI hook
    └── utils/
        ├── ItemStackConverter.java        ← JSON сериализация
        ├── TimeUtils.java
        └── MessageUtils.java
```

---

## 11. ОСНОВНЫЕ КЛАССЫ И МЕТОДЫ

### `LoveShops.java`
```java
public final class LoveShops extends JavaPlugin {
    private static LoveShops instance;
    private DatabaseManager databaseManager;
    private NpcManager npcManager;
    private BuyerManager buyerManager;
    private SellerManager sellerManager;
    private AuctionManager auctionManager;
    private PriceCalculator priceCalculator;
    
    @Override
    public void onEnable() {
        // Инициализация по порядку:
        // 1. Config
        // 2. Database
        // 3. Managers
        // 4. Commands
        // 5. Listeners
        // 6. Placeholders (если есть)
        // 7. API registration
    }
}
```

### `BuyerManager.java`
```java
public class BuyerManager {
    // Основные методы:
    public CompletableFuture<Integer> calculatePrice(Player player, ItemStack item);
    public CompletableFuture<Void> buyItem(Player player, ItemStack item);
    public boolean canPlayerSell(Player player);  // check reputation override
    public void rejectPlayer(Player player, String status);
}
```

### `SellerManager.java`
```java
public class SellerManager {
    // Основные методы:
    public void spawnSeller();                    // по расписанию (10:00 воскресенье)
    public void despawnSeller();                  // по расписанию (18:00 воскресенье)
    public void openGui(Player player);
    public CompletableFuture<Void> sellItem(Player player, ItemStack item);
    public void broadcastGuiUpdate();             // при покупке
}
```

### `AuctionManager.java`
```java
public class AuctionManager {
    public CompletableFuture<Integer> createAuction(ItemStack item, int startingPrice);
    public CompletableFuture<Void> placeBid(Player player, int auctionId, int bidAmount);
    public CompletableFuture<Void> cancelBid(Player player, int auctionId);
    public void completeAuction(int auctionId);   // по таймеру
    public int getReservedAmount(Player player);
}
```

### `PriceCalculator.java`
```java
public class PriceCalculator {
    // Ключевой метод:
    public int calculateBuyPrice(Player player, ItemStack item) {
        int basePrice = getBasePrice(item);
        double variance = getVariance();                    // -30% до +30%
        double penaltyPercent = getPenaltyPercent(player, item);  // репитиция
        int reputationBonus = getReputationBonus(player);   // из LoveBehavior
        
        return (int) (basePrice * variance * (1 - penaltyPercent/100) 
                     * (1 + reputationBonus/100));
    }
}
```

---

## 12. СООБЩЕНИЯ ОТКАЗА (КАСТОМИЗИРУЕМО)

### Статус: `bad`
```
Скупщик: Про тебя ходят плохие слухи... Катись своей дорогой!
Скупщик: Мне не нравится твоё имя в городе. Уходи.
Скупщик: С тобой лучше не иметь дел.
Скупщик: Ты не нужен здесь.
```

### Статус: `aggressive`
```
Скупщик: Дружок, я не хочу проблем, иди своей дорогой.
Скупщик: Твой агрессивный тон мне не нравится. Ступай!
Скупщик: Спокойнее! Или попросить у меня нечего.
Скупщик: Я не хочу с тобой иметь дело.
```

---

## 13. FEEDBACK ВИЗУАЛЫ В GUI

### Успешная покупка:
- Зелёная голова (DYE_GREEN) вместо предмета
- Сообщение: `&a✓ Товар куплен успешно!`

### Предмет уже куплен:
- Красная голова (DYE_RED) вместо предмета
- Сообщение: `&c✗ Этот товар уже куплен другим игроком.`

### Отказано:
- Чёрная голова (DYE_BLACK) вместо предмета
- Сообщение из config (кастомное для статуса)

---

## 14. ПЛЭНУЕМЫЕ РАСШИРЕНИЯ (Future)

- Скидки для VIP игроков
- История продаж (для аналитики)
- Система "Избранное" (сохранённые лоты)
- Экспорт цен в CSV для анализа

---

**КОНЕЦ СПЕЦИФИКАЦИИ**
