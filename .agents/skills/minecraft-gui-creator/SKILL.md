---
name: minecraft-gui-creator
description: "Генерировать с нуля и переделывать (рефакторить) конфигурации и код GUI-меню для плагина DeluxeMenus, сторонних плагинов, а также для кастомных плагинов экосистемы Love (Paper API). При работе ты обязан строго соблюдать описанные ниже правила позиционирования слотов, цветовой палитры и логики навигации. Визуальный стиль и структура меню должны оставаться неизменными вне зависимости от целевого плагина или синтаксиса. ГУИ GUI рефакторинг переделка изменение улучшение доработка стандарт шаблон меню делюкс меню base64 value"
---

# 🎨 GUI Generator Skill v1.0 — UNIFIED STANDARD

Единый золотой стандарт для генерации, рефакторинга и валидации GUI-меню в плагине **DeluxeMenus**, сторонних плагинах и кастомных плагинах экосистемы **Love\*** (Paper API).

---

## 📋 ЗОЛОТЫЕ СТАНДАРТЫ (ВСЕГДА ВЫПОЛНЯЮТСЯ)

### 1. Стекло-заполнитель
- **Материал:** `GRAY_STAINED_GLASS_PANE` (без исключений).
- **Display name:** пустая строка с пробелом (`' '`).
- **Расположение:** границы, пустые слоты, фоновые слоты.

### 2. Слот 0 — ВСЕГДА уточнять или выбирать по правилу
- **Профиль напрямую (`head-%player_name%`):** `LoveAuth`, `LoveProfile`, персональный профиль игрока.
- **Профиль косвенно (`basehead-[Base64]`):** `LoveClans` (голова клана), `LoveHunt` (голова цели охоты), системный значок/тема.
- **Редкое исключение (пусто/стекло):** уточнять у пользователя при сомнении.

### 3. Слоты 1-8 (в 27- и 54-слотовых меню)
- **Слот 1:** Стекло ВСЕГДА (`GRAY_STAINED_GLASS_PANE`).
- **Слоты 2–7:** Кнопки управления (вкладки меню, разделы, переключатели).
- **Слот 8:** Стекло ВСЕГДА (`GRAY_STAINED_GLASS_PANE`).
- Никаких исключений.

### 4. Слоты 9-17
- **В 27-слотовых меню:** не используются (пусто/контент при необходимости).
- **В 54-слотовых меню:** **СТЕКЛО ВСЕГДА** (слоты 9–17 — сплошная полоса стекла для отделения верхней панели от рабочей зоны).

### 5. Рабочая зона (Слоты 18–44, только 54-слотовые меню)
- **Слот 18:** Стекло/Пусто (левая стенка)
- **Слоты 19–25:** Контент (7 слотов)
- **Слот 26:** Стекло/Пусто (правая стенка)
- **Слот 27:** Стекло/Пусто (левая стенка)
- **Слоты 28–34:** Контент (7 слотов)
- **Слот 35:** Стекло/Пусто (правая стенка)
- **Слот 36:** Стекло/Пусто (левая стенка) **ИЛИ** Пагинация ← (опционально)
- **Слоты 37–43:** Контент (7 слотов)
- **Слот 44:** Стекло/Пусто (правая стенка) **ИЛИ** Пагинация → (опционально)

### 6. Footer (Слоты 45–53)
- **Слоты 45–50:** Стекло (`GRAY_STAINED_GLASS_PANE`)
- **Слот 51:** Доп. кнопка (опционально, например, сброс/настройка) **ИЛИ** Стекло
- **Слот 52:** Кнопка «Назад» (Back) если Chained **ИЛИ** Стекло если Standalone
- **Слот 53:** Кнопка «Закрыть» (Close) **ВСЕГДА**

### 7. Контент ВСЕГДА использует головы (Base64 / Player Head)
- Все функциональные кнопки, профили, предметы и элементы = `basehead-[Base64]` с текстурой **ИЛИ** `%player_head%` / `head-%player_name%`.
- Только стекло-фон может использовать стандартный материал `GRAY_STAINED_GLASS_PANE`.

---

## 🎭 ИСКЛЮЧЕНИЯ ИЗ ПРАВИЛ

### Исключение 1: HOPPER МЕНЮ / Подтверждение (9 слотов / Hopper)
Случай: Окно подтверждения удаления/покупки/действия (Yes/No, Confirm/Cancel).

**Архитектура (9 слотов):**
`[0: Head/Topic] [1: Confirm ✓] [2: Glass] [3: Cancel ✗] [4: Glass] [5-8: Glass]`

**Пример (DeluxeMenus):**
```yaml
menu_title: '&cПодтверждение действия'
size: 9

items:
  'topic_head':
    material: head-%player_name%
    slot: 0
    display_name: '&eИнформация'
  
  'btn_confirm':
    material: basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19
    slot: 1
    display_name: '&aПодтвердить'
    left_click_commands:
      - '[close]'
      - '[console] command %player_name%'

  'btn_cancel':
    material: basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzYxODczMWUwNjMzNzlhZWJmODJmMWQ2NGM0MTljOTBkN2YwYzE2NDhjNTQ4ZTliNjE1MWIxYmFiYTY2ZDcyMyJ9fX0=
    slot: 3
    display_name: '&cОтмена'
    left_click_commands:
      - '[close]'

  'glass_rest':
    material: GRAY_STAINED_GLASS_PANE
    display_name: ' '
    slots: [2, 4, 5, 6, 7, 8]
```

### Исключение 2: ПАГИНАЦИЯ (54-слотовое)
Когда предметов больше, чем вмещает 1 страница (более 21 контентного слота).
- **Слот 36:** Кнопка «← Назад» (`basehead-[Arrow_Left]`)
- **Слот 44:** Кнопка «Вперёд →» (`basehead-[Arrow_Right]`)

```yaml
'pagination_prev':
  material: basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODRkZjJjZWZhZDQ4YzEwMDYzZDczNTM5OWY5MDRmYWE0NjA4ZmQ0NjZkZWYxZGU5ZTU1YjFhMzY2NWUzODYwMyJ9fX0=
  slot: 36
  display_name: '&6← Предыдущая страница'
  left_click_commands:
    - '[refresh]'

'pagination_next':
  material: basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWM4YzJhMDExYmU4ZTI2NDk4YjAzNmJjNDA3OTc3NDA4ODczYTYxYTc0MjYxMmM0OTdhMjI1MzU5YTMwYjRjZDMifX19
  slot: 44
  display_name: '&6Следующая страница →'
  left_click_commands:
    - '[refresh]'
```

### Исключение 3: ДОП. КНОПКА (54-слотовое, Слот 51)
Специальная функция в футере перед кнопками Back (52) и Close (53).
```yaml
'btn_extra_action':
  material: basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGVlN2I0YTMyMDJi...
  slot: 51
  display_name: '&cСбросить настройки'
  lore:
    - ''
    - '&7Вернуть стандартные параметры.'
    - '&aЛКМ &7— сбросить'
```

### Исключение 4: НЕСТАНДАРТНЫЕ МЕНЮ
Если меню имеет нестандартный размер (18, 36 слотов) или специфическую логику — **ОБЯЗАТЕЛЬНО УТОЧНИТЬ У ПОЛЬЗОВАТЕЛЯ**:
- Размер меню
- Назначение меню
- Количество элементов
- Логику слота 0

---

## 🎨 ВИД ОФОРМЛЕНИЯ И СТИЛЬ

### Цветовая палитра MiniMessage / Legacy &-кодов
- `&6` (gold) — Основные кнопки, названия разделов
- `&a` (green) — Активные вкладки, подтверждение, успешные статусы
- `&c` (red) — Опасные действия, отмена, закрытие
- `&7` (gray) — Неактивные кнопки, базовое описание lore
- `&9` (blue) — Служебные статусы, модерация, чины
- `&f` (white) — Значения переменных, числовые данные

### Структура Lore
```yaml
lore:
  - ''
  - '&7Описание функции или раздела.'
  - '&7Текущее значение: &f%value%'
  - '&aЛКМ &7— выбрать'
  - '&7ПКМ &7— сбросить'
```

---

## 🔑 БАЗОВЫЕ BASE64 ТЕКСТУРЫ (LIBRARY)

- **Close (Крестик):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ==`
- **Back (Стрелка назад):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmQ2OWUwNmU1ZGFkZmQ4NGU1ZjNkMWMyMTA2M2YyNTUzYjJmYTk0NWVlMWQ0ZDcxNTJmZGM1NDI1YmMxMmE5In19fQ==`
- **Confirm (Галочка ✓):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19`
- **Cancel (Крест ✗):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzYxODczMWUwNjMzNzlhZWJmODJmMWQ2NGM0MTljOTBkN2YwYzE2NDhjNTQ4ZTliNjE1MWIxYmFiYTY2ZDcyMyJ9fX0=`
- **Arrow Left (←):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODRkZjJjZWZhZDQ4YzEwMDYzZDczNTM5OWY5MDRmYWE0NjA4ZmQ0NjZkZWYxZGU5ZTU1YjFhMzY2NWUzODYwMyJ9fX0=`
- **Arrow Right (→):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWM4YzJhMDExYmU4ZTI2NDk4YjAzNmJjNDA3OTc3NDA4ODczYTYxYTc0MjYxMmM0OTdhMjI1MzU5YTMwYjRjZDMifX19`
- **Filter (Фильтр):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGViODFlZjg5MDIzNzk2NTBiYTc5ZjQ1NzIzZDZiOWM4ODgzODhhMDBmYzRlMTkyZjM0NTRmZTE5Mzg4MmVlMSJ9fX0=`
- **Settings (Шестерёнка/Настройки):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDBkNWE0ZWJhMTQwY2JiMzRkNDVlNDBkNjJkZDNmN2U2NTg0YjJhYjBiNDE1NWEwYmNjNjAzZDVkNzUwZjc5MyJ9fX0=`
- **Sorting (Сортировка):** `eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjU3YzdlOTZhODAyYzI3MDgwYzdmODA1MzgxNDM2OGVhOTRkZjg2NDQ1OTEyMGU1MTU1NzE4YjUwM2MzZWQ3In19fQ==`

---

## 📐 АРХИТЕКТУРА ПО РАЗМЕРАМ

### 27-слотовое меню (Стандартное)
```
Строка 0 (0-8):   [H] [S] [K] [K] [K] [K] [K] [K] [S]
Строка 1 (9-17):  [?] [K] [K] [K] [K] [K] [K] [K] [?]
Строка 2 (18-26): [S] [S] [S] [S] [S] [S] [S] [B] [C]

H = Голова (слот 0)
S = Стекло (GRAY_STAINED_GLASS_PANE)
K = Кнопка/Контент (слоты 2-7, 10-16)
B = Back (слот 25)
C = Close (слот 26)
```

### 54-слотовое меню (Большое)
```
Строка 0 (0-8):   [H] [S] [K] [K] [K] [K] [K] [K] [S]
Строка 1 (9-17):  [S] [S] [S] [S] [S] [S] [S] [S] [S]

Рабочая зона (18-44):
Строка 2 (18-26): [S] [K] [K] [K] [K] [K] [K] [K] [S]
Строка 3 (27-35): [S] [K] [K] [K] [K] [K] [K] [K] [S]
Строка 4 (36-44): [P/S] [K] [K] [K] [K] [K] [K] [K] [P/S]

Footer (45-53):   [S] [S] [S] [S] [S] [S] [Д/S] [B] [C]

P = Пагинация (←/→) опционально в 36/44
Д = Доп. кнопка (слот 51)
B = Back (слот 52)
C = Close (слот 53)
```

---

## ✅ ЧЕК-ЛИСТ ПЕРЕД ВЫДАЧЕЙ КОДА
- [ ] Слот 0: Уточнён или применён корректный профиль
- [ ] Слот 1: Стекло (`GRAY_STAINED_GLASS_PANE`)
- [ ] Слоты 2-7: Только кнопки управления / разделы
- [ ] Слот 8: Стекло (`GRAY_STAINED_GLASS_PANE`)
- [ ] Слоты 9-17 (для 54-слотового): Полностью застеклены
- [ ] Боковые слоты рабочей зоны (18, 26, 27, 35, 36, 44): Застеклены
- [ ] Контент: Использованы `basehead-[Base64]` или `%player_head%`
- [ ] Footer: Слот 52 (Back/Стекло), Слот 53 (Close)
- [ ] Нет незаполненных (пустых) слотов — всё застеклено
- [ ] Названия стекла: `' '` (один пробел)
