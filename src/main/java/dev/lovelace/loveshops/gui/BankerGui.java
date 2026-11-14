package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.managers.LangManager;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.LoreBlocks;
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
    public static final int SLOT_INFO = 0;
    public static final int SLOT_CLOSE = 35;
    public static final int[] OPTION_SLOTS = {13, 14, 15, 16, 22, 23, 24, 25};

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    // ItemsAdder CustomStack reflection
    private static Method iaGetInstance = null;
    private static Method iaGetItemStack = null;
    private static boolean iaChecked = false;

    public static final class Holder implements org.bukkit.inventory.InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        private Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public static boolean owns(Inventory inventory, Player player) {
        return inventory != null && inventory.getHolder() instanceof Holder holder
                && holder.owner.equals(player.getUniqueId());
    }

    private final LoveShops plugin;
    private final Player player;

    public BankerGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) {
            MessageUtils.event(player, "<red>Экономика недоступна.</red>");
            return;
        }
        Session session = SESSIONS.computeIfAbsent(player.getUniqueId(), id -> new Session());
        if (!session.lock.compareAndSet(false, true)) {
            MessageUtils.event(player, "<red>Подождите, операция ещё выполняется.</red>");
            return;
        }
        try {
            Holder holder = new Holder(player.getUniqueId());
            Inventory inv = Bukkit.createInventory(holder, 36, MessageUtils.parse("<dark_green>" + TITLE + "</dark_green>"));
            holder.inventory = inv;
            session.economy = economy;
            ItemStack filler = GuiUtils.createFiller();
            for (int i = 0; i <= 8; i++) inv.setItem(i, filler);
            for (int i = 27; i < 36; i++) inv.setItem(i, filler);
            // Slot 0 is the banker NPC head and also the rules / rates switch (renderContent fills it).
            inv.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, lang().text("gui.close"),
                    lang().lines("gui.banker.close-lore")));
            renderContent(inv, economy, session);
            // Mark the replacement before Bukkit closes the old view. Only the active
            // inventory may settle this escrow; a boolean cannot identify delayed closes.
            session.inventory = inv;
            try {
                player.openInventory(inv);
            } finally {
                // Another plugin may cancel InventoryOpenEvent after closing the old
                // view. Any retained escrow must then be returned immediately.
                if (player.getOpenInventory().getTopInventory() != inv) onClose(plugin, player);
            }
        } finally {
            session.lock.set(false);
        }
    }

    public static void refresh(LoveShops plugin, Player player) {
        Session session = SESSIONS.get(player.getUniqueId());
        Inventory inventory = player.getOpenInventory().getTopInventory();
        // A queued refresh must not reopen a menu the player has already closed.
        if (session == null || session.inventory != inventory || !owns(inventory, player)) return;
        LoveEconomy economy = plugin.getEconomy().orElse(session.economy);
        if (economy == null) return;
        new BankerGui(plugin, player).renderContent(inventory, economy, session);
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

    private LangManager lang() {
        return plugin.getLangManager();
    }

    private ItemStack infoItem(LoveEconomy economy, long deposited, int feePercent, boolean personal, String currency, boolean showRates) {
        List<String> lore = new ArrayList<>();
        String title;
        if (showRates) {
            title = lang().text("gui.banker.info-rates");
            lore.add(lang().text("gui.banker.rates-subtitle"));
            lore.add("");
            List<Denomination> dens = new ArrayList<>(economy.denominations());
            dens.sort(Comparator.comparingLong(Denomination::value).reversed());
            if (dens.size() > 1) {
                // Показываем размен для каждого номинала, кроме самого младшего (меди)
                for (int i = 0; i < dens.size() - 1; i++) {
                    Denomination higher = dens.get(i);
                    lore.add(lang().text("gui.banker.rates-section", "coin", getCoinGlyph(higher) + " " + getCoinName(higher)));
                    List<String> parts = new ArrayList<>();
                    for (int j = i + 1; j < dens.size(); j++) {
                        Denomination lower = dens.get(j);
                        if (lower.value() <= 0) continue;
                        long ratio = higher.value() / lower.value();
                        parts.add(lang().text("gui.banker.rates-part", "coin", getCoinGlyph(lower), "ratio", String.valueOf(ratio)));
                    }
                    lore.add("  " + String.join(lang().text("gui.banker.rates-separator"), parts));
                    lore.add("");
                }
            }
            lore.addAll(LoreBlocks.clicks(lang().style(), LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.banker.rates-click"))));
        } else {
            title = lang().text("gui.banker.info-rules");
            lore.addAll(lang().lines("gui.banker.rules-lore", "fee", String.valueOf(feePercent),
                    "personal", personal ? lang().text("gui.banker.fee-personal") : ""));
        }
        ItemStack item = GuiUtils.createCustomHead(HeadTextures.NPC_HEADER, title, lore);
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
                    lang().text("gui.banker.deposit-empty"),
                    lang().lines("gui.banker.deposit-empty-lore"));
            ItemMeta meta = empty.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "deposit");
                empty.setItemMeta(meta);
            }
            return empty;
        }

        List<Component> lore = new ArrayList<>();
        for (String line : lang().lines("gui.banker.deposit-filled-lore")) lore.add(GuiUtils.line(player, line));
        for (Component line : CoinFormat.formatGlyphLines(economy, deposited)) lore.add(Component.text("  ").append(line));
        for (String line : LoreBlocks.clicks(lang().style(),
                LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.banker.deposit-filled-click")))) {
            lore.add(GuiUtils.line(player, line));
        }

        ItemStack filled = GuiUtils.createCustomHead(HeadTextures.BANKER_DEPOSIT_FILLED,
                lang().text("gui.banker.deposit-filled"),
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
        return CoinFormat.getCoinName(den);
    }

    public static String getCoinGlyph(Denomination den) {
        return CoinFormat.getCoinGlyph(den);
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
        } catch (Throwable ignored) {
            // intentional: ItemsAdder is optional and its API may differ between versions
        }
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
        // unknown id: guess by value on the standard scale (1 / 100 / 2000 / 20000 / 100000)
        if (unit >= 100_000) return Material.NETHERITE_INGOT;
        if (unit >= 20_000) return Material.DIAMOND;
        if (unit >= 2_000) return Material.GOLD_INGOT;
        if (unit >= 100) return Material.IRON_INGOT;
        return Material.COPPER_INGOT;
    }

    private ItemStack optionButton(Denomination den, long maxCount, long unit, int feePercent, String currency) {
        ItemStack item = getCoinItem(den);
        item.setAmount((int) Math.min(64, Math.max(1, maxCount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String countSuffix = maxCount > 64 ? lang().text("gui.banker.option-suffix", "count", String.valueOf(maxCount)) : "";
            Component displayName;
            if (meta.hasDisplayName()) {
                displayName = countSuffix.isEmpty() ? meta.displayName() : meta.displayName().append(MessageUtils.parse(player, countSuffix));
            } else {
                displayName = MessageUtils.parse(player, getCoinName(den) + countSuffix);
            }
            meta.displayName(GuiUtils.noItalic(displayName));

            List<String> loreLines = new ArrayList<>(lang().lines("gui.banker.option-lore", "count", String.valueOf(maxCount)));
            if (feePercent > 0) loreLines.add(lang().text("gui.banker.option-fee", "fee", String.valueOf(feePercent)));
            loreLines.addAll(LoreBlocks.clicks(lang().style(),
                    LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.banker.option-click-one")),
                    LoreBlocks.hint(LoreBlocks.Click.SHIFT_LEFT, lang().text("gui.banker.option-click-max"))));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(GuiUtils.line(player, line));
            meta.lore(lore);

            meta.getPersistentDataContainer().set(denomKey(), PersistentDataType.LONG, unit);
            meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, "option");
            item.setItemMeta(meta);
        }
        return item;
    }

    private NamespacedKey denomKey() { return dev.lovelace.loveshops.utils.Keys.of(plugin, DENOM_KEY); }
    private NamespacedKey actionKey() { return dev.lovelace.loveshops.utils.Keys.of(plugin, ACTION_KEY); }

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
        if (amount <= 0 || unit > Long.MAX_VALUE / amount) return 0;
        long add = unit * (long) amount;
        if (add <= 0) return 0;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.inventory != player.getOpenInventory().getTopInventory()
                || !owns(session.inventory, player)) return 0;
        if (!session.lock.compareAndSet(false, true)) return 0;
        try {
            if (session.deposited > Long.MAX_VALUE - add) return 0;
            session.economy = economy;
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
            MessageUtils.event(player, "<red>Подождите, операция ещё выполняется.</red>");
            return false;
        }
        try {
            if (session.deposited < unitValue) {
                MessageUtils.event(player, "<red>Недостаточно на столе.</red>");
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
                        MessageUtils.event(player, "<red>Не хватает с учётом комиссии.</red>");
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
                    MessageUtils.event(player, "<red>Для операции требуется комиссия " + feePercent + "%: <yellow>" + fee + " " + economy.currencyName() + "</yellow> (не хватает " + fromInventory + ").</red>");
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

    public static void onClose(LoveShops plugin, Player player, Inventory closed) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.inventory != closed || !owns(closed, player)) return;
        onClose(plugin, player);
    }

    /** Quit/disable also settle the session, regardless of which view is currently open. */
    public static void onClose(LoveShops plugin, Player player) {
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return;
        LoveEconomy economy = plugin.getEconomy().orElse(session.economy);
        if (session.deposited > 0 && economy == null) {
            plugin.getLogger().severe("Cannot refund banker escrow for " + player.getUniqueId()
                    + ": economy unavailable; escrow retained");
            return;
        }
        if (!SESSIONS.remove(player.getUniqueId(), session)) return;
        long left = session.deposited;
        session.deposited = 0;
        session.inventory = null;
        if (left > 0) economy.give(player, left);
        if (plugin.isEnabled() && plugin.getNpcDialogueManager() != null)
            plugin.getNpcDialogueManager().sayBankerClose(player);
    }

    public static void shutdown(LoveShops plugin) {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) onClose(plugin, player);
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
        private Inventory inventory;
        private LoveEconomy economy;
        private final AtomicBoolean lock = new AtomicBoolean(false);
    }
}
