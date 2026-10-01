package dev.lovelace.loveshops.market.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 27-slot Price setting GUI (Section 5 of specification).
 * Allows setting price using LoveEconomy coin denominations:
 * - Shift-click denomination: select active coin
 * - LMB: -1 of that denomination
 * - RMB: +1 of that denomination
 * - Item amount controls (+/-)
 * - Presets: min price, last price
 * - Confirm / Cancel
 */
public final class PriceGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;
    private final ItemStack template;
    private final int shelfIndex;
    private final ListingType type;
    private final int maxAvailableAmount;
    private int currentAmount;
    private long currentPrice;
    private Denomination activeDenomination;
    private final BiConsumer<Integer, Long> onConfirm;
    private final Runnable onCancel;
    private final List<Denomination> denominations = new ArrayList<>();

    public PriceGui(LoveShops plugin, Player viewer, TradePoint point, ItemStack item,
                    int shelfIndex, ListingType type, long initialPrice,
                    BiConsumer<Integer, Long> onConfirm, Runnable onCancel) {
        super(plugin, viewer);
        this.point = point;
        this.template = item.clone();
        this.shelfIndex = shelfIndex;
        this.type = type;
        this.maxAvailableAmount = Math.max(1, item.getAmount());
        this.currentAmount = this.maxAvailableAmount;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco != null) {
            denominations.addAll(eco.denominations());
            denominations.sort(Comparator.comparingLong(Denomination::value));
        }

        long min = plugin.getMarketConfig().minPrice(template.getType().name());
        long max = plugin.getMarketConfig().maxPrice(template.getType().name());
        this.currentPrice = initialPrice > 0 ? Math.min(max, Math.max(min, initialPrice)) : Math.max(1L, min);

        if (!denominations.isEmpty()) {
            activeDenomination = denominations.get(0);
        }
    }

    public PriceGui(LoveShops plugin, Player viewer, ItemStack item, boolean isEdit,
                    java.util.function.Consumer<Long> onPrice, Runnable onCancel) {
        this(plugin, viewer, null, item, -1, ListingType.SELL, 0L, (amt, price) -> {
            if (onPrice != null) onPrice.accept(price);
        }, onCancel);
    }

    public PriceGui(LoveShops plugin, Player viewer, ItemStack item, boolean isEdit,
                    BiConsumer<Integer, Long> onConfirm, Runnable onCancel) {
        this(plugin, viewer, null, item, -1, ListingType.SELL, 0L, onConfirm, onCancel);
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<dark_aqua>Установка цены лота</dark_aqua>");
        show(org.bukkit.Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public java.util.UUID pointId() {
        return point != null ? point.claimId() : null;
    }


    @Override
    public void render() {
        fillFrame();

        String itemKey = template.getType().name();
        long min = plugin.getMarketConfig().minPrice(itemKey);
        long max = plugin.getMarketConfig().maxPrice(itemKey);

        // Slot 2: Preset "Мин. цена"
        ItemStack minBtn = GuiUtils.createItem(Material.IRON_NUGGET,
                MessageUtils.parse("<yellow>Минимальная цена</yellow>"),
                List.of(
                        MessageUtils.parse("<gray>Установить цену антидампа:</gray>"),
                        MessageUtils.parse("<white>" + CoinFormat.formatGlyphs(min) + "</white>"),
                        Component.empty(),
                        MessageUtils.parse("<yellow>Клик: применить</yellow>")
                ));
        inventory.setItem(2, minBtn);

        // Slot 3: Decrease amount (if item amount > 1)
        if (maxAvailableAmount > 1) {
            ItemStack decBtn = GuiUtils.createItem(Material.RED_STAINED_GLASS_PANE,
                    MessageUtils.parse("<red>−1 к количеству</red>"),
                    List.of(MessageUtils.parse("<gray>Shift+клик: −8</gray>")));
            inventory.setItem(3, decBtn);
        }

        // Slot 4: Item preview with amount
        ItemStack preview = template.clone();
        preview.setAmount(Math.max(1, Math.min(64, currentAmount)));
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Выставляемое кол-во: <yellow>" + currentAmount + "</yellow> шт.</gray>"));
            lore.add(MessageUtils.parse("<gray>Цена за шт.: <gold>" + CoinFormat.formatGlyphs(currentPrice) + "</gold></gray>"));
            if (currentAmount > 1) {
                long total = currentPrice * (long) currentAmount;
                lore.add(MessageUtils.parse("<gray>Итого: <gold>" + CoinFormat.formatGlyphs(total) + "</gold></gray>"));
            }
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        inventory.setItem(4, preview);

        // Slot 5: Increase amount (if item amount > 1)
        if (maxAvailableAmount > 1) {
            ItemStack incBtn = GuiUtils.createItem(Material.GREEN_STAINED_GLASS_PANE,
                    MessageUtils.parse("<green>+1 к количеству</green>"),
                    List.of(MessageUtils.parse("<gray>Shift+клик: +8</gray>")));
            inventory.setItem(5, incBtn);
        }

        // Slot 6: Preset "Сбросить на 1 монету"
        ItemStack resetBtn = GuiUtils.createItem(Material.GOLD_NUGGET,
                MessageUtils.parse("<yellow>Базовая цена</yellow>"),
                List.of(
                        MessageUtils.parse("<gray>Установить 1 монету:</gray>"),
                        MessageUtils.parse("<white>" + CoinFormat.formatGlyphs(Math.max(1L, min)) + "</white>"),
                        Component.empty(),
                        MessageUtils.parse("<yellow>Клик: применить</yellow>")
                ));
        inventory.setItem(6, resetBtn);

        // Slot 10: Price summary indicator
        ItemStack summary = GuiUtils.createItem(Material.CHEST,
                MessageUtils.parse("<gold>Итоговая цена лота</gold>"),
                List.of(
                        MessageUtils.parse("<gray>Цена за 1 шт.:</gray>"),
                        MessageUtils.parse("<white>" + CoinFormat.formatGlyphs(currentPrice) + "</white>"),
                        Component.empty(),
                        MessageUtils.parse("<gray>Мин.: <yellow>" + CoinFormat.formatGlyphs(min) + "</yellow></gray>"),
                        MessageUtils.parse("<gray>Макс.: <yellow>" + CoinFormat.formatGlyphs(max) + "</yellow></gray>")
                ));
        inventory.setItem(10, summary);

        // Slots 12, 13, 14, 15, 16: Denominations row
        int[] denSlots = {12, 13, 14, 15, 16};
        for (int i = 0; i < denSlots.length && i < denominations.size(); i++) {
            Denomination den = denominations.get(i);
            int slot = denSlots[i];
            boolean isActive = activeDenomination != null && activeDenomination.value() == den.value();

            Material mat = switch (i) {
                case 0 -> Material.COPPER_INGOT;
                case 1 -> Material.IRON_INGOT;
                case 2 -> Material.GOLD_INGOT;
                case 3 -> Material.DIAMOND;
                default -> Material.NETHERITE_INGOT;
            };

            String glyph = CoinFormat.getCoinGlyph(den);
            String coinName = CoinFormat.getCoinName(den);

            long denCount = (currentPrice / den.value());
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtils.parse("<gray>Номинал: " + glyph + " = <yellow>" + den.value() + "</yellow></gray>"));
            lore.add(MessageUtils.parse("<gray>В цене содержится: <yellow>x" + denCount + "</yellow></gray>"));
            lore.add(Component.empty());
            if (isActive) {
                lore.add(MessageUtils.parse("<green>✔ АКТИВНАЯ МОНЕТА</green>"));
            } else {
                lore.add(MessageUtils.parse("<yellow>Shift+Клик: выбрать активной</yellow>"));
            }
            lore.add(MessageUtils.parse("<aqua>ЛКМ: −1 к номиналу</aqua>"));
            lore.add(MessageUtils.parse("<aqua>ПКМ: +1 к номиналу</aqua>"));

            ItemStack denItem = GuiUtils.createItem(mat, MessageUtils.parse(coinName), lore);
            if (isActive) {
                ItemMeta dMeta = denItem.getItemMeta();
                if (dMeta != null) {
                    dMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    dMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    denItem.setItemMeta(dMeta);
                }
            }
            inventory.setItem(slot, denItem);
        }

        // Slot 20: Confirm
        ItemStack confirm = GuiUtils.createHead(HeadTextures.HEAD_CONFIRM,
                MessageUtils.parse("<green><bold>Подтвердить</bold></green>"),
                List.of(
                        MessageUtils.parse("<gray>Выставить лот по цене:</gray>"),
                        MessageUtils.parse("<gold>" + CoinFormat.formatGlyphs(currentPrice) + "</gold>"),
                        Component.empty(),
                        MessageUtils.parse("<yellow>Клик: сохранить</yellow>")
                ));
        inventory.setItem(20, confirm);

        // Slot 24: Cancel
        ItemStack cancel = GuiUtils.createHead(HeadTextures.HEAD_DELETE_NO,
                MessageUtils.parse("<red><bold>Отмена</bold></red>"),
                List.of(MessageUtils.parse("<gray>Вернуться назад без сохранения</gray>")));
        inventory.setItem(24, cancel);
    }

    private void fillFrame() {
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < SIZE; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        String itemKey = template.getType().name();
        long min = plugin.getMarketConfig().minPrice(itemKey);
        long max = plugin.getMarketConfig().maxPrice(itemKey);

        // Preset Min
        if (slot == 2) {
            currentPrice = Math.max(1L, min);
            render();
            return;
        }

        // Decrease amount
        if (slot == 3 && maxAvailableAmount > 1) {
            int step = event.isShiftClick() ? 8 : 1;
            currentAmount = Math.max(1, currentAmount - step);
            render();
            return;
        }

        // Increase amount
        if (slot == 5 && maxAvailableAmount > 1) {
            int step = event.isShiftClick() ? 8 : 1;
            currentAmount = Math.min(maxAvailableAmount, currentAmount + step);
            render();
            return;
        }

        // Preset Reset
        if (slot == 6) {
            currentPrice = Math.max(1L, min);
            render();
            return;
        }

        // Denomination slots
        int[] denSlots = {12, 13, 14, 15, 16};
        for (int i = 0; i < denSlots.length && i < denominations.size(); i++) {
            if (slot == denSlots[i]) {
                Denomination den = denominations.get(i);
                if (event.isShiftClick()) {
                    activeDenomination = den;
                    render();
                    return;
                }
                if (event.isLeftClick()) {
                    currentPrice = Math.max(min > 0 ? min : 1L, currentPrice - den.value());
                } else if (event.isRightClick()) {
                    currentPrice = Math.min(max, currentPrice + den.value());
                }
                render();
                return;
            }
        }

        // Confirm
        if (slot == 20) {
            viewer.closeInventory();
            if (onConfirm != null) {
                onConfirm.accept(currentAmount, currentPrice);
            }
            return;
        }

        // Cancel
        if (slot == 24) {
            viewer.closeInventory();
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }
}
