package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.HeadSaleManager;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * Selling a hunt's head to the Wanderer. The "sell" button is a menu-control button of the Wanderer menus
 * (gui_gen: a single one sits in slot 4 of the Header) and exists only while the player carries a head from
 * a finished hunt; without one the slot stays glass. A click leads to a confirmation menu (the 9-slot
 * confirm layout of the standard) that names the head and the price.
 */
public final class WandererHeadSale {
    public static final int BUTTON_SLOT = 4;
    public static final String CONFIRM_TITLE = "Странник (Продажа головы)";
    public static final int CONFIRM_YES = 1;
    public static final int CONFIRM_NO = 7;
    private static final String VICTIM_KEY = "head_victim";

    private WandererHeadSale() {
    }

    /** Puts the sell button into slot 4 of a Wanderer menu when the player has a head to sell; otherwise the slot is left as it is. */
    public static void apply(LoveShops plugin, Player player, Inventory inventory) {
        if (!plugin.getHeadSaleManager().enabled()) {
            return;
        }
        ItemStack head = HeadSaleManager.firstHead(player);
        if (head == null) {
            return;
        }
        inventory.setItem(BUTTON_SLOT, button(plugin, player, head));
    }

    private static ItemStack button(LoveShops plugin, Player player, ItemStack head) {
        UUID victim = HeadSaleManager.victimOf(head);
        var quote = plugin.getHeadSaleManager().quote(victim);
        var lang = plugin.getLangManager();
        String count = String.valueOf(HeadSaleManager.countHeads(player));
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(victim));
        }
        if (meta != null) {
            boolean blocked = quote.cooldownLeftMillis() > 0L;
            String time = TimeUtils.formatRemainingTime((quote.cooldownLeftMillis() + 999L) / 1000L);
            String price = CoinFormat.formatGlyphs(quote.price());
            String nameKey = blocked ? "gui.wanderer.head-sell-blocked" : "gui.wanderer.head-sell";
            String loreKey = blocked ? "gui.wanderer.head-sell-blocked-lore" : "gui.wanderer.head-sell-lore";
            meta.displayName(GuiUtils.line(player, lang.text(nameKey, "count", count)));
            meta.lore(lang.lines(loreKey, "name", quote.name(), "price", price, "time", time).stream()
                    .map(line -> GuiUtils.line(player, line)).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Click on the sell button: opens the confirmation, or explains why the head cannot be sold now. */
    public static void onButtonClick(LoveShops plugin, Player player) {
        ItemStack head = HeadSaleManager.firstHead(player);
        if (head == null) {
            player.closeInventory();
            return;
        }
        UUID victim = HeadSaleManager.victimOf(head);
        var quote = plugin.getHeadSaleManager().quote(victim);
        if (quote.cooldownLeftMillis() > 0L) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            MessageUtils.event(player, plugin.getLangManager().text("gui.wanderer.head-cooldown",
                    "name", quote.name(), "time", TimeUtils.formatRemainingTime((quote.cooldownLeftMillis() + 999L) / 1000L)));
            return;
        }
        openConfirm(plugin, player, quote);
    }

    private static void openConfirm(LoveShops plugin, Player player, HeadSaleManager.Quote quote) {
        var lang = plugin.getLangManager();
        Inventory inv = Bukkit.createInventory(null, 9, Component.text(CONFIRM_TITLE).color(net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
        ItemStack filler = GuiUtils.createFiller();
        for (int slot : new int[] {0, 2, 6, 8}) {
            inv.setItem(slot, filler);
        }
        String price = CoinFormat.formatGlyphs(quote.price());
        ItemStack yes = GuiUtils.createCustomHead(dev.lovelace.loveshops.textures.HeadTextures.HEAD_CONFIRM,
                lang.text("gui.wanderer.head-confirm-yes"), lang.lines("gui.wanderer.head-confirm-yes-lore", "name", quote.name(), "price", price));
        ItemMeta meta = yes.getItemMeta();
        meta.getPersistentDataContainer().set(dev.lovelace.loveshops.utils.Keys.of(plugin, VICTIM_KEY),
                org.bukkit.persistence.PersistentDataType.STRING, quote.victim().toString());
        yes.setItemMeta(meta);
        inv.setItem(CONFIRM_YES, yes);
        inv.setItem(CONFIRM_NO, GuiUtils.createCustomHead(dev.lovelace.loveshops.textures.HeadTextures.HEAD_DELETE_NO,
                lang.text("gui.wanderer.head-confirm-no"), lang.lines("gui.wanderer.head-confirm-no-lore")));
        player.openInventory(inv);
    }

    /** Click in the confirmation menu. */
    public static void onConfirmClick(LoveShops plugin, Player player, int slot, ItemStack clicked) {
        if (slot == CONFIRM_NO) {
            player.closeInventory();
            return;
        }
        if (slot != CONFIRM_YES || clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String raw = clicked.getItemMeta().getPersistentDataContainer().get(
                dev.lovelace.loveshops.utils.Keys.of(plugin, VICTIM_KEY), org.bukkit.persistence.PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        var sale = plugin.getHeadSaleManager().sell(player, UUID.fromString(raw));
        player.closeInventory();
        var lang = plugin.getLangManager();
        switch (sale.result()) {
            case SOLD -> MessageUtils.important(player, lang.text("gui.wanderer.head-sold",
                    "name", sale.quote().name(), "price", CoinFormat.formatGlyphs(sale.quote().price())));
            case COOLDOWN -> MessageUtils.event(player, lang.text("gui.wanderer.head-cooldown", "name", sale.quote().name(),
                    "time", TimeUtils.formatRemainingTime((sale.quote().cooldownLeftMillis() + 999L) / 1000L)));
            case NO_HEAD -> MessageUtils.event(player, lang.text("gui.wanderer.head-missing"));
            case NO_ECONOMY, DISABLED -> MessageUtils.event(player, lang.text("gui.wanderer.head-unavailable"));
        }
    }

    /** Every Wanderer menu title that must stay cancel-all for clicks. */
    public static List<String> titles() {
        return List.of(CONFIRM_TITLE);
    }
}
