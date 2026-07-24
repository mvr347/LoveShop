# LoveShops — Промпт для JetBrains AI Assistant

## КОНТЕКСТ

Я разрабатываю плагин **LoveShops** для Minecraft Paper/Purpur 1.21+, часть экосистемы Love* plugins. Проект использует:
- **Java 21** с Paper API 1.21.1
- **Maven** для сборки (shaded SQLite driver)
- **SQLite** для хранения данных (в папке плагина)
- **LoveBehavior API** через Bukkit ServicesManager для интеграции репутации
- **Async scheduling** через Paper AsyncScheduler для DB операций
- **MiniMessage** для форматирования сообщений

## ПРОЕКТ СТРУКТУРА

```
LoveShops/
├── pom.xml
├── plugin.yml
└── src/main/java/dev/lovelace/loveshops/
    ├── LoveShops.java
    ├── api/LoveShopsAPI.java
    ├── commands/ShopsCommand.java
    ├── listeners/...
    ├── managers/...
    ├── database/DatabaseManager.java
    ├── gui/...
    ├── models/...
    ├── events/...
    ├── placeholders/...
    └── utils/...
```

## ПОЛНАЯ СПЕЦИФИКАЦИЯ

[Вставить содержимое из loveshops_specification.md целиком]

---

## ПРАВИЛА КОДИРОВАНИЯ (Love* Ecosystem)

1. **Архитектура:**
   - Все managers передают `plugin` в конструктор (нет статических синглтонов, кроме `getInstance()`)
   - DB операции ВСЕГДА async через `Bukkit.getAsyncScheduler()`
   - Bukkit API (Player, World, Inventory) ТОЛЬКО на main thread
   - Результаты async работы → main thread через `Bukkit.getScheduler().runTask()`

2. **Асинхрон:**
   ```java
   // ✅ Правильно:
   Bukkit.getAsyncScheduler().runNow(plugin, task -> {
       Optional<Data> data = databaseManager.query(...);
       Bukkit.getScheduler().runTask(plugin, () -> {
           player.sendMessage(...);  // Main thread
       });
   });
   
   // ❌ Неправильно:
   player.sendMessage(...);  // Async context!
   databaseManager.query();   // Main thread!
   ```

3. **Конфигурация:**
   - ALL hardcoded values → `config.yml`
   - ALL player messages → `lang.yml`
   - Использовать `plugin.getConfig().getString(...)` или `LangManager`

4. **Исключения:**
   - Ловить специфичные (SQLException, FileNotFoundException)
   - Логировать через `plugin.getLogger()`
   - Никогда не выбрасывать исключения в событийных listeners

5. **GUI:**
   - ItemStack building → helper методы (не inline)
   - Component для MiniMessage → `Component.text(...)`
   - Клики на предметы → `InventoryClickEvent` listener с проверкой по title

6. **Java 21 features:**
   - Records для data classes
   - Pattern matching в switch
   - Text blocks для SQL
   - CompletableFuture для async методов, возвращающих значение

---

## ФАЗЫ РЕАЛИЗАЦИИ

### ФАЗА 1: Scaffolding + Database
**Цель:** базовая структура проекта, схема БД, основные классы

**Задачи:**
1. Создать `pom.xml` с зависимостями (Paper 1.21.1, SQLite shaded)
2. Создать `plugin.yml` с командами и перм-ами
3. Создать `LoveShops.java` (основной класс с инициализацией)
4. Создать `DatabaseManager.java` с методом `initialize()` для создания всех таблиц
5. Создать `config.yml` и `lang.yml` по спецификации
6. Создать базовые model классы: `NpcData`, `AuctionData`, `BidData`, `ReservedCurrency`

**Проверка:** БД создаётся при первом запуске, все таблицы видны в файле `plugins/LoveShops/database.db`

---

### ФАЗА 2: NPC Manager + Commands
**Цель:** создание/удаление NPC, базовые команды

**Задачи:**
1. Создать `NpcManager.java`:
   - `createNpc(String type, String name, Location loc)` → CompletableFuture
   - `deleteNpc(UUID npcUuid)` → CompletableFuture
   - `getNpcsByType(String type)` → List<NpcData>
   - `getNpcNear(Location loc, double radius)` → Optional<NpcData>

2. Создать `ShopsCommand.java`:
   - `/loveshops npc create <type> <name>`
   - `/loveshops npc delete` (смотреть на NPC)
   - `/loveshops reload`
   - `/loveshops buyer <player> <status> [message]`
   - TabCompleter для всех подкоманд

3. Зарегистрировать команду в `LoveShops.java` и в `plugin.yml`

**Проверка:** команды работают, NPC добавляются в БД, видны при перезагрузке

---

### ФАЗА 3: Buyer Manager + Price Calculation
**Цель:** логика скупщика, расчёт цен с вариативностью и штрафами

**Задачи:**
1. Создать `PriceCalculator.java`:
   - `getBasePrice(ItemStack item)` → int (из config)
   - `getVariance()` → double (±30%)
   - `getPenaltyPercent(Player p, ItemStack item)` → double (повторенияz)
   - `getReputationBonus(Player p)` → int (из LoveBehavior API)
   - `calculateBuyPrice(Player p, ItemStack item)` → int (финальная цена)

2. Создать `BuyerManager.java`:
   - `canPlayerSell(Player p)` → boolean (проверить override статус)
   - `buyItem(Player p, ItemStack item)` → CompletableFuture<Void> (сохранить в БД, списать предмет, дать монеты)
   - `rejectPlayer(Player p, String status)` → void (отправить кастомное сообщение)
   - `trackItemSubmission(UUID playerUuid, ItemStack item)` → async (обновить penalty в БД)

3. Создать интеграцию с **LoveBehaviorAPI** через ServicesManager

**Проверка:** `calculateBuyPrice()` возвращает корректные цены с учётом всех модификаторов

---

### ФАЗА 4: Buyer GUI + Inventory Click Listener
**Цель:** GUI для скупщика, клики на предметы, покупка

**Задачи:**
1. Создать `BuyerGui.java`:
   - `open(Player p)` → открыть инвентарь 27 слотов
   - Построить предметы из инвентаря игрока (показать цену в лоре)
   - Пагинация (max 16 предметов на странице)
   - Кнопки навигации и принятия

2. Создать `InventoryClickListener.java`:
   - Ловить клики по title "Скупщик" или "Барахолка" или "Аукцион"
   - При клике на предмет: `calculateBuyPrice()` → `buyItem()`
   - При успехе: зелёная голова + сообщение
   - При отказе: чёрная голова + кастомное сообщение
   - Клики на кнопки: пагинация, закрытие, инфо

3. Создать кастомное событие `GuiBuyerItemClickEvent`

**Проверка:** GUI открывается, клики обрабатываются, предметы продаются, монеты выдаются

---

### ФАЗА 5: Seller Manager + Scheduling
**Цель:** торговец-барахолка, расписание прихода/ухода, GUI продажи

**Задачи:**
1. Создать `SellerManager.java`:
   - `spawnSeller()` → проверить кол-во предметов, спавнить NPC, отправить сообщение
   - `despawnSeller()` → удалить NPC из мира
   - `openSellerGui(Player p)` → показать предметы из `buyer_inventory` со скидкой
   - `sellItem(Player p, ItemStack item)` → CompletableFuture (удалить из buyer_inventory, дать монеты, обновить GUI всем)
   - `broadcastGuiUpdate()` → обновить GUI у всех, кто его открыл

2. Создать scheduler в `LoveShops.java`:
   - Каждый день в 10:00 (воскресенье): `sellerManager.spawnSeller()`
   - Каждый день в 18:00 (воскресенье): `sellerManager.despawnSeller()`
   - Использовать `Bukkit.getGlobalRegionScheduler()` или Timer

3. Создать `SellerGui.java` (аналог BuyerGui)

**Проверка:** в воскресенье в 10:00 торговец спавнится, в 18:00 исчезает, GUI работает

---

### ФАЗА 6: Auction Manager + Reserve Currency
**Цель:** аукцион на редкие предметы, ставки, зарезервированная валюта

**Задачи:**
1. Создать `AuctionManager.java`:
   - `createAuction(ItemStack item, int startingPrice)` → CompletableFuture<Integer> (вернуть auction_id)
   - `placeBid(Player p, int auctionId, int bidAmount)` → CompletableFuture (проверить средства, зарезервировать, сохранить ставку)
   - `cancelBid(Player p, int auctionId)` → CompletableFuture (вернуть зарезервированные монеты)
   - `completeAuction(int auctionId)` → async (выдать предмет победителю, списать монеты, разблокировать оставшиеся)
   - `getReservedAmount(Player p)` → int

2. Создать логику расчёта шага ставки:
   - Если type = "percentage": шаг = currentBid * (value / 100)
   - Если type = "fixed": шаг = value

3. Создать аукционер NPC и спавн лотов в воскресенье

4. Создать scheduler для завершения аукционов (каждый час проверять `ends_at`)

**Проверка:** можно создать аукцион, делать ставки, ставки зарезервированы, аукцион завершается

---

### ФАЗА 7: Auction GUI + GUI Update Protection
**Цель:** GUI для аукциона, live обновление, protection от race conditions

**Задачи:**
1. Создать `AuctionGui.java`:
   - Показать активные лоты с текущей ставкой и временем
   - Кнопка "Поставить ставку" → открыть anvil GUI для ввода суммы
   - Кнопка "Мои ставки" → показать список ставок игрока

2. Создать `GuiUpdater.java`:
   - При покупке в SellerGui: `broadcastGuiUpdate()` обновляет все открытые GUI
   - Заменить проданный предмет красной головой
   - Показать сообщение "Товар куплен"

3. Fallback protection:
   - При клике на проданный предмет: проверить в БД, если статус = "sold", отправить сообщение и отменить

**Проверка:** GUI обновляется, когда товар куплен другим игроком

---

### ФАЗА 8: PlaceholderAPI + Events
**Цель:** интеграция с PlaceholderAPI, кастомные события

**Задачи:**
1. Создать `LoveShopsPlaceholder.java`:
   - Зарегистрировать плейсхолдеры в `onEnable()`
   - Реализовать методы для всех плейсхолдеров из спецификации

2. Создать кастомные события:
   - `GuiBuyerItemClickEvent`
   - `GUISellerItemClickEvent`
   - `AuctionBidPlacedEvent`

3. Зарегистрировать listener для этих событий (для логирования, аналитики)

**Проверка:** плейсхолдеры работают, события вызываются

---

### ФАЗА 9: Config Files + LangManager
**Цель:** полные конфиг и язык файлы

**Задачи:**
1. Создать `config.yml` с полной структурой из спецификации
2. Создать `lang.yml` с сообщениями на русском
3. Создать `LangManager.java` для удобного доступа к переводам
4. Убедиться, что все messages идут из конфигов

**Проверка:** конфиги сохраняются, перезагружаются без ошибок

---

### ФАЗА 10: Тестирование + Оптимизация
**Цель:** убедиться, что всё работает правильно

**Задачи:**
1. Полный тест всех основных сценариев:
   - Скупщик покупает товар
   - Повторная сдача → штраф
   - Торговец приходит в воскресенье
   - Игрок покупает на барахолке
   - Другой игрок покупает же товар → race condition protection
   - Аукцион работает 24 часа
   - Можно ставить ставки, отменять, выигрывать

2. Логирование и дебаггинг
3. Оптимизация SQL запросов (индексы, batch operations)
4. Проверка на утечки памяти

**Проверка:** нет ошибок в логах, плагин стабилен

---

## НАЧАЛО: Фаза 1

**Начни с Фазы 1. Выполни ВСЕ задачи из неё.**

Я буду давать команды вида:
- `phase1-task1`: Создай pom.xml
- `phase1-task2`: Создай plugin.yml
- И т.д.

При каждой команде:
1. Выведи полный исходный код класса/файла
2. Объясни ключевые части
3. Укажи, какие зависимости/plugins нужны
4. Дай instructions для интеграции в проект

**НАЧИНАЕМ?**
