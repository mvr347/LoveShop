package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Банкир — обмен номиналов через слот депозита.
 * Использует реальные предметы монет ItemsAdder / LoveEconomy вместо голов игроков.
 */
public class BankerGui {

    public static final String TITLE = "Банкир";
    public static final String DENOM_KEY = "banker_denom_value";
    public static final String ACTION_KEY = "banker_action";
    public static final int SLOT_DEPOSIT = 11;
    public static final int SLOT_INFO = 34;
    public static final int SLOT_CLOSE = 35;
    public static final int[] OPTION_SLOTS = {13, 14, 15, 16, 22, 23, 24, 25};

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    // ItemsAdder CustomStack reflection
    private static Method iaGetInstance = null;
    private static Method iaGetItemStack = null;
    private static boolean iaChecked = false;

    private final LoveShops plugin;
    private final Player player;

    public BankerGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) {
            MessageUtils.sendMessage(player, "<red>Экономика недоступна.</red>");
            return;
        }
        Session session = SESSIONS.computeIfAbsent(player.getUniqueId(), id -> new Session());
        if (!session.lock.compareAndSet(false, true)) {
            MessageUtils.sendMessage(player, "<red>Подождите, операция ещё выполняется.</red>");
            return;
        }
        try {
            Inventory inv = Bukkit.createInventory(null, 36, MessageUtils.parse("<dark_green>" + TITLE + "</dark_green>"));
            ItemStack filler = GuiUtils.createFiller();
            for (int i = 0; i <= 8; i++) inv.setItem(i, filler);
            for (int i = 27; i < 36; i++) inv.setItem(i, filler);
            inv.setItem(0, GuiUtils.createPlayerProfileHead(player));
            inv.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                    List.of("", "<gray>Выход из меню</gray>",
                            "<gray>Несданные монеты вернутся вам.</gray>",
                            "<red>ЛКМ </red><gray>— закрыть</gray>")));
            renderContent(inv, economy, session);
            // The flag swallows the InventoryCloseEvent Bukkit fires for the banker view this call replaces.
            // When no banker view is open that event never comes, and an unconditional flag ate the player's
            // first real close instead: the deposited coins were not refunded until a second close or quit.
            session.ignoreNextClose = isBankerViewOpen(player);
            player.openInventory(inv);
        } finally {
            session.lock.set(false);
        }
    }

    private static boolean isBankerViewOpen(Player player) {
        var title = player.getOpenInventory().title();
        return title != null
                && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(title).contains(TITLE);
    }

    public static void refresh(LoveShops plugin, Player player) {
        if (player.getOpenInventory().title() == null) {
            new BankerGui(plugin, player).open();
            return;
        }
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(player.getOpenInventory().title());
        if (!title.contains(TITLE)) {
            new BankerGui(plugin, player).open();
            return;
        }
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        Session session = SESSIONS.get(player.getUniqueId());
        if (economy == null || session == null) {
            new BankerGui(plugin, player).open();
            return;
        }
        Inventory inv = player.getOpenInventory().getTopInventory();
        if (inv.getSize() < 36) {
            new BankerGui(plugin, player).open();
            return;
        }
        new BankerGui(plugin, player).renderContent(inv, economy, session);
        player.updateInventory();
    }

    void renderContent(Inventory inv, LoveEconomy economy, Session session) {
        String currency = economy.currencyName();
        int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
        boolean personal = plugin.getBankerManager().hasOverride(player.getUniqueId());
        inv.setItem(SLOT_INFO, infoItem(economy, session.deposited, feePercent, personal, currency, session.showExchangeRates));
        inv.setItem(SLOT_DEPOSIT, depositItem(economy, session.deposited, currency));
        for (int slot : OPTION_SLOTS) inv.setItem(slot, null);
        if (session.deposited <= 0) return;
        List<Denomination> dens = new ArrayList<>(economy.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());
        long maxDenomValue = dens.isEmpty() ? 0 : dens.get(0).value();
        long minDenomValue = dens.isEmpty() ? 0 : dens.get(dens.size() - 1).value();

        int idx = 0;
        for (Denomination den : dens) {
            if (idx >= OPTION_SLOTS.length) break;
            long unit = den.value();
            if (unit <= 0 || unit > session.deposited) continue;

            // Если внесены монеты только одного номинала — нельзя разменивать монету на саму себя
            if (session.minInputUnit == session.maxInputUnit && unit == session.maxInputUnit) {
                continue;
            }

            // Незеритовая монета (максимальный номинал) — если внесён только незерит, нельзя брать незерит
            if (unit == maxDenomValue && session.minInputUnit == maxDenomValue) {
                continue;
            }

            // Медная монета (минимальный номинал) — если внесена только медь, нельзя брать медь
            if (unit == minDenomValue && session.maxInputUnit == minDenomValue) {
                continue;
            }

            long maxCount = session.deposited / unit;
            if (maxCount <= 0) continue;
            inv.setItem(OPTION_SLOTS[idx++], optionButton(den, maxCount, unit, feePercent, currency));
        }
    }

    private ItemStack infoItem(LoveEconomy economy, long deposited, int feePercent, boolean personal, String currency, boolean showRates) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        String title;
        if (showRates) {
            title = "<gold>Курс и размен валют</gold>";
            List<Denomination> dens = new ArrayList<>(economy.denominations());
            dens.sort(Comparator.comparingLong(Denomination::value).reversed());
            if (dens.size() > 1) {
                // Показываем размен для каждого номинала, кроме самого младшего (меди)
                for (int i = 0; i < dens.size() - 1; i++) {
                    Denomination higher = dens.get(i);
                    lore.add(getCoinGlyph(higher) + " " + getCoinName(higher) + " <gray>равна:</gray>");
                    List<String> parts = new ArrayList<>();
                    for (int j = i + 1; j < dens.size(); j++) {
                        Denomination lower = dens.get(j);
                        if (lower.value() <= 0) continue;
                        long ratio = higher.value() / lower.value();
                        parts.add(getCoinGlyph(lower) + " <yellow>" + ratio + " шт.</yellow>");
                    }
                    lore.add("  <gray>•</gray> " + String.join(" <dark_gray>/</dark_gray> ", parts));
                    lore.add("");
                }
            }
            lore.add("<yellow>⚡ ЛКМ </yellow><gray>— показать правила банкира</gray>");
        } else {
            title = "<gold>Как работает банкир</gold>";
            lore.add("<gray>• <yellow>Внесение:</yellow> положите монеты в слот <gold>«Ваша валюта»</gold></gray>");
            lore.add("<gray>• <yellow>Выбор:</yellow> справа выберите желаемый номинал</gray>");
            lore.add("<gray>• <green>ЛКМ </green><gray>— получить <aqua>1 монету</aqua></gray></gray>");
            lore.add("<gray>• <green>Shift+ЛКМ </green><gray>— получить <gold>максимум</gold></gray></gray>");
            lore.add("<gray>• <yellow>Возврат:</yellow> клик по слоту валюты вернёт всё назад</gray>");
            lore.add("");
            lore.add("<gray>Банковский сбор: <gold>" + feePercent + "%" + (personal ? " <dark_gray>(личный)</dark_gray>" : "") + "</gold></gray>");
            lore.add("");
            lore.add("<yellow>⚡ ЛКМ </yellow><gray>— посмотреть курс размена монет</gray>");
        }
        ItemStack item = GuiUtils.createCustomHead(HeadTextures.BANKER_INFO, title, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "info");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack depositItem(LoveEconomy economy, long deposited, String currency) {
        if (deposited <= 0) {
            ItemStack empty = GuiUtils.createCustomHead(HeadTextures.BANKER_DEPOSIT_EMPTY,
                    "<gold>Ваша валюта</gold>",
                    List.of(
                            "",
                            "<gray>Положите сюда монеты для обмена</gray>",
                            "<dark_gray>(клик монетой из инвентаря или Shift)</dark_gray>"
                    ));
            ItemMeta meta = empty.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "deposit");
                empty.setItemMeta(meta);
            }
            return empty;
        }

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(player, "<gray>Внесённые монеты:</gray>"));

        long remaining = deposited;
        List<Denomination> dens = new ArrayList<>(economy.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                remaining %= den.value();
                String glyph = getCoinGlyph(den);
                lore.add(MessageUtils.parse(player, glyph + " <yellow>x" + count + "</yellow>"));
            }
        }
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(player, "<yellow>Клик </yellow><gray>— забрать всё назад</gray>"));

        ItemStack filled = GuiUtils.createCustomHead(HeadTextures.BANKER_DEPOSIT_FILLED,
                "<gold>Ваша валюта</gold>",
                List.of());
        ItemMeta meta = filled.getItemMeta();
        if (meta != null) {
            meta.lore(lore);
            meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "deposit");
            filled.setItemMeta(meta);
        }
        return filled;
    }

    public static String getCoinName(Denomination den) {
        if (den == null) return "Монета";
        String id = den.itemId() != null ? den.itemId().toLowerCase() : "";
        long val = den.value();
        if (id.contains("netherite") || val >= 1000) return "<gradient:#9B51E0:#BB6BD9>Незеритовая монета</gradient>";
        if (id.contains("diamond") || val >= 100) return "<gradient:#00C9FF:#92FE9D>Алмазная монета</gradient>";
        if (id.contains("gold") || val >= 50) return "<gradient:#FFE000:#799F0C>Золотая монета</gradient>";
        if (id.contains("iron") || val >= 10) return "<gradient:#E0E0E0:#F2F2F2>Железная монета</gradient>";
        return "<gradient:#E67E22:#D35400>Медная монета</gradient>";
    }

    public static String getCoinGlyph(Denomination den) {
        if (den == null || den.itemId() == null) return "%img_copper_coin%";
        String id = den.itemId();
        int colon = id.indexOf(':');
        String tag = colon >= 0 ? id.substring(colon + 1) : id;
        return "%img_" + tag + "%";
    }

    private static void initItemsAdder() {
        if (iaChecked) return;
        iaChecked = true;
        String[] candidates = {
                "dev.beer.itemsadder.api.CustomStack",
                "beer.devs.itemsadder.api.CustomStack",
                "dev.lone.itemsadder.api.CustomStack"
        };
        for (String candidate : candidates) {
            try {
                Class<?> customStackClass = Class.forName(candidate);
                iaGetInstance = customStackClass.getMethod("getInstance", String.class);
                iaGetItemStack = customStackClass.getMethod("getItemStack");
                if (iaGetInstance != null && iaGetItemStack != null) {
                    break;
                }
            } catch (Throwable ignored) {
                iaGetInstance = null;
                iaGetItemStack = null;
            }
        }
    }

    private static ItemStack getCustomStack(String id) {
        if (id == null || id.isBlank()) return null;
        initItemsAdder();
        if (iaGetInstance == null || iaGetItemStack == null) return null;
        try {
            Object customStack = iaGetInstance.invoke(null, id);
            if (customStack != null) {
                ItemStack is = (ItemStack) iaGetItemStack.invoke(customStack);
                if (is != null) return is.clone();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static ItemStack getCoinItem(Denomination den) {
        if (den != null && den.itemId() != null && !den.itemId().isBlank()) {
            String id = den.itemId();
            ItemStack is = getCustomStack(id);
            if (is != null) return is;

            if (!id.contains(":")) {
                is = getCustomStack("voidcore:" + id);
                if (is != null) return is;
                is = getCustomStack("currency:" + id);
                if (is != null) return is;
            } else {
                String raw = id.substring(id.indexOf(':') + 1);
                is = getCustomStack(raw);
                if (is != null) return is;
            }
        }
        return new ItemStack(fallbackMaterial(den));
    }

    private static Material fallbackMaterial(Denomination den) {
        if (den != null && den.itemId() != null) {
            String id = den.itemId().toLowerCase();
            if (id.contains(":")) id = id.substring(id.indexOf(':') + 1);
            if (id.contains("netherite")) return Material.NETHERITE_INGOT;
            if (id.contains("diamond")) return Material.DIAMOND;
            if (id.contains("gold")) return Material.GOLD_INGOT;
            if (id.contains("iron")) return Material.IRON_INGOT;
            if (id.contains("copper")) return Material.COPPER_INGOT;

            Material mat = Material.matchMaterial(id);
            if (mat != null && !mat.isAir()) return mat;
        }
        long unit = den != null ? den.value() : 1;
        if (unit >= 1000) return Material.NETHERITE_INGOT;
        if (unit >= 100) return Material.DIAMOND;
        if (unit >= 50) return Material.GOLD_INGOT;
        if (unit >= 10) return Material.IRON_INGOT;
        return Material.COPPER_INGOT;
    }

    private ItemStack optionButton(Denomination den, long maxCount, long unit, int feePercent, String currency) {
        ItemStack item = getCoinItem(den);
        item.setAmount((int) Math.min(64, Math.max(1, maxCount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String countSuffix = maxCount > 64 ? " <yellow>(" + maxCount + " шт)</yellow>" : "";
            Component displayName;
            if (meta.hasDisplayName()) {
                displayName = countSuffix.isEmpty() ? meta.displayName() : meta.displayName().append(MessageUtils.parse(player, countSuffix));
            } else {
                displayName = MessageUtils.parse(player, getCoinName(den) + countSuffix);
            }
            meta.displayName(displayName);

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(player, "<gray>Доступно: <yellow>до " + maxCount + " шт.</yellow></gray>"));
            if (feePercent > 0) {
                lore.add(MessageUtils.parse(player, "<dark_gray>Банковский сбор: " + feePercent + "%</dark_gray>"));
            }
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(player, "<green>ЛКМ </green><gray>— взять 1 шт.</gray>"));
            lore.add(MessageUtils.parse(player, "<green>Shift+ЛКМ </green><gray>— взять максимум</gray>"));
            meta.lore(lore);

            meta.getPersistentDataContainer().set(denomKey(), PersistentDataType.LONG, unit);
            meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "option");
            item.setItemMeta(meta);
        }
        return item;
    }

    private NamespacedKey denomKey() { return new NamespacedKey(plugin, DENOM_KEY); }
    private NamespacedKey actionKey() { return new NamespacedKey(plugin, ACTION_KEY); }

    public static Session session(UUID uuid) { return SESSIONS.get(uuid); }
    public static void clearSession(UUID uuid) { SESSIONS.remove(uuid); }

    public static void toggleInfoMode(Player player) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session != null) {
            session.showExchangeRates = !session.showExchangeRates;
        }
    }

    public static long depositStack(LoveShops plugin, Player player, ItemStack stack) {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null || stack == null || stack.getType().isAir()) return 0;
        long unit = economy.valueOf(stack);
        if (unit <= 0) return 0;
        int amount = stack.getAmount();
        long add = unit * (long) amount;
        if (add <= 0) return 0;
        Session session = SESSIONS.computeIfAbsent(player.getUniqueId(), id -> new Session());
        if (!session.lock.compareAndSet(false, true)) return 0;
        try {
            if (session.deposited > Long.MAX_VALUE - add) return 0;
            session.deposited += add;
            if (session.minInputUnit == 0 || unit < session.minInputUnit) {
                session.minInputUnit = unit;
            }
            if (unit > session.maxInputUnit) {
                session.maxInputUnit = unit;
            }
            return add;
        } finally { session.lock.set(false); }
    }

    public static boolean withdrawAll(LoveShops plugin, Player player) {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return false;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.deposited <= 0) return false;
        if (!session.lock.compareAndSet(false, true)) return false;
        try {
            long value = session.deposited;
            session.deposited = 0;
            session.maxInputUnit = 0;
            session.minInputUnit = 0;
            if (value > 0) economy.give(player, value);
            return true;
        } finally { session.lock.set(false); }
    }

    public static boolean takeOption(LoveShops plugin, Player player, long unitValue, long count) {
        if (unitValue <= 0 || count <= 0) return false;
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return false;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return false;
        if (!session.lock.compareAndSet(false, true)) {
            MessageUtils.sendMessage(player, "<red>Подождите, операция ещё выполняется.</red>");
            return false;
        }
        try {
            if (session.deposited < unitValue) {
                MessageUtils.sendMessage(player, "<red>Недостаточно на столе.</red>");
                return false;
            }
            long maxCount = session.deposited / unitValue;
            count = Math.min(count, maxCount);
            if (count <= 0) return false;
            long need = unitValue * count;
            int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
            long fee = feePercent > 0 ? (need * feePercent + 99) / 100 : 0;
            long totalCost = need + fee;

            long fromDeposit = Math.min(session.deposited, totalCost);
            long fromInventory = totalCost - fromDeposit;

            // Если средств на столе не хватает на оплату полной стоимости с комиссией
            if (fromInventory > 0) {
                // Если на столе не хватает даже на чистый номинал (need), уменьшаем count
                if (session.deposited < need) {
                    while (need + fee > session.deposited && count > 0) {
                        count--;
                        need = unitValue * count;
                        fee = feePercent > 0 ? (need * feePercent + 99) / 100 : 0;
                    }
                    if (count <= 0) {
                        MessageUtils.sendMessage(player, "<red>Не хватает с учётом комиссии.</red>");
                        return false;
                    }
                    totalCost = need + fee;
                    fromDeposit = Math.min(session.deposited, totalCost);
                    fromInventory = totalCost - fromDeposit;
                }
            }

            // Доплата комиссии из инвентаря игрока, если депозита на столе хватило на номинал, но не на сбор
            if (fromInventory > 0) {
                if (!economy.charge(player, fromInventory)) {
                    MessageUtils.sendMessage(player, "<red>Для операции требуется комиссия " + feePercent + "%: <yellow>" + fee + " " + economy.currencyName() + "</yellow> (не хватает " + fromInventory + ").</red>");
                    return false;
                }
            }

            session.deposited -= fromDeposit;
            if (session.deposited <= 0) {
                session.deposited = 0;
                session.maxInputUnit = 0;
                session.minInputUnit = 0;
            }

            // Выдаём номинал порциями не больше unitValue, чтобы LoveEconomy
            // не укрупняла монеты обратно в старший номинал при выдаче
            long remaining = need;
            while (remaining > 0) {
                long chunk = Math.min(remaining, unitValue);
                economy.give(player, chunk);
                remaining -= chunk;
            }

            // Убраны сообщения «Получено …» — игрок видит результат в GUI сам
            return true;
        } finally { session.lock.set(false); }
    }

    public static void onClose(LoveShops plugin, Player player) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return;
        if (session.ignoreNextClose) { session.ignoreNextClose = false; return; }
        SESSIONS.remove(player.getUniqueId());
        long left = session.deposited;
        session.deposited = 0;
        session.maxInputUnit = 0;
        session.minInputUnit = 0;
        if (left <= 0) {
            plugin.getNpcDialogueManager().sayBankerClose(player);
            return;
        }
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy != null) {
            economy.give(player, left);
        }
        plugin.getNpcDialogueManager().sayBankerClose(player);
    }

    public static boolean isOptionSlot(int slot) {
        for (int s : OPTION_SLOTS) if (s == slot) return true;
        return false;
    }

    public static final class Session {
        private long deposited;
        private long maxInputUnit;
        private long minInputUnit;
        private boolean showExchangeRates;
        private boolean ignoreNextClose;
        private final AtomicBoolean lock = new AtomicBoolean(false);
    }
}
