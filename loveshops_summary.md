# LoveShops — Резюме проекта + Чеклист реализации

## 🎯 СУТЬ ПРОЕКТА

**LoveShops** — многоуровневая система торговли для Minecraft Paper 1.21+ с тремя типами NPC-торговцев:

| NPC | Функция | Доступность | Особенности |
|-----|---------|-------------|------------|
| **Скупщик (Buyer)** | Скупает предметы у игроков | Всегда | Переменная цена, репутация, штрафы |
| **Торговец-барахолка (Seller)** | Продаёт скупленные товары | Вс 10:00–18:00 | Наценка, защита от race conditions |
| **Аукционер (Auctioneer)** | Аукцион редких предметов | Вс–Пн (24ч) | Зарезервированная валюта, ставки |

---

## 📋 КЛЮЧЕВЫЕ МЕХАНИКИ

### 1. Скупщик (Buyer)
- ✅ Скупает ВСЕ предметы по конфигурируемой цене
- ✅ Цена варьируется ±30% случайно
- ✅ Штраф за повтор (макс. -50%)
- ✅ Бонус из LoveBehavior API репутации (+20%)
- ✅ Статусы: `default` / `good` / `bad` / `aggressive`
  - `bad` → отказ с кастомным сообщением
  - `aggressive` → отказ или сниженная цена
- ✅ Красивый GUI с пагинацией

### 2. Торговец-барахолка (Seller)
- ✅ Приходит в воскресенье **с 10:00 до 18:00**
- ✅ Продаёт всё, что сдавали скупщику на протяжении недели
- ✅ Наценка на товар (+15% из конфига)
- ✅ **Race condition protection**: если товар куплен, все видят красную голову
- ✅ Если товаров < 10, торговец не приходит
- ✅ GUI с пагинацией и live updates

### 3. Аукционер (Auctioneer)
- ✅ Редкие предметы на специальном аукционе
- ✅ Аукцион длится **24 часа** (вс 10:00 – пн 10:00)
- ✅ Шаг ставки: проценты от текущей (5%)
- ✅ **Зарезервированная валюта** — нельзя потратить при ставке
- ✅ Отмена ставки — вернуть зарезервированную валюту
- ✅ По завершении — выдать предмет победителю

---

## 🗄️ СТРУКТУРА БД (SQLite)

```
shops_npcs                    — описание всех NPC (координаты, имя, тип)
buyer_inventory               — предметы, поданные скупщику (для барахолки)
buyer_prices_history          — отслеживание сдач (для штрафов)
buyer_reputation_overrides    — кастомные статусы игроков (good/bad/agg)
auctions                      — активные/завершённые аукционы
auction_bids                  — история ставок на каждый аукцион
reserved_currency             — зарезервированная валюта при ставках
```

---

## 📦 ФАЙЛОВАЯ СТРУКТУРА

```
LoveShops/
├── pom.xml                                    [Maven конфиг]
├── plugin.yml                                 [Регистрация плагина]
├── src/main/java/dev/lovelace/loveshops/
│   ├── LoveShops.java                         [main class]
│   ├── api/LoveShopsAPI.java                  [public API]
│   ├── commands/ShopsCommand.java             [все команды]
│   ├── listeners/
│   │   ├── InventoryClickListener.java
│   │   └── ScheduleListener.java
│   ├── managers/
│   │   ├── NpcManager.java
│   │   ├── BuyerManager.java
│   │   ├── SellerManager.java
│   │   ├── AuctionManager.java
│   │   ├── PriceCalculator.java
│   │   └── CurrencyManager.java
│   ├── database/DatabaseManager.java
│   ├── gui/
│   │   ├── BuyerGui.java
│   │   ├── SellerGui.java
│   │   ├── AuctionGui.java
│   │   └── GuiUpdater.java
│   ├── models/
│   │   ├── NpcData.java
│   │   ├── AuctionData.java
│   │   ├── BidData.java
│   │   └── ReservedCurrency.java
│   ├── events/
│   │   ├── GuiBuyerItemClickEvent.java
│   │   ├── GUISellerItemClickEvent.java
│   │   └── AuctionBidPlacedEvent.java
│   ├── placeholders/LoveShopsPlaceholder.java
│   └── utils/
│       ├── ItemStackConverter.java
│       ├── TimeUtils.java
│       └── MessageUtils.java
├── config.yml                                 [конфиг]
└── lang.yml                                   [сообщения на русском]
```

---

## 🛠️ КОМАНДЫ

| Команда | Пермиссия | Функция |
|---------|----------|---------|
| `/loveshops npc create <type> <name>` | `loveshops.admin.create` | Создать NPC |
| `/loveshops npc delete` | `loveshops.admin.delete` | Удалить NPC (смотреть на него) |
| `/loveshops buyer <player> <status> [msg]` | `loveshops.admin.buyer` | Установить статус игрока |
| `/loveshops reload` | `loveshops.admin.reload` | Перезагрузить конфиг |

---

## 🎨 GUI ДИЗАЙН

### Скупщик (27 слотов)
```
[Закрыть]  [ Инфо ]  [Название]  [ Инфо ]  [Закрыть]
[◄◄ Пред ] [Предметы игрока] [Предметы] [◄◄ След ]
[    ]     [Предметы игрока] [Предметы] [    ]
[Отмена]   [       Предметы       ] [    Принять ]
```

### Торговец (45 слотов, 5 строк)
Аналогичный скупщику, но с товарами из `buyer_inventory`

### Аукционер (27 слотов)
```
[Назад] [Лот] [Лот] [Лот] [Лот]
[Лот ] [Лот] [Лот] [Лот] [Лот]
[Мои] [Активные] [История] [Завершённые] [Обновить]
```

---

## 📊 ПЛЕЙСХОЛДЕРЫ (PlaceholderAPI)

```
%loveshops_seller_arrival%          → "В 10:00" или "Завтра в 10:00"
%loveshops_seller_active%           → "true" / "false"
%loveshops_auction_count%           → кол-во активных аукционов
%loveshops_player_reserved%         → зарезервированная валюта игрока
%loveshops_item_<type>_price%       → базовая цена предмета
```

---

## 🔒 ЗАЩИТА ОТ АБУЗОВ

### Race Condition (Товар куплен одновременно двумя)
1. **SQL UNIQUE constraint** на `(buyer_inventory.id, sold_at)`
2. При ошибке в Java → GuiUpdater.broadcastGuiUpdate()
3. Все открытые GUI обновляются: проданный → RED SKULL
4. Сообщение: "§c✗ Этот товар уже куплен другим игроком"

### Превышение бюджета при ставке
1. Проверить баланс перед ставкой
2. Если недостаточно → отклонить
3. Валюта зарезервирована отдельно (не в инвентаре)

### Отказ плохорепутационным игрокам
1. Таблица `buyer_reputation_overrides`
2. При попытке продажи → проверить статус
3. Если `bad`/`aggressive` → кастомное сообщение отказа

---

## 🔄 ИНТЕГРАЦИЯ С LOVE* ECOSYSTEM

### LoveBehavior
- **Запрос:** `Bukkit.getServicesManager().load(LoveBehaviorAPI.class)`
- **Использование:** `getReputation(UUID)` → бонус к цене скупщика
- **Soft dependency** в `plugin.yml`

### ItemsAdder / ExecutableItems
- **Валюта:** кастомный предмет (coins)
- **API:** транзакции добавления/вычитания

### Bukkit ServicesManager
- Все расширения через ServiceRegistry
- Нет hardcoded зависимостей

---

## 🚀 ФАЗЫ РЕАЛИЗАЦИИ (10 фаз)

| Фаза | Задача | Статус |
|------|--------|--------|
| 1 | Scaffolding + Database (pom.xml, schema, config) | ⏳ Очередь |
| 2 | NPC Manager + Commands | ⏳ Очередь |
| 3 | Buyer Manager + Price Calculation | ⏳ Очередь |
| 4 | Buyer GUI + Click Listener | ⏳ Очередь |
| 5 | Seller Manager + Scheduling | ⏳ Очередь |
| 6 | Auction Manager + Reserve Currency | ⏳ Очередь |
| 7 | Auction GUI + Race Condition Protection | ⏳ Очередь |
| 8 | PlaceholderAPI + Events | ⏳ Очередь |
| 9 | Config Files + LangManager | ⏳ Очередь |
| 10 | Тестирование + Оптимизация | ⏳ Очередь |

---

## ✅ ЧЕКЛИСТ ТЕСТИРОВАНИЯ

### Скупщик
- [ ] Игрок открывает GUI скупщика
- [ ] Выбирает предмет из инвентаря
- [ ] Цена рассчитана правильно (base ± variance)
- [ ] Повторная сдача → цена ниже (штраф)
- [ ] Хорошая репутация → цена выше (+20%)
- [ ] Статус `bad` → отказ с сообщением
- [ ] Статус `aggressive` → отказ или -30%
- [ ] Предметы сохранены в `buyer_inventory`
- [ ] Монеты выданы игроку

### Торговец-барахолка
- [ ] В воскресенье 10:00 торговец спавнится
- [ ] В 18:00 исчезает
- [ ] Если < 10 товаров → не приходит
- [ ] Товары видны в GUI (со скидкой)
- [ ] Покупка работает
- [ ] При покупке → все видят RED SKULL
- [ ] Fallback: клик на проданный → сообщение об ошибке

### Аукцион
- [ ] Лоты видны в GUI аукционера
- [ ] Можно поставить ставку
- [ ] Валюта зарезервирована
- [ ] Минимальный шаг ставки соблюдён
- [ ] Можно отменить ставку
- [ ] Валюта вернута при отмене
- [ ] Аукцион завершается через 24 часа
- [ ] Предмет выдан победителю

### Команды
- [ ] `/loveshops npc create buyer Скупщик` работает
- [ ] `/loveshops npc delete` удаляет (смотря на NPC)
- [ ] `/loveshops buyer steve bad` сохраняется
- [ ] `/loveshops reload` перезагружает конфиг

### Перформанс
- [ ] Нет lag при открытии GUI
- [ ] DB операции асинхронны
- [ ] Нет утечек памяти
- [ ] Логирование ошибок работает
- [ ] TPS не падает при множественных покупках

---

## 📝 ИСПОЛЬЗОВАНИЕ ПРОМПТА ДЛЯ JETBRAINS AI

### Начало работы:

1. **Откройте** файл `loveshops_jetbrains_prompt.md`
2. **Скопируйте** весь текст в чат JetBrains AI Assistant
3. **Добавьте** к промпту содержимое из `loveshops_specification.md`
4. **Начните** с команды: `phase1-task1`
5. **Следуйте** инструкциям для каждой фазы

### Структура команд:

```
phase<N>-task<M>: <Описание задачи>
```

Например:
- `phase1-task1`: Создай pom.xml
- `phase2-task2`: Создай ShopsCommand.java
- `phase3-task3`: Реализуй calculateBuyPrice()

---

## 📖 ДОКУМЕНТАЦИЯ

Созданные файлы:

1. **loveshops_specification.md** — Полная спецификация (14 секций)
2. **loveshops_jetbrains_prompt.md** — Готовый промпт для JetBrains AI (10 фаз)
3. **loveshops_architecture.svg** — Диаграмма архитектуры
4. **loveshops_flows.md** — Flow-диаграммы 5 основных сценариев
5. **loveshops_summary.md** — Это резюме

---

## 🎓 СОБЛЮДЕНИЕ LOVE* ECOSYSTEM СТАНДАРТОВ

✅ **Async/Threading:**
- DB операции → `Bukkit.getAsyncScheduler()`
- Bukkit API → `Bukkit.getScheduler()` (main thread)

✅ **Cross-plugin Communication:**
- `ServicesManager` для LoveBehavior API
- Softdepend в plugin.yml

✅ **Configuration:**
- Все значения в config.yml
- Все сообщения в lang.yml
- Использование MiniMessage

✅ **Code Style:**
- Java 21 features (records, pattern matching, text blocks)
- `Component` вместо ChatColor
- Logging через `plugin.getLogger()`
- Обработка исключений по типам

---

## 🎯 СЛЕДУЮЩИЕ ШАГИ

1. **Готовь репозиторий** на GitHub (если ещё нет)
2. **Скопируй промпт** в JetBrains AI Assistant
3. **Начни с Фазы 1** — создание pom.xml, plugin.yml, DatabaseManager
4. **По завершении каждой фазы** — проверь в test server (Purpur 1.21)
5. **При вопросах** — уточняй в этом чате, я готов уточнить спец

---

**ГОТОВО К РЕАЛИЗАЦИИ! 🚀**
