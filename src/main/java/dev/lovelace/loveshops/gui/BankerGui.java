package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.textures.HeadTextures;
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
 * GUI банкира: размен и укрупнение монет.
 * Использует реальные предметы монет ItemsAdder / LoveEconomy вместо голов игроков.
 */
public class BankerGui {

    public static final String TITLE = "Банкир";
    private static final int SLOT_INFO = 4;
    public static final int SLOT_DEPOSIT = 13;
    public static final int SLOT_CLOSE = 31;
    private static final int[] OPTION_SLOTS = {15, 16, 17, 24, 25, 26};
    public static final String DENOM_KEY = "banker_denom";
    private static final String ACTION_KEY = "banker_action";

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    // ItemsAdder reflection
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
            session.ignoreNextClose = true;
            player.openInventory(inv);
        } finally {
            session.lock.set(false);
        }
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
        inv.setItem(SLOT_INFO, infoItem(session.deposited, feePercent, personal, currency));
        inv.setItem(SLOT_DEPOSIT, depositItem(session.deposited, currency));
        for (int slot : OPTION_SLOTS) inv.setItem(slot, null);
        if (session.deposited <= 0) return;
        List<Denomination> dens = new ArrayList<>(economy.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());
        int idx = 0;
        // Находим максимальный номинал экономики (незеритовая монета) — её нельзя «укрупнять»
        long highestUnit = dens.isEmpty() ? 0 : dens.get(0).value();
        for (Denomination den : dens) {
            if (idx >= OPTION_SLOTS.length) break;
            long unit = den.value();
            if (unit <= 0 || unit > session.deposited) continue;
            // Незеритовая (максимальная) монета не показывается как вариант укрупнения
            if (unit == highestUnit && unit > session.maxInputUnit) continue;
            long maxCount = session.deposited / unit;
            if (maxCount <= 0) continue;
            inv.setItem(OPTION_SLOTS[idx++], optionButton(den, maxCount, unit, feePercent, currency));
        }
    }

    private ItemStack infoItem(long deposited, int feePercent, boolean personal, String currency) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<gold>Как работает банкир</gold>"));
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtils.parse("<gray>1. Положите монеты в слот слева</gray>"));
            lore.add(MessageUtils.parse("<gray>2. Справа выберите номинал</gray>"));
            lore.add(MessageUtils.parse("<gray>ЛКМ — одна монета, Shift — максимум</gray>"));
            lore.add(MessageUtils.parse("<gray>Клик по депозиту — забрать всё назад</gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>На столе: <yellow>" + MessageUtils.currencyIcon() + deposited + " " + currency + "</yellow></gray>"));
            lore.add(MessageUtils.parse("<gray>Комиссия: <yellow>" + feePercent + "%" + (personal ? " <dark_gray>(личная)</dark_gray>" : "") + "</yellow></gray>"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack depositItem(long deposited, String currency) {
        if (deposited <= 0) {
            ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(MessageUtils.parse("<yellow>Слот для монет</yellow>"));
                meta.lore(List.of(
                        Component.empty(),
                        MessageUtils.parse("<gray>Положите сюда монеты LoveEconomy</gray>"),
                        MessageUtils.parse("<gray>(клик монетой или Shift из инвентаря)</gray>")
                ));
                meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "deposit");
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<green>Депозит: " + MessageUtils.currencyIcon() + deposited + " " + currency + "</green>"));
            meta.lore(List.of(
                    Component.empty(),
                    MessageUtils.parse("<gray>Клик — забрать всё назад</gray>")
            ));
            meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "withdraw");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack getCoinItem(Denomination den) {
        ItemStack ia = tryItemsAdder(den);
        if (ia != null) return ia;
        Material mat = materialForUnit(den);
        return new ItemStack(mat);
    }

    private ItemStack tryItemsAdder(Denomination den) {
        if (den.itemId() == null) return null;
        try {
            if (!iaChecked) {
                iaChecked = true;
                Class<?> iaClass = Class.forName("dev.lone.itemsadder.api.ItemsAdder");
                iaGetInstance = iaClass.getMethod("getInstance");
                Object inst = iaGetInstance.invoke(null);
                iaGetItemStack = inst.getClass().getMethod("getItemStack", String.class);
            }
            if (iaGetInstance == null || iaGetItemStack == null) return null;
            Object inst = iaGetInstance.invoke(null);
            Object stack = iaGetItemStack.invoke(inst, den.itemId());
            if (stack instanceof ItemStack is && !is.getType().isAir()) {
                return is.clone();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Material materialForUnit(Denomination den) {
        String id = den.itemId() != null ? den.itemId().toLowerCase() : "";
        if (id.contains("netherite")) return Material.NETHERITE_INGOT;
        if (id.contains("diamond")) return Material.DIAMOND;
        if (id.contains("gold")) return Material.GOLD_INGOT;
        if (id.contains("iron")) return Material.IRON_INGOT;
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
            Component displayName;
            String countSuffix = " <gray>(" + maxCount + " шт.)</gray>";
            if (meta.hasDisplayName()) {
                displayName = meta.displayName().append(MessageUtils.parse(countSuffix));
            } else {
                String label = den.itemId() != null ? den.itemId() : ("×" + unit);
                displayName = MessageUtils.parse("<aqua>" + label + "</aqua>" + countSuffix);
            }
            meta.displayName(displayName);

            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtils.parse("<gray>Можно получить: <yellow>до " + maxCount + " шт.</yellow></gray>"));
            if (feePercent > 0) lore.add(MessageUtils.parse("<dark_gray>Комиссия " + feePercent + "%</dark_gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— взять 1</gray>"));
            lore.add(MessageUtils.parse("<green>Shift+ЛКМ </green><gray>— взять максимум</gray>"));
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

    /**
     * @return сколько единиц валюты добавлено на стол (0, если стек не является монетой
     *         известного номинала LoveEconomy)
     */
    public static long depositStack(LoveShops plugin, Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return 0;
        long unit = economy.valueOf(stack);
        if (unit <= 0) return 0;
        long value = unit * stack.getAmount();
        Session session = SESSIONS.computeIfAbsent(player.getUniqueId(), id -> new Session());
        if (!session.lock.compareAndSet(false, true)) return 0;
        try {
            session.deposited += value;
            if (unit > session.maxInputUnit) session.maxInputUnit = unit;
            return value;
        } finally { session.lock.set(false); }
    }

    public static boolean isOptionSlot(int slot) {
        for (int s : OPTION_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    public static boolean withdrawAll(LoveShops plugin, Player player) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.deposited <= 0) return false;
        if (!session.lock.compareAndSet(false, true)) return false;
        try {
            LoveEconomy economy = plugin.getEconomy().orElse(null);
            if (economy == null) return false;
            long left = session.deposited;
            session.deposited = 0;
            session.maxInputUnit = 0;
            economy.give(player, left);
            return true;
        } finally { session.lock.set(false); }
    }

    public static boolean takeOption(LoveShops plugin, Player player, long unitValue, long count) {
        if (unitValue <= 0 || count <= 0) return false;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return false;
        if (!session.lock.compareAndSet(false, true)) return false;
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) {
            session.lock.set(false);
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
            long fee = 0;
            if (feePercent > 0 && unitValue > session.maxInputUnit) {
                fee = (need * feePercent + 99) / 100; // округление вверх в пользу сервера
                while (need + fee > session.deposited && count > 0) {
                    count--;
                    need = unitValue * count;
                    fee = (need * feePercent + 99) / 100; // округление вверх в пользу сервера
                }
                if (count <= 0) {
                    MessageUtils.sendMessage(player, "<red>Не хватает с учётом комиссии.</red>");
                    return false;
                }
            }
            long totalCost = need + fee;
            session.deposited -= totalCost;

            // Выдаём номинал порциями не больше unitValue, чтобы LoveEconomy
            // не укрупняла монеты обратно в старший номинал при выдаче
            long remaining = need;
            while (remaining > 0) {
                long chunk = Math.min(remaining, unitValue);
                economy.give(player, chunk);
                remaining -= chunk;
            }

            // Сообщения «Получено» убраны — игрок видит результат в GUI
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
        if (left <= 0) return;
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy != null) economy.give(player, left);
    }

    public static class Session {
        long deposited;
        long maxInputUnit;
        final AtomicBoolean lock = new AtomicBoolean(false);
        boolean ignoreNextClose;
    }
}
