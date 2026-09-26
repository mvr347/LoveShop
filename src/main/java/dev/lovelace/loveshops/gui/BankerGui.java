package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Банкир — обмен физических монет LoveCore между номиналами.
 * <ul>
 *   <li>Укрупнить — списать весь баланс и выдать обратно крупными номиналами (со сдачей/оптимальной выдачей через {@link LoveEconomy#give})</li>
 *   <li>Разменять — разбить одну монету выбранного номинала на более мелкие</li>
 * </ul>
 */
public class BankerGui {

    public static final String TITLE = "Банкир";
    public static final String DENOM_KEY = "banker_denom_value";
    public static final int SLOT_CONSOLIDATE = 11;
    public static final int SLOT_INFO = 13;
    public static final int SLOT_CLOSE = 26;
    // gui-gen RULE 2/6: стекло только в Header (0-8) и Footer (18-26), рабочая зона (9-17)
    // без стекла — только контент. 11 и 13 заняты Consolidate/Info, боковые стенки 9 и 17 пустые.
    public static final int[] BREAK_SLOTS = {10, 12, 14, 15, 16};

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

        Inventory inv = Bukkit.createInventory(null, 27, MessageUtils.parse("<dark_green>" + TITLE + "</dark_green>"));

        // gui-gen RULE 2/6: стекло только в Header (0-8) и Footer (18-26) — та же схема,
        // что уже используют BuyerGui/SellerGui/WandererDealGui в этом плагине.
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i <= 8; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 18; i < 27; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));
        inv.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        String currency = economy.currencyName();
        long balance = economy.balance(player);

        // Info
        inv.setItem(SLOT_INFO, infoItem(economy, balance, currency));

        // Consolidate
        inv.setItem(SLOT_CONSOLIDATE, consolidateButton(economy, balance, currency));

        // Break buttons for each denomination that has smaller ones
        List<Denomination> dens = new ArrayList<>(economy.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());
        int idx = 0;
        for (Denomination den : dens) {
            if (idx >= BREAK_SLOTS.length) break;
            // Разменивать имеет смысл только номиналы, у которых есть мельче
            boolean hasSmaller = dens.stream().anyMatch(d -> d.value() < den.value());
            if (!hasSmaller) continue;
            inv.setItem(BREAK_SLOTS[idx++], breakButton(economy, den, currency));
        }

        player.openInventory(inv);
    }

    private ItemStack infoItem(LoveEconomy economy, long balance, String currency) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<gold>Ваш баланс</gold>"));
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtils.parse("<gray>Всего: <yellow>" + MessageUtils.currencyIcon() + balance + " " + currency + "</yellow></gray>"));
            int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
            boolean personal = plugin.getBankerManager().hasOverride(player.getUniqueId());
            lore.add(MessageUtils.parse("<gray>Комиссия укрупнения: <yellow>" + feePercent + "%"
                    + (personal ? " <dark_gray>(личная)</dark_gray>" : "") + "</yellow></gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<dark_gray>Укрупнение пересобирает монеты</dark_gray>"));
            lore.add(MessageUtils.parse("<dark_gray>в более крупные номиналы.</dark_gray>"));
            lore.add(MessageUtils.parse("<dark_gray>Размен — одна монета на мелочь.</dark_gray>"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack consolidateButton(LoveEconomy economy, long balance, String currency) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<green>Укрупнить монеты</green>"));
            List<Component> lore = new ArrayList<>();
            int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
            if (balance > 0 && feePercent > 0) {
                long fee = Math.max(0, balance * feePercent / 100);
                long net = Math.max(0, balance - fee);
                lore.add(MessageUtils.parse("<gray>Получите: <yellow>" + MessageUtils.currencyIcon() + net + " " + currency + "</yellow></gray>"));
                lore.add(MessageUtils.parse("<gray>Комиссия: <red>" + MessageUtils.currencyIcon() + fee + "</red></gray>"));
            } else {
                lore.add(MessageUtils.parse("<gray>Баланс не изменится — только состав монет.</gray>"));
            }
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— укрупнить</gray>"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack breakButton(LoveEconomy economy, Denomination den, String currency) {
        int count = countDenom(player, economy, den);
        ItemStack item = new ItemStack(Material.GOLD_NUGGET);
        item.setAmount(Math.max(1, Math.min(64, count > 0 ? count : 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<aqua>Разменять: " + den.itemId() + "</aqua>"));
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtils.parse("<gray>Номинал: <yellow>" + den.value() + " " + currency + "</yellow></gray>"));
            lore.add(MessageUtils.parse("<gray>У вас: <yellow>" + count + " шт.</yellow></gray>"));
            lore.add(Component.empty());
            if (count > 0) {
                lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— разменять одну</gray>"));
            } else {
                lore.add(MessageUtils.parse("<red>Нет таких монет</red>"));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, DENOM_KEY),
                    PersistentDataType.LONG,
                    den.value());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static int countDenom(Player player, LoveEconomy economy, Denomination den) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (economy.valueOf(stack) == den.value()) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    public static boolean consolidate(LoveShops plugin, Player player) {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return false;

        long balance = economy.balance(player);
        if (balance <= 0) {
            MessageUtils.sendMessage(player, "<red>Кошелёк пуст.</red>");
            return false;
        }

        int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
        long fee = Math.max(0, balance * feePercent / 100);
        long net = Math.max(0, balance - fee);

        if (!economy.charge(player, balance)) {
            MessageUtils.sendMessage(player, "<red>Не удалось списать баланс.</red>");
            return false;
        }
        if (net > 0) {
            economy.give(player, net);
        }

        if (fee > 0) {
            MessageUtils.sendMessage(player, "<green>Монеты укрупнены. Комиссия: <yellow>"
                    + MessageUtils.currencyIcon() + fee + "</yellow>.</green>");
        } else {
            MessageUtils.sendMessage(player, "<green>Монеты укрупнены без комиссии.</green>");
        }
        return true;
    }

    /**
     * Размен одной монеты номинала {@code denomValue} на более мелкие:
     * снимаем одну монету (charge её value), выдаём ту же сумму через give
     * (крупные номиналы сначала — но без исходного, если единственный крупный?
     * give всегда отдаёт оптимум; чтобы разбить именно на мелочь, выдаём
     * вручную только номиналами строго меньше denomValue).
     */
    public static boolean breakOne(LoveShops plugin, Player player, long denomValue) {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null || denomValue <= 0) return false;

        Denomination target = null;
        for (Denomination d : economy.denominations()) {
            if (d.value() == denomValue) {
                target = d;
                break;
            }
        }
        if (target == null) {
            MessageUtils.sendMessage(player, "<red>Неизвестный номинал.</red>");
            return false;
        }

        // Снимаем ровно одну монету этого номинала
        if (!removeOneCoin(player, economy, denomValue)) {
            MessageUtils.sendMessage(player, "<red>Не удалось изъять монету.</red>");
            return false;
        }

        List<Denomination> smaller = economy.denominations().stream()
                .filter(d -> d.value() < denomValue)
                .sorted(Comparator.comparingLong(Denomination::value).reversed())
                .toList();
        if (smaller.isEmpty()) {
            economy.give(player, denomValue); // вернуть
            MessageUtils.sendMessage(player, "<red>Этот номинал уже самый мелкий.</red>");
            return false;
        }
        // give() всегда отдаёт старшими номиналами — чтобы не вернуть ту же крупную,
        // выдаём сумму кусками не больше максимального «меньшего» номинала.
        long remaining = denomValue;
        long maxSmall = smaller.get(0).value();
        while (remaining > 0) {
            long chunk = Math.min(remaining, maxSmall);
            economy.give(player, chunk);
            remaining -= chunk;
        }
        MessageUtils.sendMessage(player, "<green>Монета разменяна.</green>");
        return true;
    }

    private static boolean removeOneCoin(Player player, LoveEconomy economy, long denomValue) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (economy.valueOf(stack) != denomValue) continue;
            if (stack.getAmount() <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            return true;
        }
        return false;
    }
}
