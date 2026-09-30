# LoveShops — Player Market (Рынок игроков): торговые точки

**Версия:** 1.4.0 (реализация в LoveShop#38–#41 и LoveClaims#33, отклонения — раздел 20; переработка 1.0.0 по сверке с кодом, 2026-09-30; + команды цен и модерации, раздел 18; + нарастающий шанс ограбления и групповая атака, раздел 6.4; + оформление glyph, раздел 19)
**Статус:** Черновик к утверждению — раздел 15 содержит допущения, которые нужно подтвердить
**Зависимости:** LoveShops (торговля), LoveClaims (аренда/защита точки), LoveCore (`LoveEconomy`, `TaxOracle`, `BehaviorLevels`, `ReputationOracle`), LoveBehavior (ставит службы в ядро), Citizens (NPC)

> Что изменилось относительно 1.0.0 и почему — раздел 16. Коротко: ТЗ приведено к тому, что реально есть в коде (валюта — физические монеты, репутация — две шкалы 0–6, налог — `TaxOracle`), «плоты» заменены **торговыми точками**, вымогательство и конфискация заменены **ограблением и стражей**, барахолка переделана в **Барахольщика**.

---

## 1. ОБЗОР

Рынок на спавне — набор **торговых точек**. Точка — это одна сущность на два плагина:

- **LoveClaims** отвечает за землю: аренда, срок, оплата, защита территории, флаги.
- **LoveShops** отвечает за торговлю: NPC-торговец, склад, касса, стража, сделки, рейтинг.

Игрок арендует точку → на ней появляется его NPC-торговец → он продаёт и скупает через GUI. Владелец не обязан быть онлайн: деньги копятся в кассе точки.

Остальные участники рынка:
- **Барахольщик** (переделка воскресной барахолки, раздел 9) — вход для новичков: любой игрок выставляет лут без аренды.
- **Скупщик, Аукционер, Странник, Военный торговец, Банкир** — существующие серверные NPC, **не меняются**. Ограбление и правила рынка игроков их не касаются.
- **Ежедневный скупщик** (раздел 9.3) — стимул для фармеров.

Репутация LoveBehavior определяет доступ к рынку игроков (раздел 3). Ограбление и стража — раздел 6.

---

## 2. ТОРГОВАЯ ТОЧКА: ДВА ПЛАГИНА, ОДИН ЖИЗНЕННЫЙ ЦИКЛ

### 2.1. Что уже есть в LoveClaims и что не подходит как есть

В LoveClaims уже есть аренда плотов: `Claim.isRentalPlot()` (`parentClaimId != null`), `RentalManager`, `RentalExpirationTask` (раз в минуту), NPC арендодателя и налоговика, GUI аренды/продления, таблички-индикаторы. Но:

1. **Нет типа плота.** Метки `MARKET_STALL` и секции `rental.market-stalls` в 1.0.0 не существует. Нужен `Claim.plotType`.
2. **Нет событий.** Состояние аренды меняется напрямую в **8+ местах** (`RentalPaymentGUI`, `TaxerGUI`, `RentalAbandonConfirmGUI`, `RentalExpirationTask`, `RentalCommand` ×4, `GuiListener`). Подписаться из LoveShops нечем.
3. **Своя валюта.** У LoveClaims своя `ItemCurrencyManager` (`rental.currency`, медный слиток), а валюта экосистемы — `LoveEconomy` из LoveCore.
4. **Оплата только онлайн-игроком.** `RentalExpirationTask` при офлайн-арендаторе просто пропускает списание (`renter == null → return`) — срок аренды при этом идёт.

### 2.2. Что нужно сделать в LoveClaims

1. **`Claim.plotType`**: `RENTAL_PLOT` (по умолчанию, поведение не меняется) и `TRADE_POINT`. Поле в `claims` с миграцией в `SQLiteStorage` (`ALTER TABLE … ADD COLUMN plot_type TEXT DEFAULT 'RENTAL_PLOT'`, миграция идемпотентна).
2. **Единая точка изменения аренды** в `RentalManager`, через которую идут все 8 мест:
   ```java
   boolean assign(Claim plot, UUID renter, long endTime, Player payerOrNull);   // сдача
   boolean extend(Claim plot, long extraMillis);                                // продление
   void release(Claim plot, ReleaseReason reason);                              // EXPIRED | ABANDONED | EVICTED | ADMIN
   ```
   Прямые `setOwnerUuid/setRentalEndTime` в GUI/командах заменяются вызовами этих методов. Ни одного вызова мимо них.
3. **Bukkit-события** (пакет `me.lovelace.loveclaims.api.event`), вызываются только из методов выше и только для `TRADE_POINT`:
   - `TradePointRentRequestEvent` — **отменяемое**, до списания оплаты. LoveShops отменяет для игрока с плохой репутацией или агрессивным стилем (LoveClaims про LoveBehavior ничего не знает).
   - `TradePointRentedEvent(UUID player, Claim point)`
   - `TradePointReleasedEvent(UUID player, Claim point, ReleaseReason reason)`
   - `TradePointExpiryWarningEvent(UUID player, Claim point, long millisLeft)` — за `warn-hours` до конца.
4. **Правила `TRADE_POINT`, которые нельзя обойти настройкой плота:**
   - не более `rental.trade-points.max-per-player` точек на игрока (по умолчанию **1**; общий лимит `max-rentals-per-player: 2` не затрагивается);
   - **нельзя добавлять участников/доверенных** (точка — личная; торговец не должен получать чужой доступ);
   - **флаги заблокированы**: `build/break` — нет даже для владельца (рынок — общая застройка спавна), `containers` — нет для всех кроме админов, `pvp` — настраивается глобально;
   - `RentalStrangerGUI`/`RentalPlayerGUI` для точек показывают данные торговой точки (цена, срок, лимит), а не обычного плота.
5. **Оплата аренды точки** — через `LoveEconomy` (`has/charge`), если LoveCore доступен; для `RENTAL_PLOT` остаётся как есть. Так на рынке одна валюта.
6. **Автопродление.** Пока владелец офлайн, монет в инвентаре нет. Поэтому LoveShops регистрирует в LoveClaims провайдера `TradePointRentPayer`:
   ```java
   interface TradePointRentPayer {
       /** Списать сумму аренды из кассы точки (без участия инвентаря). true — оплачено. */
       boolean payFromTill(Claim point, long amount);
   }
   ```
   Порядок оплаты при продлении: касса точки → инвентарь владельца (если онлайн) → не хватило → **льготный период** `grace-hours` (по умолчанию 12): точка закрыта, склад цел, потом `release(EXPIRED)`.
7. **Создание точки админом**: расширить существующий поток создания аренды флагом типа (`/rental create … type trade_point` — точный синтаксис определяется по `RentalCommand`), цена и срок задаются на самой точке, как у обычных плотов. Общий конфиг `rental.market-stalls` из 1.0.0 **не вводится** — всё на точке.

### 2.3. Что делает LoveShops

- **Мягкая зависимость**: без LoveClaims подсистема рынка игроков выключена (остальной LoveShops работает). Слушает события LoveClaims — **не** свой самодельный интерфейс `MarketStallRentalListener`.
- Таблица `trade_points` (раздел 7) хранит только торговую часть; ключ — `claim_id` точки LoveClaims. Запись создаётся при первом появлении `TRADE_POINT` (в том числе свободной — с табличкой «Свободно · аренда N»).
- На `TradePointRentedEvent` → создать NPC торговца (раздел 2.4), восстановить прокачку игрока (`player_trader_progress`), открыть точку.
- На `TradePointReleasedEvent` → закрыть точку, убрать NPC и стражу, **вернуть вещи и монеты** через таблицу `pending_returns` (раздел 2.5).

### 2.4. NPC торговца

`CitizensIntegration` сейчас умеет только искать NPC по взгляду и по id — создавать и удалять он **не умеет**, а существующие NPC LoveShops привязаны админом. Нужно написать:
- `createStallTrader(Location, UUID owner)` / `remove(npc)` в `CitizensIntegration` (Citizens API: `CitizensAPI.getNPCRegistry().createNPC(...)`, метка-владелец в data NPC);
- **восстановление после рестарта**: при `onEnable` сверить `trade_points.npc_citizens_id` с реестром Citizens; нет NPC у арендованной точки → пересоздать; NPC без арендованной точки → удалить (защита от осиротевших NPC и от дублей при перезагрузках);
- тип NPC `stall` (новый) в `NpcManager`/`CitizensListener`: клик по своему NPC → Owner GUI, по чужому → покупательский GUI. Существующие типы (`buyer`, `seller`, …) не трогать.

### 2.5. Возврат вещей

Возвращать в инвентарь нельзя: владелец может быть офлайн, а инвентарь полон. Всё, что возвращается (остаток склада, касса, деньги за стражу), пишется в `pending_returns`. Забирает игрок у Барахольщика (вкладка «Мои вещи», раздел 10.4) — единое место, работает при любом состоянии точки.

---

## 3. РЕПУТАЦИЯ (LoveBehavior через LoveCore)

Строковых уровней `excellent/good/neutral/bad/aggressive` и `getReputationLevel()` **не существует**. Используются службы ядра:

- `BehaviorLevels#politenessLevel(uuid)` — вежливость, 0 (Ужасно) … 6 (Максимум);
- `BehaviorLevels#playstyleLevel(uuid)` — стиль игры, 0 (Агрессивный) … 6 (Добрый);
- без LoveBehavior обе шкалы возвращают нейтраль (3) — игрок ни изгой, ни агрессор.

### 3.1. Классы игрока

| Класс | Условие (настраивается) | По умолчанию |
|---|---|---|
| **Изгой** | `politenessLevel ≤ outcast-max-politeness` | 1 (Ужасно, Плохо — как `GatedAction` в `TaxOracle`) |
| **Агрессор** | `playstyleLevel ≤ aggressive-max-playstyle` | 2 (как `aggressive-threshold` у Странника) |
| **Образцовый** | `politenessLevel ≥ perfect-min-politeness` и `playstyleLevel ≥ good-min-playstyle` | 6 и 5 |
| Обычный | всё остальное | |

Изгой проверяется раньше агрессора. Классы не пересекаются в ответах: игрок — изгой, либо агрессор, либо обычный/образцовый.

### 3.2. Правила

| Класс | Аренда точки | Барахольщик | Чужая палатка | Серверные NPC (скупщик и др.) |
|---|---|---|---|---|
| Образцовый | да | **без налога** | да, без налога с его продаж | как сейчас |
| Обычный | да | налог | да | как сейчас |
| Агрессор | **нет** | редко (шанс) | **редко + ограбление** (раздел 6) | **как сейчас** |
| Изгой | **нет** | отказ | **отказ** | **как сейчас** |

«Как сейчас» — рынок игроков не меняет поведение существующих NPC (у военного торговца и странника агрессоры, наоборот, привилегированы; отказ скупщика — по ручному статусу админа).

Отказ изгою — обычный «шлёт»: короткая реплика NPC, без штрафов и без кулдауна. Флаг `market.gates.enabled` отключает все проверки разом (для тестов).

### 3.3. `ReputationGate`

```java
public final class ReputationGate {
    enum PlayerClass { OUTCAST, AGGRESSOR, PERFECT, NORMAL }
    PlayerClass classify(UUID player);          // единственный источник правды по правилам 3.1
    boolean canRent(UUID player);               // OUTCAST/AGGRESSOR -> false
    boolean isTaxExempt(UUID player);           // PERFECT
}
```
Реализация — поверх `LoveCore.service(BehaviorLevels.class)`. Никакой рефлексии на `LoveBehaviorAPI` (пакета `dev.lovelace.lovebehavior.api` не существует; в `WandererManager` остался устаревший рефлексивный путь — отдельная чистка, не часть этой задачи).

---

## 4. ВАЛЮТА И НАЛОГ

### 4.1. Валюта

Валюта — физические монеты в инвентаре (`LoveEconomy`), только у онлайн-игрока. Следствия, обязательные к реализации:

1. **Продавец мог уйти в офлайн** → его выручка не выдаётся, а накапливается в **кассе точки** (`trade_points.till_coins`). Владелец забирает кассу в Owner GUI.
2. **Выдача** только после `LoveEconomy#canFit(player, amount)`; не помещается — кнопка отказывает с сообщением. (`give` при нехватке места роняет остаток под ноги — для кассы недопустимо.)
3. **Оплата покупателя**: `has` → `charge`. `charge` умеет разменивать, поэтому суммы — в единицах валюты (`long`).
4. Монеты нельзя выставлять как товар (`LoveEconomy#isCoin(stack)` → отказ), иначе слот склада превращается в обменник.

### 4.2. Налог

- Налог платит **продавец с выручки** (покупатель платит ровно цену листинга). Удержанное **уничтожается** (сток валюты); отдельного «налогового счёта» нет.
- Ставка = `TaxOracle#tradeRate(sellerUuid)` — вежливость/стиль игры, для сделок между игроками уже смягчена вдвое. Плоские 7% из 1.0.0 **отменены**: они наложились бы на `TaxOracle` вторым налогом.
- **Образцовый продавец** (3.1) — ставка 0. Так же на Барахольщике.
- Округление: `tax = round(total × rate)`, `sellerGets = total − tax`; для любой сделки `sellerGets ≥ 0` и `tax + sellerGets == total` (тест).
- Если продавец — владелец палатки и офлайн, ставка берётся по его данным `BehaviorLevels` (служба работает с UUID и кэшем; если игрока нет в кэше — нейтраль). Это не должно падать для офлайн-UUID.

---

## 5. ПАЛАТКА: МЕХАНИКА

### 5.1. Слоты, склад, касса

- Продажа: **5** слотов, Скупка: **5** слотов (+1 на уровень прокачки, максимум `max-level` 10 → 14 слотов, помещается в 21 слот рабочей зоны без пагинации).
- **Склад** (`stall_stock`): товары для продажи и запас под скупку — по строке на стек, а не одним JSON-блобом (блоб теряет обновления при конкурентной записи).
- **Скупка** — заказ: предмет, цена за штуку, лимит количества; монеты на выкуп берутся **из кассы**; касса пуста — заказ приостановлен.
- Всё, что лежит на складе, «принадлежит» точке, а не инвентарю игрока: прямого доступа к контейнерам нет (флаги LoveClaims запрещают).

### 5.2. Прокачка

`player_trader_progress` хранится за игроком, а не за точкой: сменил точку — уровень сохранился. Улучшения: слоты, лимиты скупки, вместимость склада, внешний вид NPC. Стоимость: `upgrade-cost-base × multiplier^(level-1)`, только из **кассы** или инвентаря самого владельца.

### 5.3. Открыто / Закрыто

Флаг `is_open`. Закрытая точка не торгует, табличка «Закрыто», зрители GUI закрываются. Причины закрытия различаются (`close_reason`):
- `OWNER` — закрыл сам, открывает сам;
- `ROBBERY` — ограбление (раздел 6), **владелец должен открыть вручную**;
- `RENT_GRACE` — льготный период аренды;
- `REPUTATION` — репутация владельца стала «изгой/агрессор» (открыть нельзя, пока не выправится; вещи не изымаются);
- `ADMIN` — закрыл админ.

### 5.4. Что убрано из 1.0.0

- **Автоконфискация вещей при плохой репутации владельца** — убрана: наказание чрезмерно, а в 1.0.0 оно опиралось на несуществующие уровни. Остаётся закрытие (`REPUTATION`) и админская команда изъятия.
- **Вымогательство** как отдельный сервис — заменено ограблением палаток (раздел 6); серверные NPC не вымогаются.

---

## 6. ОГРАБЛЕНИЕ И СТРАЖА

Касается только NPC палаток. Барахольщик, Скупщик, Аукционер, Банкир, Военный торговец и Странник — иммунны.

### 6.1. Клик по NPC палатки

Порядок проверок при клике (покупательский вход):

1. Точка закрыта → сообщение «закрыто».
2. **Изгой** → отказ («шлёт»), дальше не идём.
3. **Агрессор** →
   1. на точке **нет активной стражи**: клик засчитывается в «доставание» (6.4) — растущий шанс **ограбления** (6.2); если не выпало, бросок `refuse-chance` (0.75) → отказ с репликой «терпения» (6.4), иначе — обычная торговля (то есть агрессор торгует «редко»);
   2. на точке **активная стража**: ограбление невозможно вообще; бросок `refuse-chance` остаётся; каждая попытка (клик, закончившийся отказом) — в счётчик «приставаний» (`harassment`) пары (игрок, точка).
4. Образцовый/обычный → обычная торговля.

Кулдауны: пара (игрок, точка) — ограбление не чаще `robbery-cooldown-hours` (6); после успешного ограбления этот игрок для этой точки в отказе `hostility-minutes` (30).

### 6.2. Ограбление

Успех атомарно (одна транзакция БД + выдача игроку на главном потоке, с журналом как в 8.2):
1. Точка закрывается: `is_open = 0`, `close_reason = ROBBERY`; NPC/табличка визуально закрыты; **владелец обязан открыть её вручную** (переключатель в Owner GUI), автооткрытия нет.
2. Грабитель получает **или деньги, или вещи** (не то и другое сразу): бросок `coins-chance` (0.5) выбирает вид добычи; если выбранного нет (касса пуста / на продаже ничего нет) — берётся другой вид. Деньги: до `max-till-percent` (15) кассы. Вещи: до `max-stock-percent` (10) стеков **с продажи** (склад скупки и монеты-заказы не трогаем), но не более `max-items` стеков. Забранное отдаётся **грабителю**: сначала в инвентарь, что не поместилось — падает под ноги (для монет — `LoveEconomy#give`, которая сама роняет остаток; для вещей — `dropItemNaturally`). Если брать нечего вообще (касса 0 и продажа пуста) — ограбления не происходит: NPC говорит «у меня нечего взять», точка **не закрывается**, попытка дня расходуется.
3. Владельцу — сообщение (онлайн) или запись в почту при входе (`owner_notices`): кто, что забрано, как открыть.
4. Грабителю: `ReputationOracle#modify(uuid, −robbery-reputation-penalty)` (по умолчанию 5).
5. Запись в `robbery_log` (кто, где, что и сколько) — для модерации и админской команды восстановления (`/loveshopsadmin point restore <id>`).
6. Дневной потолок: не более `max-robberies-per-day` (3) успешных ограблений на игрока — защита от массового сбора.
7. Нельзя грабить свою точку и точку «связанного» аккаунта (проверка связи — раздел 8.3; если связь определить нельзя, проверка пропускается и это фиксируется как ограничение).

### 6.3. Стража

Платная охрана точки, оплата **как зарплата** — регулярно, а не разово.

- **Найм**: вкладка «Стража» в Owner GUI. Стоимость зарплаты — `guard.salary` за период `guard.salary-period-hours` (по умолчанию 24 ч). Стража — второй NPC Citizens рядом с палаткой (скин из конфига), убирается при увольнении, окончании аренды и просрочке зарплаты.
- **Оплата**: в конце каждого периода списывается из **кассы точки**, если не хватает — из инвентаря владельца (если он онлайн). Не хватило нигде → стража уходит (`guard_state = UNPAID`), защита выключена, владельцу сообщение при входе. **Долга нет** — зарплата не копится.
- **Защита**: при активной страже (6.1.3.2) ограбление невозможно; NPC не отдаёт товар и не закрывает магазин.
- **«Если долго докапываться — выкинут»**: `harassment ≥ harassment-limit` (5 за `harassment-window-minutes`, 10) → игрок телепортируется на выход рынка (`market.exit` в конфиге, мир/координаты) и получает **бан на эту точку** на `harassment-ban-minutes` (30). Кик с сервера — **нет** (это лишает сервер игрока за клики по NPC; выкидывают с рынка, не с сервера).
- Стража не защищает от админа и не снимает рейтинг/налог.
- v1.1: одна стража на точку (`max-guards-per-point: 1`); уровни стражи — фаза D.

### 6.4. Нарастающий шанс, дневной лимит и групповая атака (точка без стражи)

**Доставание за день.** «День» — период от `day-reset-hour` (по умолчанию 4:00 по времени сервера) до следующего. Для каждой пары (агрессор, точка) хранится `clicks_today` и `day_locked` (раздел 7).

1. Каждый клик агрессора по NPC точки без стражи увеличивает `k = clicks_today` (учитываются только клики, которые дошли до броска — не забаненные и не на закрытой точке).
2. Индивидуальный шанс ограбления растёт **понемногу** с каждым кликом:
   `p(k) = min(chance-cap, base-chance + step × (k − 1))`, по умолчанию `base-chance = 0.01`, `step = 0.005`, `chance-cap = 0.20`.
3. **Бюджет дня**: `attempts-per-day` кликов (по умолчанию 12). Если бюджет исчерпан и ограбление не выпало — `day_locked = 1`: **до смены дня ограбления от этого игрока у этой точки не будет**, а NPC отвечает «сегодня я тебя больше слушать не буду» (не торгует и не грабится). Так «чем чаще, тем выше», но бесконечный спам сегодня ничего не даёт.
   Ориентир при значениях по умолчанию: шанс, что игрок в одиночку получит ограбление за один день, `1 − ∏(1 − p(k)) ≈ 37%` (посчитать в тесте, а не полагаться на оценку).
4. Итоговый бросок при клике — по формуле групповой атаки ниже (для одиночки она равна `p(k)`).

**Групповая атака.** Когда к одной точке одновременно пристают несколько агрессоров, шанс растёт, но по **убывающей** отдаче и никогда не достигает 100%:

- Множество атакующих `A` — агрессоры, у которых на этой точке был зачтённый клик за последние `co-attack-window-minutes` (по умолчанию 15) и не стоит `day_locked`.
- Шанс на клик: `P = 1 − ∏_{i ∈ A} (1 − p_i)`, где `p_i` — текущий индивидуальный шанс каждого (`last_chance`), у кликнувшего — только что пересчитанный.
- Это ровно нужное свойство: двое с `p` дают `2p − p²` (почти вдвое, но чуть меньше), чем ближе к 100% — тем слабее вклад нового участника (`ΔP = p_new × (1 − P_old)`), и `P < 1` при любом числе атакующих.
- Дополнительный жёсткий потолок `max-combined-chance` (0.90) — защита от округления и от «армии альтов».
- **Добычу получает тот, чей клик выпал** (остальным ничего); ограбление закрывает точку для всех (6.2).
- Не более `max-robberies-per-point-per-day` (2) успешных ограблений одной точки за день — иначе группа выжимает кассу досуха, пока владелец не успел открыть.

**«Терпение» NPC.** Каждый клик агрессора сопровождается репликой по стадии `patience = 1 − k / attempts-per-day`:
- `> 0.66` — спокойно («Слушай, мне надо работать…»);
- `0.33 … 0.66` — раздражён («Хватит, я сказал…»);
- `< 0.33` — предупреждение («Ещё раз — и позову стражу!» — фигурально, стражи нет; или «У меня тут не так много, отвали»);
- ограбление — реплика «Забирай и уходи!» (6.2);
- `day_locked` — «Сегодня я тебя больше слушать не буду».
У точки **со стражей** стадии считаются по `harassment / harassment-limit` (6.3), последняя — «Стража, выведите его!» и выкидывание с рынка.

Все реплики — списки (случайная), в `lang.yml`, с оформлением из раздела 19.

**Антиобход.**
- Смена аккаунта/альт под тот же день: границы по игроку, а не по точке, но общее число ограблений точки в день ограничено (`max-robberies-per-point-per-day`), а групповая формула не даёт 100%.
- Перезаход не сбрасывает счётчик: он в БД, а не в памяти сессии.
- Изменение времени/`day-reset-hour` в горячей перезагрузке не открывает новый день до фактической смены `day_key`.

---

## 7. SQL-СХЕМА (SQLite)

Схема — дополнение к `shops_npcs`, `buyer_*`, `auctions`, `seller_price_state`, `wanderer_*`, `banker_*`. Все создаются через `CREATE TABLE IF NOT EXISTS`; индексы — отдельными `CREATE INDEX IF NOT EXISTS`. `AUTOINCREMENT` не обязателен; блокировки — раздел 8.

```sql
CREATE TABLE IF NOT EXISTS trade_points (
    claim_id        TEXT PRIMARY KEY,           -- Claim.getId() из LoveClaims
    owner_uuid      TEXT,                       -- NULL пока свободна
    npc_citizens_id INTEGER,                    -- торговец
    guard_citizens_id INTEGER,                  -- стража
    level           INTEGER NOT NULL DEFAULT 1,
    sell_slots      INTEGER NOT NULL DEFAULT 5,
    buy_slots       INTEGER NOT NULL DEFAULT 5,
    is_open         INTEGER NOT NULL DEFAULT 0,
    close_reason    TEXT,                       -- OWNER | ROBBERY | RENT_GRACE | REPUTATION | ADMIN
    till_coins      INTEGER NOT NULL DEFAULT 0, -- касса, в единицах валюты
    guard_state     TEXT NOT NULL DEFAULT 'NONE', -- NONE | ACTIVE | UNPAID
    guard_paid_until INTEGER NOT NULL DEFAULT 0,
    rented_at       INTEGER,
    version         INTEGER NOT NULL DEFAULT 0  -- счётчик, +1 при каждой записи
);
CREATE INDEX IF NOT EXISTS idx_points_owner ON trade_points(owner_uuid);

CREATE TABLE IF NOT EXISTS stall_listings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    point_id        TEXT NOT NULL,
    type            TEXT NOT NULL,              -- SELL | BUY
    slot_index      INTEGER NOT NULL,
    item_data       TEXT NOT NULL,              -- ItemStackConverter (base64), снимок ДО списания
    item_hash       TEXT NOT NULL,
    unit_price      INTEGER NOT NULL,
    stock           INTEGER NOT NULL DEFAULT 0, -- SELL: в наличии; BUY: сколько уже выкуплено
    max_amount      INTEGER,                    -- BUY: лимит выкупа
    active          INTEGER NOT NULL DEFAULT 1,
    UNIQUE(point_id, type, slot_index)
);

-- Склад. Одна строка на предмет: нет блоба «весь склад одним JSON».
CREATE TABLE IF NOT EXISTS stall_stock (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    point_id        TEXT NOT NULL,
    item_data       TEXT NOT NULL,
    item_hash       TEXT NOT NULL,
    amount          INTEGER NOT NULL CHECK(amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_stock_point ON stall_stock(point_id);

CREATE TABLE IF NOT EXISTS stall_ratings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    point_id        TEXT NOT NULL,
    rater_uuid      TEXT NOT NULL,
    stars           INTEGER NOT NULL CHECK(stars BETWEEN 1 AND 5),
    comment         TEXT,
    trade_amount    INTEGER NOT NULL,
    weight          REAL NOT NULL DEFAULT 1.0,
    hidden          INTEGER NOT NULL DEFAULT 0, -- скрыто модератором
    created_at      INTEGER NOT NULL,
    UNIQUE(point_id, rater_uuid)
);

CREATE TABLE IF NOT EXISTS market_transactions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    kind            TEXT NOT NULL,              -- SALE | BUYOUT | FLEA | ROBBERY | RETURN
    point_id        TEXT,                       -- NULL для Барахольщика
    seller_uuid     TEXT,
    buyer_uuid      TEXT,
    item_hash       TEXT,
    amount          INTEGER,
    price           INTEGER,
    tax             INTEGER,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_point ON market_transactions(point_id, created_at);

-- Журнал незавершённых сделок: восстановление после краша между шагами (раздел 8.2).
CREATE TABLE IF NOT EXISTS pending_trades (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    buyer_uuid      TEXT NOT NULL,
    point_id        TEXT,
    listing_id      INTEGER,
    amount          INTEGER NOT NULL,
    total           INTEGER NOT NULL,           -- сколько монет списано/должно быть списано
    state           TEXT NOT NULL,              -- RESERVED | CHARGED | DONE | ROLLED_BACK
    created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pending_returns (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid     TEXT NOT NULL,
    item_data       TEXT,                       -- NULL для строки с монетами
    amount          INTEGER NOT NULL,           -- штук, либо монет (если item_data NULL)
    reason          TEXT,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_returns_player ON pending_returns(player_uuid);

CREATE TABLE IF NOT EXISTS owner_notices (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid     TEXT NOT NULL,
    message         TEXT NOT NULL,
    created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS player_trader_progress (
    player_uuid     TEXT PRIMARY KEY,
    level           INTEGER NOT NULL DEFAULT 1,
    total_sold      INTEGER NOT NULL DEFAULT 0,
    total_bought    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS robbery_state (        -- кулдауны, враждебность, приставания, баны
    player_uuid     TEXT NOT NULL,
    point_id        TEXT NOT NULL,
    robbery_until   INTEGER NOT NULL DEFAULT 0,
    hostile_until   INTEGER NOT NULL DEFAULT 0,
    harassment      INTEGER NOT NULL DEFAULT 0,   -- приставания к точке СО стражей (6.3)
    harassment_since INTEGER NOT NULL DEFAULT 0,
    banned_until    INTEGER NOT NULL DEFAULT 0,
    day_key         TEXT NOT NULL DEFAULT '',     -- игровой «день» доставания, см. 6.4 (yyyy-MM-dd с учётом day-reset-hour)
    clicks_today    INTEGER NOT NULL DEFAULT 0,   -- зачтённые клики за этот день БЕЗ стражи
    last_chance     REAL NOT NULL DEFAULT 0,      -- текущий индивидуальный шанс p_i (для групповой атаки)
    last_click_at   INTEGER NOT NULL DEFAULT 0,
    day_locked      INTEGER NOT NULL DEFAULT 0,   -- 1 = лимит дня исчерпан, ограбления от этого игрока сегодня не будет
    PRIMARY KEY (player_uuid, point_id)
);

CREATE TABLE IF NOT EXISTS robbery_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    robber_uuid     TEXT NOT NULL,
    point_id        TEXT NOT NULL,
    owner_uuid      TEXT NOT NULL,
    coins           INTEGER NOT NULL,
    items_json      TEXT,                       -- что забрано, для админского восстановления
    restored        INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS flea_listings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_uuid     TEXT NOT NULL,
    item_data       TEXT NOT NULL,
    item_hash       TEXT NOT NULL,
    unit_price      INTEGER NOT NULL,
    amount_left     INTEGER NOT NULL,
    active          INTEGER NOT NULL DEFAULT 1,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_flea_seller ON flea_listings(seller_uuid);
```

Записи `robbery_state` чистятся по времени (запись, у которой все `*_until` в прошлом и `harassment = 0`, удаляется задачей раз в час; при смене `day_key` счётчики дня обнуляются при первом же клике, отдельной задачи не нужно) — иначе таблица растёт неограниченно (урок из аудита памяти).

---

## 8. ДЮПЫ, ГОНКИ, АБУЗЫ

### 8.1. Что в 1.0.0 было неверно

- `SELECT … FOR UPDATE` в SQLite нет → `BEGIN IMMEDIATE`.
- `db.transactionAsync(...)` в `DatabaseManager` нет: он синхронный, `getConnection()` открывает соединение на каждый вызов (WAL, `busy_timeout=5000`).
- `trade_token` не защищает от дюпа: реальную защиту дают транзакция и проверка `active/stock`. Токен как «одноразовый ключ подтверждения» оставлен только для многошаговых окон (подтверждение крупной покупки).

### 8.2. Порядок атомарной сделки (физические монеты + БД)

Инвентарь Bukkit — только главный поток, БД — отдельно; две системы одной транзакцией не объединить, поэтому порядок такой:

1. **Главный поток**: проверки (открыто, класс покупателя, не своя точка, не забанен, `has`).
2. **БД `BEGIN IMMEDIATE`**: перечитать листинг и точку; проверить `active`, `stock ≥ amount`, `is_open`, `version`; уменьшить `stock`; записать `pending_trades (RESERVED)`; `COMMIT`. Не прошло → сообщение «уже куплено», ничего не тронуто.
3. **Главный поток**: `LoveEconomy#charge(buyer, total)`. Не хватило/ошибка → **компенсация**: вернуть `stock`, `pending_trades → ROLLED_BACK`.
4. **Главный поток**: выдать товар (`addItem`; если не помещается — отказ на шаге 1 по `canFit`-аналогу для предметов).
5. **БД `BEGIN IMMEDIATE`**: `till_coins += sellerGets`, `market_transactions`, `player_trader_progress`, `pending_trades → DONE`; `COMMIT`.

**Восстановление при `onEnable` и по таймеру**: для `RESERVED` старше `pending-timeout-seconds` (по умолчанию 30) вернуть `stock` (монеты не списаны); для `CHARGED` без `DONE` — довести до конца (товар выдать при входе игрока или вернуть монеты в `pending_returns`). Тот же приём, что в escrow-reconcile LoveDuels.

### 8.3. Прочие абузы

| Абуз | Защита |
|---|---|
| Слив рейтинга | одна оценка на пару (`UNIQUE`), только после сделки на сумму ≥ `min-trade-amount`, кулдаун 48 ч, вес аккаунта < 7 дней = 0.3, нет оценки своей точки, модератор может скрыть (`hidden`) |
| Альты/один IP | проверять связь аккаунтов, **если** в экосистеме есть общий источник (LoveAuth хранит адреса, но публичного API для этого не проверял — уточнить до реализации); иначе проверка пропускается и в документации фиксируется как ограничение |
| Демпинг | `min-price` по материалу/кастомному id в конфиге |
| Забивание слотов мусором | лимит листингов, кнопка выставления требует наличия предмета |
| Торговля с самим собой | владелец не покупает у своей точки; связанные аккаунты — по IP-проверке выше |
| Продажа монет как товара | `LoveEconomy#isCoin` → отказ |
| Спам открытием GUI | rate-limit 1 открытие/сек на игрока; кэш кулдаунов с чисткой (не растущая карта) |
| Прямой доступ к сундуку | нет контейнеров вообще: склад в БД; флаги LoveClaims запрещают `containers` |
| Прокачка за чужие деньги | только касса/инвентарь владельца |
| Дюп кликами GUI | у GUI свой `InventoryHolder`, **все** клики отменяются, разрешены только явные обработчики; отмена `COLLECT_TO_CURSOR`, `MOVE_TO_OTHER_INVENTORY`, `NUMBER_KEY`, `SWAP_OFFHAND`, drag; закрытие GUI во время операции откатывает |
| Ограбление как сбор | дневной потолок, кулдауны, журнал, админское восстановление |

Дублировать `DupeGuardListener` LoveClaims не нужно; рынок только дополняет защиту на уровне сделок.

---

## 9. БАРАХОЛЬЩИК (заменяет воскресную барахолку) И СКУПЩИК ДНЯ

### 9.1. Барахольщик

**Решение: барахолка переделывается.** Тип NPC в БД остаётся `seller` (совместимость данных и команд `event flea`), в игре — «Барахольщик».

- Один заметный NPC на рынке, **постоянный** (без воскресного расписания; расписание становится опцией `flea.schedule.enabled`).
- **Игроки выставляют лут без аренды**, продают друг другу через него: таблица `flea_listings`, лимит `flea.max-listings-per-player` (10), минимальная цена `min-prices`.
- **Налог**: `TaxOracle#tradeRate(seller)`, для **образцового** продавца — 0 (3.1, 4.2).
- **Доступ**: изгой — отказ, агрессор — редкий шанс; Барахольщик иммунен к ограблению.
- **Серверный сток остаётся**: товары, которые Скупщик принял и направил по каналу `seller` (`BuyerManager`, `seller_price_state`, `PriceCalculator`), Барахольщик по-прежнему продаёт как «предложения сервера» в отдельной вкладке. Экономический цикл скупщик → барахолка сохраняется.
- Без прежнего расписания «10:00–18:00, порог `min-items-to-spawn`» остаётся, только если `flea.schedule.enabled: true` (админ-управление `event flea` продолжает работать).

### 9.2. Вкладки Барахольщика (Вк)

«Игроки», «Сервер», «Мои лоты», «Мои вещи» (`pending_returns`, 10.4).

### 9.3. Ежедневный скупщик

Раз в сутки на `daily-buyer.duration-hours` (6) скупает заданные категории по цене чуть выше скупщика; лимит на игрока в сутки; ассортимент меняется. Категории — отдельная секция/файл; расписание и выдача — как у существующих событий LoveShops (`ScheduleListener`).

---

## 10. GUI (стандарт gui_gen v2.1, особенно пункт 8)

Общие правила: размеры 27/36/45/54 (18 нельзя); стекло только в Header и Footer; в Header/Footer нет пустых слотов; Row1 у 45/54 — полностью стекло; Footer — 1 ряд; **Вк только в Header, слоты 2–7**, с центрированием по количеству (1→{4}, 2→{3,5}, 3→{2,4,6}, 4→{2,3,5,6}, 5→{2,3,4,6,7}, 6→{2..7}); рабочая зона — только контент, боковые стенки пусты; пагинация только в 54-слотовом (слоты 36/44). Footer: позиции 1–5 стекло, **Д** (доп. кнопка), **B** (назад, стекло если standalone), **C** (закрыть, всегда).

В 1.0.0 владельческое меню («два ряда по пять кнопок») противоречило пункту 8. Раскладки ниже — **на 54 слота** (слоты: Header 0–8, Row1 9–17, рабочая зона 18–44 — три ряда по 7 контентных слотов, Footer 45–53: Д = 51, B = 52, C = 53).

### 10.1. Owner GUI (ПКМ по своему NPC), 54 слота, standalone

- Слот 0: голова (**уточнить** — допущение: `head-%player_name%` владельца; правило 3 требует подтверждения).
- **Вк (6, слоты 2–7)**: «Склад», «Продажа», «Скупка», «Улучшения», «Стража», «Статистика».
- **Д (слот 51)**: переключатель «Открыто/Закрыто» (`LIME_DYE`/`GRAY_DYE` как basehead из HeadTextures) — активен всегда; при `close_reason = ROBBERY` подписан «Открыть после ограбления».
- **B (52)**: стекло (standalone). **C (53)**: закрыть.
- Рабочая зона по вкладкам:
  - «Склад»: предметы склада (контент) + плитка «Касса: N» (ЛКМ — забрать, проверка `canFit`);
  - «Продажа»/«Скупка»: листинги в слотах (до 14 на 21 доступный слот), пустые слоты уровня — «Добавить» (контент), **закрытые** слоты — плитка «Откроется на ур. N»;
  - «Улучшения»: плитки уровня и стоимости;
  - «Стража»: плитка статуса, «Нанять / Уволить», зарплата, срок оплаты;
  - «Статистика»: выручка, сделки, рейтинг, журнал последних сделок (пагинация не нужна: последние 21).

### 10.2. Покупательский GUI (ПКМ по чужому NPC), 54 слота

- Слот 0: голова владельца точки (`head-…` — **уточнить**).
- **Вк (3)**: «Товары», «Скупка», «Рейтинг» → слоты 2, 4, 6.
- Рабочая зона: листинги. **Д**: стекло. **B**: стекло. **C**: закрыть.
- Покупка: клик → подтверждение (Hopper 9 слотов, исключение 1) при сумме ≥ `confirm-threshold`; иначе сразу.
- Обновление GUI **по событию** (как `GuiUpdater`), красная/зелёная голова обратной связи — как в существующих GUI.

### 10.3. Вспомогательные меню

- Подтверждения (покупка крупной суммы, найм/увольнение стража, улучшение) — Hopper 9: `[С][✓][С][П][П][П][С][✗][С]` с `basehead` Confirm/Cancel из стандарта.
- Оценка и комментарий — GUI 27 (Вк: «Оценка»; звёзды — контент).
- Барахольщик — GUI 54 (Вк 4 вкладки → слоты 2,3,5,6; пагинация 36/44 при более 21 лота).

### 10.4. «Мои вещи»

Вкладка Барахольщика: содержимое `pending_returns` игрока (предметы и монеты), забрать по одной или «Забрать всё» (Д, footer 51); монеты и предметы выдаются только при `canFit`; нехватка места — частичная выдача с сообщением, остаток остаётся в БД.

---

## 11. ТАБЛИЧКИ И СООБЩЕНИЯ

Табличка у точки обновляется при смене: открыто/закрыто, причина закрытия, рейтинг, уровень, срок аренды.

- `Открыто · Ур.3 · ★4.2 (18)`
- `Закрыто` / `Закрыто (ограбление)` / `Закрыто (аренда)`
- `Свободно · N монет / 7 дн.`
- `Аренда до: 05.10`

Сообщения — в `lang.yml` LoveShops, MiniMessage, оформление и glyph — по разделу 19, ключи `market.*`; реплики отказа/ограбления — списки (случайная), как у скупщика. Обязательные ключи: `stall-rented`, `stall-released`, `shop-opened`, `shop-closed`, `shop-robbed`, `robbery-success`, `robbery-refused`, `outcast-refused`, `aggressor-refused`, `guard-hired`, `guard-unpaid`, `guard-harassment-warn`, `guard-kicked`, `till-collect-no-space`, `rating-need-trade`, `rating-cooldown`, `trade-already-sold`, `trade-closed`, `flea-listing-limit`.

---

## 12. КОНФИГ LoveShops (фрагмент)

```yaml
# Рынок игроков: торговые точки. Значения-заготовки; цифры уточнять по экономике сервера.
market:
  enabled: true                       # выключатель подсистемы; без LoveClaims включить нельзя

  gates:
    enabled: true
    outcast-max-politeness: 1         # ступень вежливости ≤ этого = изгой (те же, что у GatedAction в TaxOracle)
    aggressive-max-playstyle: 2       # как aggressive-threshold у Странника
    perfect-min-politeness: 6         # налог не берётся, если вежливость 6 (Максимум)
    good-min-playstyle: 5             # и стиль игры ≥ 5 (Добрый)

  tax:
    enabled: true                     # true = TaxOracle#tradeRate по продавцу; false = налога нет вообще
    exempt-perfect: true              # образцовый продавец без налога

  stalls:
    base-sell-slots: 5
    base-buy-slots: 5
    max-level: 10
    upgrade-cost-base: 5000
    upgrade-cost-multiplier: 1.5
    confirm-threshold: 5000           # покупка на сумму ≥ этого — с подтверждением
    pending-timeout-seconds: 30

  robbery:
    enabled: true
    base-chance: 0.01                 # индивидуальный шанс ограбления на первом клике за день (без стражи)
    step: 0.005                       # прибавка шанса за каждый следующий клик того же дня
    chance-cap: 0.20                  # потолок индивидуального шанса
    attempts-per-day: 12              # бюджет кликов в день; исчерпан без ограбления → сегодня уже не выпадет
    day-reset-hour: 4                 # во сколько по серверному времени начинается новый «день» доставания
    co-attack-window-minutes: 15      # окно, в котором агрессоры считаются атакующими одну точку вместе
    max-combined-chance: 0.90         # жёсткий потолок групповой атаки; формула 1−∏(1−p_i) и так < 1
    max-robberies-per-point-per-day: 2
    coins-chance: 0.5                 # добыча — деньги (иначе вещи)
    refuse-chance: 0.75               # шанс, что агрессору просто откажут
    max-till-percent: 15
    max-stock-percent: 10
    max-items: 8
    robbery-cooldown-hours: 6
    hostility-minutes: 30
    max-robberies-per-day: 3
    robbery-reputation-penalty: 5

  guard:
    enabled: true
    salary: 800                       # за период
    salary-period-hours: 24
    max-guards-per-point: 1
    harassment-limit: 5
    harassment-window-minutes: 10
    harassment-ban-minutes: 30
  exit:                               # куда выкидывают за приставания
    world: "world"
    x: 0
    y: 64
    z: 0

  rating:
    min-trade-amount: 500
    cooldown-hours: 48
    new-account-days: 7
    new-account-weight: 0.3
    max-comment-length: 100

  anti-dump:
    enabled: true
    min-prices:
      DIAMOND: 50
      NETHERITE_INGOT: 200

  flea:
    max-listings-per-player: 10
    schedule:
      enabled: false                  # true = прежнее воскресное окно барахолки
  daily-buyer:
    enabled: true
    duration-hours: 6
```

Конфиг LoveClaims (дополнение):

```yaml
rental:
  trade-points:
    enabled: true
    max-per-player: 1
    warn-hours: 24
    grace-hours: 12
    auto-renew: true
```

В самом `config.yml` каждое число сопровождается русским комментарием с датой и **причиной выбора порога** (правило проекта), а не только описанием.

---

## 13. СТРУКТУРА КЛАССОВ

```
LoveClaims (me.lovelace.loveclaims)
├── model/Claim.java                      // + plotType
├── model/PlotType.java                   // RENTAL_PLOT | TRADE_POINT
├── manager/RentalManager.java            // assign / extend / release — единственный путь
├── api/event/
│   ├── TradePointRentRequestEvent.java   // cancellable
│   ├── TradePointRentedEvent.java
│   ├── TradePointReleasedEvent.java
│   └── TradePointExpiryWarningEvent.java
├── api/TradePointRentPayer.java          // провайдер оплаты из кассы
└── storage/SQLiteStorage.java            // миграция plot_type

LoveShops (dev.lovelace.loveshops)
├── market/
│   ├── TradePointManager.java            // жизненный цикл точки, NPC, восстановление
│   ├── StallTradeService.java            // сделка 8.2, pending_trades, reconcile
│   ├── StallUpgradeService.java
│   ├── ReputationGate.java               // классы игрока 3.1
│   ├── RobberyService.java               // 6.1–6.2
│   ├── GuardService.java                 // 6.3, зарплата
│   ├── RatingService.java
│   ├── ReturnsService.java               // pending_returns
│   └── FleaMarketManager.java            // Барахольщик (переработка SellerManager)
├── gui/market/
│   ├── StallOwnerGui.java   StallBuyerGui.java   StallConfirmGui.java (Hopper)
│   ├── StallRatingGui.java  FleaMarketGui.java   MyItemsGui.java
├── models/market/  TradePoint, StallListing, StallRating, TradeResult, PlayerClass
├── listeners/  MarketClaimsListener (события LoveClaims), StallNpcListener, MarketInventoryListener
└── integration/CitizensIntegration.java  // + createStallTrader/remove/guard
```

Правило потоков: сделки — цепочка 8.2 (Bukkit-часть на главном потоке, БД — на `synchronized`-доступе `DatabaseManager`, без async-обещаний, которых нет); Citizens/инвентари/сообщения — только главный поток.

---

## 14. ФАЗЫ РЕАЛИЗАЦИИ

| Фаза | Репозиторий | Задачи |
|---|---|---|
| **0 — основа** | LoveClaims | `plotType` + миграция; `assign/extend/release`, все 8 мест переведены; события; правила `TRADE_POINT`; оплата через `LoveEconomy`; создание точки админом; тесты на переходы состояния |
| **A — MVP** | LoveShops | SQL; `TradePointManager` + слушатель событий; `CitizensIntegration` create/remove/restore; Owner GUI (склад, касса, открыто/закрыто); слоты 5+5; налог по 4.2 |
| **B** | LoveShops | `StallTradeService` (8.2 + reconcile); `ReputationGate`; `RatingService` с защитами; прокачка; «Мои вещи» |
| **C** | LoveShops | `RobberyService`, `GuardService`, приставания и «выкидывание»; Барахольщик (переработка `SellerManager`), скупщик дня |
| **D** | оба | команды цен и модерации аукциона/барахолки (раздел 18); анти-демпинг; журналы/админ-восстановление ограблений; GUI рейтинга; полировка табличек; уровни стражи; тесты |

Между фазами — сборка `mvn compile` на JDK 25 и тесты; каждая фаза мержится отдельным PR (фаза 0 — в LoveClaims, дальше — LoveShops, зависимость LoveShops от новой версии LoveClaims фиксируется в `pom.xml`).

---

## 15. ЧЕКЛИСТ ТЕСТИРОВАНИЯ

**Аренда и цикл**
- [ ] Аренда `TRADE_POINT` → событие → NPC появился, прокачка восстановилась
- [ ] Изгой и агрессор не могут арендовать (отмена `TradePointRentRequestEvent`, деньги не списаны)
- [ ] Лимит 1 точка на игрока; нельзя добавить участников; флаги нельзя переопределить
- [ ] Автопродление из кассы при офлайн-владельце; нехватка → льготный период, склад цел
- [ ] Окончание аренды → NPC и стража удалены, вещи/касса в `pending_returns`
- [ ] Рестарт сервера: NPC пересозданы, осиротевшие удалены, дублей нет
- [ ] Все 8 старых мест изменения аренды идут через `assign/extend/release` (grep не находит прямых `setRentalEndTime`)

**Торговля**
- [ ] Покупка/выкуп атомарны; два покупателя на последний товар → один победитель
- [ ] Краш между шагами 2–5 → после рестарта `pending_trades` восстановлен, монеты и товар не потеряны и не размножены
- [ ] `tax + sellerGets == total`; образцовый продавец без налога; офлайн-продавец не падает
- [ ] Касса: выдача только при `canFit`; монеты нельзя выставить товаром
- [ ] Закрытая точка не торгует

**Репутация и ограбление**
- [ ] Изгой: отказ везде на рынке игроков; серверные NPC — как раньше
- [ ] Агрессор без стражи: доли 0.10/0.75 ≈ ожидаемые (статистический тест), при ограблении точка закрыта, владельцу сообщение, открыть может только он
- [ ] Без стражи: шанс растёт с кликами (`p(k)` монотонен, ≤ `chance-cap`), после `attempts-per-day` без ограбления `day_locked` держится до смены дня; перезаход счётчик не сбрасывает
- [ ] Групповая атака: `P = 1 − ∏(1 − p_i)` для 2, 5, 50 атакующих — растёт монотонно, добавка убывает, `P < 1` и `≤ max-combined-chance`
- [ ] Добыча — либо деньги, либо вещи; пустая касса и пустая продажа → «нечего взять», точка остаётся открытой; излишек падает под ноги грабителю
- [ ] Агрессор со стражей: ограбить нельзя, товар не отдаётся, после N приставаний — выкинут с рынка на 30 мин
- [ ] Стража: зарплата списывается из кассы, потом из инвентаря; не хватило → защита off, без долга
- [ ] Дневной потолок ограблений; кулдаун пары; своя/связанная точка не грабится
- [ ] Иммунны: Барахольщик, Скупщик, Аукционер, Банкир, Военный торговец, Странник

**Рейтинг, абузы, GUI**
- [ ] Нельзя оценить без сделки; кулдаун 48 ч; вес нового аккаунта 0.3
- [ ] Сделка невозможна на min-price ниже порога; rate-limit открытия GUI
- [ ] Клики `COLLECT_TO_CURSOR/MOVE_TO_OTHER_INVENTORY/NUMBER_KEY/drag` отменены
- [ ] Каждое GUI проходит чек-лист gui_gen (размер, Вк только 2–7, Row1, Footer, ни одного пустого слота Header/Footer, стекла в рабочей зоне нет)

---

## 16. ДОПУЩЕНИЯ И ВОПРОСЫ ДЛЯ ПОДТВЕРЖДЕНИЯ

Эти пункты я выбрал сам, потому что из ответов они однозначно не вытекают. Каждый меняет реализацию.

1. **Налог на палатках** — та же ставка (`TaxOracle#tradeRate` продавца, образцовый = 0), что и у Барахольщика. Плоские 7% отменены.
2. **Агрессор не может арендовать точку** (вытекает из «закрыть рынок игроков»), но в чужой палатке торгует редко, а не никогда.
3. **Что берёт грабитель**: **или** деньги (до 15% кассы), **или** вещи (до 10% стеков с продажи, не более 8); добыча идёт грабителю, излишек падает под ноги; после ограбления точка закрыта до ручного открытия. Проценты — заготовки.
4. **«Выкинут»** = телепорт на выход рынка + бан на точку 30 минут, **не** кик с сервера.
5. **Стража**: один NPC, зарплата раз в сутки из кассы → инвентаря, долга нет. Уровни стражи — фаза D.
6. **Автоконфискация убрана**, осталось закрытие при плохой репутации владельца и админ-изъятие.
7. **Барахольщик**: постоянный NPC, воскресное расписание — опция; серверный сток из Скупщика сохраняется как вкладка «Сервер».
8. **Слот 0 в GUI палатки** — голова владельца (`head-%player_name%`); по правилу 3 gui_gen нужно ваше подтверждение.
9. **Пороги классов**: изгой — вежливость ≤ 1, агрессор — стиль ≤ 2, образцовый — вежливость 6 и стиль ≥ 5.
10. **Проверка связанных аккаунтов** зависит от того, отдаёт ли LoveAuth адреса другим плагинам (не проверял); без этого проверка пропускается.
11. **Шанс ограбления**: старт 1%, +0.5% за клик, бюджет 12 кликов в день (≈37% за день для одиночки), потолок 20%; групповая атака `1 − ∏(1 − p_i)` с потолком 90%. Добычу получает тот, чей клик выпал. Числа — заготовки.
12. **Границы цен** (`price bounds`) — и в конфиге, и командами (команда пишет то же хранилище, конфиг задаёт значения по умолчанию).
13. **Скупщик, платящий слишком много** (см. `CLAUDE.md` LoveShop) — отдельная задача, в этот пакет не входит.

## 17. ЧТО ИЗМЕНИЛОСЬ ОТНОСИТЕЛЬНО 1.0.0

| Было в 1.0.0 | Стало | Почему |
|---|---|---|
| Плоты `MARKET_STALL` + тег `claim-tag` | Торговые точки, `plotType` в LoveClaims | тега не было; аренда устроена иначе |
| `MarketStallRentalListener` | Bukkit-события + единая точка `assign/release` | 8 разбросанных мест изменения аренды, у слушателя нет вызывающего |
| `economy.withdraw/deposit`, счёт `server_tax` | `LoveEconomy` + касса точки | валюта — физические монеты, офлайн-баланса нет |
| Налог 7% | `TaxOracle#tradeRate`, образцовым 0 | иначе двойной налог |
| Уровни `excellent…aggressive`, `getReputationLevel()` | `BehaviorLevels` (две шкалы 0–6), классы 3.1 | такого API нет |
| Вымогательство серверных NPC | Ограбление палаток + стража | решение владельца сервера; серверные NPC не меняются |
| Конфискация вещей | Закрытие + админ-изъятие | избыточно и опиралось на несуществующее |
| `FOR UPDATE`, `transactionAsync`, `trade_token` как защита | `BEGIN IMMEDIATE` + журнал `pending_trades` | SQLite и реальный `DatabaseManager` |
| Owner GUI: 2 ряда по 5 кнопок | 6 Вк в Header + переключатель в Footer | пункт 8 gui_gen |
| Барахольщик — отдельный NPC поверх барахолки | Барахолка переделана в Барахольщика | ваше решение «заменить/переделать» |


---

## 18. КОМАНДЫ РЕГУЛИРОВКИ ЦЕН И МОДЕРАЦИИ (аукцион, Барахольщик)

### 18.1. Что уже есть и что добавляется

Уже работает (не ломать, сохранить синтаксис и права):
- `/loveshopsadmin price <buyer|seller|war_merchant|wanderer|auctioneer|all> <предмет> <цена>` — `PricesManager#setNpcPrice`, пишет в `prices.yml`;
- `/loveshopsadmin item price|rarity|allow|deny` — цена/редкость/запрет предмета в руке.

Не хватает: **посмотреть** цену и её источник, **сбросить** переопределение, **изменить пачкой** (множитель), **границы цен** для рынка игроков, а для аукциона и Барахольщика — **модерация лотов**. Всё ниже — расширение того же `/loveshopsadmin` (алиасы `lspa`, `lssa` уже есть), горячее применение без рестарта (`PricesManager` перечитывает/применяет сразу), русские алиасы подкоманд — по образцу LoveAdaptation.

### 18.2. Цены

| Команда | Действие |
|---|---|
| `price get <цель> <предмет>` | текущая цена, источник (`prices.yml` / по умолчанию), множитель цели, границы |
| `price list <цель> [стр]` | постраничный список переопределений цели (в чат, 10 на страницу) |
| `price reset <цель> <предмет>` | убрать переопределение → вернуться к `default-price` |
| `price mult <цель> <процент>` | множитель цены цели, например `+10` / `-15` / `0` (сброс); применяется в `PriceCalculator` поверх базовой цены, до налога `TaxOracle` |
| `price bounds <предмет> <мин> <макс>` | границы для рынка игроков: нижняя — анти-демпинг, верхняя — потолок от накрутки; действуют на палатки, Барахольщика и стартовую цену лота; `off` снимает |
| `price bounds list [стр]` | список границ |

Правила:
- цена — целое `1 … price.max` (`price.max` в конфиге, по умолчанию 100 000 000), без переполнения `int`; сумма сделки считается в `long`;
- множитель ограничен `−90 … +500 %`, чтобы опечатка не обнулила и не взорвала экономику;
- `предмет` — материал или id кастомного предмета; неизвестный — ошибка с подсказкой, ничего не пишется;
- **журнал** `price_changes (id, admin_uuid, target, item, old, new, kind, created_at)`, хранится 90 дней, чистится раз в сутки; просмотр `/loveshopsadmin price history [предмет] [стр]`;
- права: `loveshops.admin.price` (уже есть).

### 18.3. Аукцион

| Команда | Действие |
|---|---|
| `auction list [стр]` | активные лоты: id, предмет, текущая ставка, лидер, время до конца |
| `auction create <старт> [выкуп] [часы]` | лот из предмета в руке (`AuctionManager#createAuction`), выкуп и срок необязательны; срок по умолчанию `auction.default-duration-hours` (24) |
| `auction price <id> <старт>` | сменить стартовую цену **только если нет ставок** |
| `auction buyout <id> <цена\|off>` | задать/снять цену выкупа; нельзя ниже текущей ставки |
| `auction extend <id> <часы>` | продлить; потолок `auction.max-extension-hours` |
| `auction end <id>` | завершить сейчас: победитель получает лот, ставки закрываются как обычно (`checkAndCompleteAuctions` по этому лоту) |
| `auction cancel <id> [причина]` | отменить: лот возвращается в `pending_returns` продавца/серверу, **все резервы ставок снимаются** (`reserved_currency`), участникам уведомление |
| `auction step <процент\|сумма>` | шаг ставки (`auction.min-bid-increment`); действует на новые ставки |

Правила: цену лота **со ставками** менять нельзя (обманет поставивших) — только `end`/`cancel`; каждая команда пишется в `price_changes`/лог модерации; `cancel` и `end` идут через ту же транзакционную цепочку, что и обычное завершение (без дублирования выплат/возвратов — использовать существующие пути `deliverPendingWins`/возврат резервов). Права: `loveshops.admin.auction`.

### 18.4. Барахольщик (лоты игроков)

| Команда | Действие |
|---|---|
| `flea list [игрок] [стр]` | лоты: id, продавец, предмет, цена, остаток |
| `flea remove <id> [причина]` | снять лот; остаток и предмет уходят в `pending_returns` продавца, ему — уведомление |
| `flea price <id> <цена>` | принудительно сменить цену лота (модерация демпинга/накрутки); продавцу уведомление, запись в журнал |
| `flea limit <игрок> <n\|reset>` | индивидуальный лимит лотов вместо `flea.max-listings-per-player` |
| `flea ban <игрок> [минуты] [причина]` / `flea unban <игрок>` | запрет выставлять лоты (срок пустой = бессрочно); чужие лоты игрока не трогаются |

Права: `loveshops.admin.flea`. Команды палаток (`point …`: закрыть, изъять, восстановить ограбление) — отдельное право `loveshops.admin.market`.

### 18.5. Общие требования

- Tab-complete по целям, id лотов (только активных), онлайн-игрокам и материалам; подсказки использования — ключи `admin-*-usage` в `lang.yml`.
- Все сообщения — MiniMessage в `lang.yml`; в коде никаких «зашитых» строк.
- Команды не блокируют главный поток: чтение списков — пагинированно и с ограничением размера; запись `prices.yml` — как сейчас, но один сохранённый файл за команду, не за каждый предмет пакетной операции.
- Проверка прав до любого доступа к БД; в консоли команды работают (кроме `auction create` — предмет в руке).
- Тесты: границы цены (0, отрицательная, `int`-переполнение), `mult` ±, `auction price` со ставками отклоняется, `auction cancel` снимает резервы ровно один раз, `flea remove` не теряет предмет.


---

## 19. ОФОРМЛЕНИЕ: GLYPH-ИКОНКИ, ГРАДИЕНТЫ, ДЕНЬГИ

Требование владельца: везде красивые glyph-иконки, за образец берётся банкир (`BankerGui`).

### 19.1. Как это уже делает банкир

- В тексте пишется `%img_<тег>%` — токен ItemsAdder «font image». `MessageUtils.parse` → `ItemsAdderFontHook.resolve` заворачивает токен в `<white>…</white>` **до** подстановки PlaceholderAPI, поэтому глиф не красится окружающим цветом.
- Название монеты — градиентом MiniMessage (`<gradient:#FFE000:#799F0C>Золотая монета</gradient>`), сумма — `<yellow>xN</yellow>`, номиналы — `LoveEconomy#denominations()`, тег монеты — `BankerGui#getCoinGlyph(Denomination)` (`%img_gold_coin%`, `%img_copper_coin%`, …).
- Это работает и в имени/лоре предмета, и в сообщениях чата, и в заголовке инвентаря.

### 19.2. Единый сервис оформления

Новый `market/MarketStyle` (LoveShops), одна точка, а не глифы вразброс по коду:

```java
public final class MarketStyle {
    /** Разбивка суммы на номиналы: "%img_gold_coin% <yellow>x2</yellow> %img_iron_coin% <yellow>x5</yellow>". */
    static String money(long amount);          // ноль → "%img_copper_coin% <yellow>x0</yellow>"
    /** Иконка по логическому имени; если тега нет в ресурспаке — запасной символ. */
    static String icon(Icon icon);
    /** Префикс реплики NPC: иконка + градиентное имя. */
    static String npcPrefix(NpcRole role);
}
```

- `money` берёт номиналы у `LoveEconomy` (не хардкодить `copper/iron/gold`), выводит минимальным числом монет, старшие вперёд — как `give`. `BankerGui#getCoinGlyph` выносится в общий `utils/CoinGlyphs`, банкир и рынок используют одну реализацию.
- **Цена, касса, зарплата, налог, аренда** везде показываются через `money(...)`, а не голым числом.
- `icon` вызывает `ItemsAdderFontHook.resolve`; если результат всё ещё содержит `%img_` (тега нет в ресурспаке или ItemsAdder не установлен) — подставляется запасной юникод-символ из таблицы ниже. Так на сервере без нужных текстур не появится сырой `%img_market_guard%` в чате.

### 19.3. Иконки

Монеты уже есть в ресурспаке. Остальных нет — их нужно **создать в ItemsAdder** (`font_images`), список тегов ниже. Пока текстур нет, работает запасной символ, ничего не ломается.

| Логическое имя | Тег ItemsAdder | Запасной символ | Где |
|---|---|---|---|
| `STALL` | `%img_market_stall%` | `❖` | заголовки GUI палатки, табличка |
| `GUARD` | `%img_market_guard%` | `🛡`→`⚔` | стража, реплики стражи |
| `ROBBERY` | `%img_market_robbery%` | `☠` | ограбление, журнал |
| `STAR` | `%img_market_star%` | `★` | рейтинг |
| `CLOSED` | `%img_market_closed%` | `✖` | «Закрыто» |
| `TILL` | `%img_market_till%` | `⛁` | касса |
| `FLEA` | `%img_market_flea%` | `✦` | Барахольщик |

Запасные символы — только из шрифта Minecraft по умолчанию (проверить отображение на клиенте до утверждения).

### 19.4. Где применяется

- **Заголовки GUI**: `%img_market_stall% <gradient:…>Палатка · Имя</gradient>`; для 54-слотовых меню длина заголовка ограничена — проверить, что глиф + текст не обрезаются.
- **Имена и лор кнопок**: иконка перед названием (`%img_market_till% Касса`), значения — `<yellow>`, действие — `<green>ЛКМ</green> <gray>— …</gray>` (структура lore по gui_gen).
- **Табличка**: `<green>%img_market_stall% Открыто</green> · Ур.3 · <gold>★4.2</gold> (18)` (на табличке глифы ItemsAdder могут не отображаться — там запасные символы, проверить).
- **Реплики NPC**: префикс роли — иконка + градиентное имя (`npcPrefix`): торговец, стража, Барахольщик, свой цвет градиента у каждой роли; стража — сине-серый, ограбление — тёмно-красный, барахольщик — золотой.
- **Стадии «терпения»** (6.4) разного цвета: спокойно — серый, раздражён — жёлтый, предупреждение — оранжевый, ограбление/выкидывание — красный.
- **Деньги в любых сообщениях** — только `money(...)`.

### 19.5. Пример реплик (`lang.yml`)

```yaml
market:
  patience:
    calm:
      - "%img_market_stall% <gradient:#E67E22:#D35400>Торговец:</gradient> <gray>Слушай, мне надо работать…</gray>"
    annoyed:
      - "%img_market_stall% <gradient:#E67E22:#D35400>Торговец:</gradient> <yellow>Хватит, я сказал!</yellow>"
    warning:
      - "%img_market_stall% <gradient:#E67E22:#D35400>Торговец:</gradient> <gold>Ещё раз — и мало не покажется…</gold>"
    robbed:
      - "%img_market_robbery% <gradient:#B71C1C:#7F0000>Торговец:</gradient> <red>Забирай и уходи!</red>"
    day-locked:
      - "%img_market_stall% <gradient:#E67E22:#D35400>Торговец:</gradient> <gray>Сегодня я тебя больше слушать не буду.</gray>"
  guard:
    intervene:
      - "%img_market_guard% <gradient:#90A4AE:#455A64>Стража:</gradient> <gray>Отойди от торговца.</gray>"
    kicked:
      - "%img_market_guard% <gradient:#90A4AE:#455A64>Стража:</gradient> <red>Вон с рынка!</red>"
  robbery-loot-coins: "%img_market_robbery% <red>Вы ограбили торговца:</red> {money}"
```

Токены `{money}`, `{player}`, `{point}` подставляются `LangManager` после `MarketStyle.money`, перед `MessageUtils.parse` (порядок важен: иначе глиф внутри подстановки не успеет получить `<white>`).

### 19.6. Что проверить

- [ ] `money(0)`, `money(1)`, суммы вокруг границ номиналов (9/10/11, 49/50/51, 999/1000/1001), большая сумма без переполнения `long`
- [ ] Отсутствие тега в ресурспаке → запасной символ, без сырого `%img_…%` в чате, лоре и заголовке
- [ ] Глиф не окрашивается соседним цветом/градиентом (обёртка `<white>`)
- [ ] Заголовок 54-слотового меню с глифом не обрезается
- [ ] Банкир после выноса `getCoinGlyph` выглядит как раньше

---

## 20. ОТКЛОНЕНИЯ РЕАЛИЗАЦИИ ОТ СПЕКИ (факт на 2026-09-30)

- **Нет таблицы `stall_stock`**: склад хранится в `stall_listings.stock`, полка = запись листинга.
- **Owner GUI**: вкладки Касса / Продажа / Скупка / Улучшения / Стража.
- **Оценка палатки** — сообщением в чат (голов-«звёзд» в ресурспаке нет).
- **Возвраты** выдаются автоматически при входе; `/loveshops` для игроков намеренно молчит.
- **Закрытие по репутации** — только для онлайн-владельцев (у оффлайн репутация читается как нейтральная).
- **Расписание Барахольщика**: `market.flea.schedule-enabled` (по умолчанию `false` = открыт всегда).
- **Восстановление после сбоя**: BUY:RESERVED → вернуть остаток; BUY:CHARGED → зачесть в кассу и считать выданным; SELL:RESERVED → вернуть в кассу.
- **§18**: `auction.default-duration-hours` → существующий `auctioneer.auction-duration-hours`; `auction.max-extension-hours` → `auctioneer.max-extension-hours` (72); `auction step` — `5%` (процент) или `50` (сумма), хранится в `prices.yml`; `point seize` — только игроком, изъятое идёт в его «Мои вещи»; добавлена `point robberies` (список номеров для `restore`); `restore` возмещает владельцу, у грабителя добыча остаётся.
- **Не реализовано**: обнаружение связанных аккаунтов (альты/IP); проверка на живом сервере не проводилась.
