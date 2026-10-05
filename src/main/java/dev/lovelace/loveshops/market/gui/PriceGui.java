package dev.lovelace.loveshops.market.gui;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.Hints;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lot price menu (27 slots).
 * <ul>
 *   <li>Work zone: the lot (a stack: click changes the amount) at 11, the price button at 13 and Confirm at 15,
 *       which stays inactive until a valid price is set.</li>
 *   <li>Price button: the price as a list of coin glyphs; <b>Shift</b> switches the active coin,
 *       <b>left click</b> adds one of it, <b>right click</b> takes one away. Shows minimum price:
 *       1 copper coin.</li>
 *   <li>Footer: Back (if the caller can restore the previous state) and Close. Leaving by any way
 *       except Confirm (Close, Esc) hands the item back through {@code onCancel}.</li>
 * </ul>
 */
public final class PriceGui extends MarketGui {

    private static final int SIZE = 27;

    private final ItemStack template;
    private final boolean editsOnlyPrice;
    private final int maxAmount;
    private final PriceInput input;
    private final BiConsumer<Integer, Long> onConfirm;
    private final Runnable onCancel;
    private final UUID pointId;
    private int amount;
    private boolean finished;

    /**
     * @param onConfirm receives (amount, unit price); runs after the menu is closed
     * @param onCancel  gives the item back / restores the previous state; must not open another menu
     *                  (the player may have pressed Esc), the caller decides what to show next
     */
    public PriceGui(LoveShops plugin, Player viewer, UUID pointId, ItemStack item, boolean editsOnlyPrice,
                    long initialPrice, BiConsumer<Integer, Long> onConfirm, Runnable onCancel) {
        super(plugin, viewer);
        this.pointId = pointId;
        this.template = item.clone();
        this.editsOnlyPrice = editsOnlyPrice;
        this.maxAmount = editsOnlyPrice ? 1 : Math.max(1, item.getAmount());
        this.amount = maxAmount;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        List<Denomination> dens = new ArrayList<>(plugin.getEconomy().map(LoveEconomy::denominations).orElse(List.of()));
        dens.removeIf(d -> d.value() <= 0);
        dens.sort(Comparator.comparingLong(Denomination::value));
        long[] units = dens.stream().mapToLong(Denomination::value).toArray();
        this.input = new PriceInput(units, 1L, plugin.getMarketConfig().priceMax(), initialPrice);
        this.input.startAtUnit(plugin.getMarketConfig().priceStartUnit());
    }

    /** Convenience: only the unit price matters (editing an existing lot, buy orders). */
    public PriceGui(LoveShops plugin, Player viewer, ItemStack item, boolean editsOnlyPrice,
                    Consumer<Long> onPrice, Runnable onCancel) {
        this(plugin, viewer, null, item, editsOnlyPrice, 0L, (amt, price) -> onPrice.accept(price), onCancel);
    }

    /** Convenience: amount and price (a new lot from a stack). */
    public PriceGui(LoveShops plugin, Player viewer, ItemStack item, boolean editsOnlyPrice,
                    BiConsumer<Integer, Long> onConfirm, Runnable onCancel) {
        this(plugin, viewer, null, item, editsOnlyPrice, 0L, onConfirm, onCancel);
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-price-title"))));
    }

    @Override
    public UUID pointId() {
        return pointId;
    }

    /** Owner flows pass their point; the stand-alone price picker (no point) is not an owner menu. */
    @Override
    public boolean ownerMenu() { return pointId != null; }

    @Override
    public void render() {
        frame();

        button(11, lotItem(), this::clickAmount);
        button(13, priceButton(), this::clickPrice);
        button(15, confirmButton(), this::clickConfirm);

        if (onCancel != null) {
            button(MarketLayout.backSlot(SIZE), tile(HeadTextures.BUTTON_BACK, "gui-back", "gui-back-lore"), e -> {
                finished = true;
                viewer.closeInventory();
                onCancel.run();
            });
        }
        button(MarketLayout.closeSlot(SIZE), tile(HeadTextures.BUTTON_CLOSE, "gui-close", "gui-close-lore"), e -> viewer.closeInventory());

        refreshClient();
    }

    // ------------------------------------------------------------------ items

    private ItemStack lotItem() {
        ItemStack item = template.clone();
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, "<dark_gray>▪</dark_gray> <gray>Количество: <white>" + amount
                    + "</white>" + (maxAmount > 1 ? " <dark_gray>из " + maxAmount + "</dark_gray>" : " шт.") + "</gray>"));
            if (input.price() > 0) {
                lore.add(MessageUtils.parse(viewer, "<dark_gray>▪</dark_gray> <gray>Цена за единицу:</gray>"));
                for (String line : CoinFormat.glyphLineStrings(plugin.getEconomy().orElse(null), input.price())) {
                    lore.add(MessageUtils.parse(viewer, line));
                }
            }
            if (maxAmount > 1) {
                lore.add(Component.empty());
                lore.add(MessageUtils.parse(viewer, Hints.amountStepper(8)));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack priceButton() {
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        List<String> lore = new ArrayList<>(lines("gui-price-btn-top"));
        lore.addAll(CoinFormat.glyphLineStrings(eco, input.price()));
        String coin = coinGlyph(input.activeIndex());
        String copper = copperGlyph();
        List<String> bottom = lines("gui-price-btn-bottom", "coin", coin, "copper", copper, "hint", Hints.coinPicker());
        lore.addAll(bottom);
        boolean hasMin = bottom.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("минимальная цена"));
        if (!hasMin) {
            lore.add("<gray>Минимальная цена </gray>" + copper + " <white>х1</white>");
        }
        return head(HeadTextures.BANKER_ACCOUNT, t("gui-price-btn"), lore);
    }

    private ItemStack confirmButton() {
        if (input.valid()) {
            LoveEconomy eco = plugin.getEconomy().orElse(null);
            List<String> lore = new ArrayList<>(lines("gui-price-confirm-top"));
            lore.addAll(CoinFormat.glyphLineStrings(eco, input.price()));
            if (!editsOnlyPrice && amount > 1) {
                lore.add("");
                lore.add(t("gui-price-confirm-total", "amount", String.valueOf(amount)));
                lore.addAll(CoinFormat.glyphLineStrings(eco, input.price() * (long) amount));
            }
            lore.addAll(lines("gui-price-confirm-bottom"));
            return head(HeadTextures.HEAD_CONFIRM, t("gui-price-confirm"), lore);
        }
        String reason = input.price() <= 0 ? t("gui-price-reason-unset") : t("gui-price-reason-low");
        return head(HeadTextures.HEAD_DELETE_NO, t("gui-price-confirm-off"), lines("gui-price-confirm-off-lore", "reason", reason));
    }

    private String coinGlyph(int index) {
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return "";
        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.removeIf(d -> d.value() <= 0);
        dens.sort(Comparator.comparingLong(Denomination::value));
        return index < dens.size() ? CoinFormat.getCoinGlyph(dens.get(index)) : "";
    }

    private String copperGlyph() {
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return "%img_copper_coin%";
        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.removeIf(d -> d.value() <= 0);
        for (Denomination d : dens) {
            if (d.itemId() != null && d.itemId().toLowerCase(Locale.ROOT).contains("copper")) {
                return CoinFormat.getCoinGlyph(d);
            }
        }
        dens.sort(Comparator.comparingLong(Denomination::value));
        return !dens.isEmpty() ? CoinFormat.getCoinGlyph(dens.get(0)) : "%img_copper_coin%";
    }

    // ------------------------------------------------------------------ clicks

    private void clickPrice(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click.isShiftClick()) {
            input.cycle();
        } else if (click.isRightClick()) {
            input.subtract();
        } else if (click.isLeftClick()) {
            input.add();
        } else {
            return;
        }
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        render();
    }

    private void clickAmount(InventoryClickEvent event) {
        int step = event.getClick().isShiftClick() ? 8 : 1;
        if (event.getClick().isRightClick()) {
            amount = Math.max(1, amount - step);
        } else {
            amount = Math.min(maxAmount, amount + step);
        }
        render();
    }

    private void clickConfirm(InventoryClickEvent event) {
        if (!input.valid()) {
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        finished = true;
        viewer.closeInventory();
        if (onConfirm != null) onConfirm.accept(editsOnlyPrice ? 1 : amount, input.price());
    }

    /** Close button, Esc, a kick: any exit that is not Confirm hands the item back. */
    @Override
    public void onClose() {
        super.onClose();
        if (finished) return;
        finished = true;
        if (onCancel != null) onCancel.run();
    }
}
