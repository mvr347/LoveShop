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
 * Укрупнение берёт комиссию (базовая или личная через BankerManager).
 */
public class BankerGui {

    public static final String TITLE = "Банкир";
    public static final String DENOM_KEY = "banker_denom_value";
    public static final int SLOT_CONSOLIDATE = 11;
    public static final int SLOT_INFO = 13;
    public static final int[] BREAK_SLOTS = {19, 20, 21, 22, 23, 24, 25};

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

        Inventory inv = Bukkit.createInventory(null, 36, Component.text(TITLE).color(NamedTextColor.GOLD));
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i <= 8; i++) inv.setItem(i, filler);
        for (int i = 27; i < 36; i++) inv.setItem(i, filler);

        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));
        inv.setItem(35, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        long balance = economy.balance(player);
        int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
        boolean personalFee = plugin.getBankerManager().hasOverride(player.getUniqueId());
        String currency = economy.currencyName();

        inv.setItem(SLOT_INFO, infoItem(balance, feePercent, personalFee, currency));
        inv.setItem(SLOT_CONSOLIDATE, consolidateButton(balance, feePercent, currency));

        List<Denomination> dens = new ArrayList<>(economy.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        int idx = 0;
        for (Denomination den : dens) {
            if (idx >= BREAK_SLOTS.length) break;
            boolean hasSmaller = dens.stream().anyMatch(d -> d.value() < den.value());
            if (!hasSmaller) continue;
            inv.setItem(BREAK_SLOTS[idx++], breakButton(economy, den, currency));
        }

        player.openInventory(inv);
    }

    private ItemStack infoItem(long balance, int feePercent, boolean personalFee, String currency) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<gold>Ваш баланс</gold>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Сейчас: <yellow>" + MessageUtils.currencyIcon() + balance + " " + currency + "</yellow></gray>"));
            if (feePercent > 0) {
                String feeLabel = personalFee
                        ? "<gray>Ваша комиссия: <red>" + feePercent + "%</red> <dark_gray>(личная)</dark_gray></gray>"
                        : "<gray>Комиссия банкира: <red>" + feePercent + "%</red></gray>";
                lore.add(MessageUtils.parse(feeLabel));
            } else {
                lore.add(MessageUtils.parse("<gray>Комиссия: <green>нет</green></gray>"));
            }
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<dark_gray>Монеты физические — в инвентаре.</dark_gray>"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack consolidateButton(long balance, int feePercent, String currency) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<green>Укрупнить монеты</green>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Собрать мелочь в крупные номиналы.</gray>"));
            if (feePercent > 0 && balance > 0) {
                long fee = Math.max(0, balance * feePercent / 100L);
                long net = Math.max(0, balance - fee);
                lore.add(MessageUtils.parse("<gray>Получите: <yellow>" + MessageUtils.currencyIcon() + net + " " + currency + "</yellow></gray>"));
                lore.add(MessageUtils.parse("<gray>Комиссия: <red>" + MessageUtils.currencyIcon() + fee + "</red></gray>"));
            }
            lore.add(Component.empty());
            if (balance > 0) {
                lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— укрупнить</gray>"));
            } else {
                lore.add(MessageUtils.parse("<red>Кошелёк пуст</red>"));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack breakButton(LoveEconomy economy, Denomination den, String currency) {
        ItemStack item = den.icon() != null ? den.icon().clone() : new ItemStack(Material.GOLD_NUGGET);
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int count = countDenom(player, economy, den);
            meta.displayName(MessageUtils.parse("<yellow>Разменять: " + den.displayName() + "</yellow>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Номинал: <white>" + MessageUtils.currencyIcon() + den.value() + "</white></gray>"));
            lore.add(MessageUtils.parse("<gray>У вас: <white>" + count + " шт.</white></gray>"));
            lore.add(MessageUtils.parse("<gray>Разбивает 1 монету на более мелкие.</gray>"));
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
            MessageUtils.sendMessage(player, "<red>Нечего укрупнять — кошелёк пуст.</red>");
            return false;
        }
        int feePercent = plugin.getBankerManager().getFeePercent(player.getUniqueId());
        long fee = balance * feePercent / 100L;
        long net = balance - fee;
        if (!economy.charge(player, balance)) {
            MessageUtils.sendMessage(player, "<red>Не удалось списать монеты.</red>");
            return false;
        }
        if (net > 0) {
            economy.give(player, net);
        }
        if (fee > 0) {
            MessageUtils.sendMessage(player, "<green>Банкир: монеты укрупнены. Комиссия: <yellow>"
                    + MessageUtils.currencyIcon() + fee + " " + economy.currencyName() + "</yellow>.</green>");
        } else {
            MessageUtils.sendMessage(player, "<green>Банкир: монеты укрупнены.</green>");
        }
        return true;
    }

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
        if (countDenom(player, economy, target) <= 0) {
            MessageUtils.sendMessage(player, "<red>У вас нет такой монеты.</red>");
            return false;
        }

        if (!removeOneCoin(player, economy, denomValue)) {
            MessageUtils.sendMessage(player, "<red>Не удалось изъять монету.</red>");
            return false;
        }

        List<Denomination> smaller = economy.denominations().stream()
                .filter(d -> d.value() < denomValue)
                .sorted(Comparator.comparingLong(Denomination::value).reversed())
                .toList();
        if (smaller.isEmpty()) {
            economy.give(player, denomValue);
            MessageUtils.sendMessage(player, "<red>Этот номинал уже самый мелкий.</red>");
            return false;
        }
        long remaining = denomValue;
        long maxSmall = smaller.get(0).value();
        while (remaining > 0) {
            long chunk = Math.min(remaining, maxSmall);
            economy.give(player, chunk);
            remaining -= chunk;
        }
        MessageUtils.sendMessage(player, "<green>Банкир: монета разменяна.</green>");
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
