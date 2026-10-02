package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Подменю «Торговля»: продажа, скупка, касса.
 */
public final class StallTradeMenuGui extends MarketGui {

    private static final int SIZE = 27;
    private final TradePoint point;

    public StallTradeMenuGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<gold>Торговля</gold>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        inventory.setItem(11, sellItem());
        inventory.setItem(13, buyItem());
        inventory.setItem(15, tillItem());
        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK,
                "<yellow>Назад</yellow>", List.of("<gray>В меню точки</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>", List.of()));
    }

    private ItemStack sellItem() {
        return icon(Material.GOLD_INGOT, "<gold>Продажа</gold>", List.of(
                "<gray>Витрина товаров на продажу.</gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть</gray>"
        ));
    }

    private ItemStack buyItem() {
        return icon(Material.EMERALD, "<aqua>Скупка</aqua>", List.of(
                "<gray>Заказы на покупку у игроков.</gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть</gray>"
        ));
    }

    private ItemStack tillItem() {
        long till = point.tillCoins();
        String money = CoinFormat.formatGlyphs(till);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Касса точки</gray>");
        lore.add("<white>" + money + "</white>");
        lore.add("");
        lore.add("<green>ЛКМ </green><gray>— забрать</gray>");
        lore.add("<yellow>Shift </yellow><gray>— внести с руки</gray>");
        return icon(Material.CHEST, "<gold>Касса</gold>", lore);
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtils.parse(viewer, name));
        List<Component> compLore = new ArrayList<>();
        for (String line : lore) compLore.add(MessageUtils.parse(viewer, line));
        meta.lore(compLore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        ClickType click = event.getClick();
        if (slot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }
        if (slot == MarketLayout.backSlot(SIZE)) {
            new StallOwnerGui(plugin, viewer, point).open();
            return;
        }
        switch (slot) {
            case 11 -> new StallOwnerSellGui(plugin, viewer, point).open();
            case 13 -> new StallOwnerBuyGui(plugin, viewer, point).open();
            case 15 -> {
                if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    depositTill();
                } else {
                    withdrawTill();
                }
            }
        }
    }

    private void withdrawTill() {
        TradePointManager.TillResult res = plugin.getTradePointManager().collectTill(viewer, point);
        var msg = plugin.getMarketMessages();
        if (res.ok()) {
            msg.send(viewer, "till-collected", "money", plugin.getMarketStyle().money(res.amount()));
        } else if ("till-empty".equals(res.reason())) {
            msg.send(viewer, "till-empty");
        } else if ("till-no-space".equals(res.reason())) {
            msg.send(viewer, "till-no-space");
        } else {
            msg.send(viewer, "economy-down");
        }
        render();
    }

    private void depositTill() {
        var eco = plugin.getEconomy().orElse(null);
        if (eco == null) {
            plugin.getMarketMessages().send(viewer, "economy-down");
            return;
        }
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        if (hand == null || !eco.isCoin(hand)) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>Возьмите монеты в руку для внесения в кассу!</red>"));
            return;
        }
        long value = eco.valueOf(hand);
        if (value <= 0) return;
        if (eco.charge(viewer, value)) {
            point.tillCoins(point.tillCoins() + value);
            plugin.getTradePointManager().save(point);
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>В кассу внесено: " + CoinFormat.formatGlyphs(eco, value) + "</green>"));
            render();
        }
    }
}
