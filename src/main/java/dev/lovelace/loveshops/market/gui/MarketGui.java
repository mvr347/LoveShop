package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Base of every market menu. The menu IS the inventory holder, so the listener recognises it by
 * type (not by title text) and cancels every click and drag: nothing can be moved in or out of a
 * market menu except through the explicit handlers, which is what keeps it dupe-proof.
 */
public abstract class MarketGui implements InventoryHolder {

    protected final LoveShops plugin;
    protected final Player viewer;
    protected Inventory inventory;

    protected MarketGui(LoveShops plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public Player viewer() { return viewer; }

    /** The trade point this menu shows, {@code null} for menus that are not tied to one. */
    public abstract UUID pointId();

    /** (Re)draws the whole menu into {@link #inventory}. */
    public abstract void render();

    /** A click inside the top inventory; the event is already cancelled. */
    public abstract void handleClick(InventoryClickEvent event);

    /**
     * A click in the viewer's own inventory while this menu is open; the event is already cancelled.
     * Menus that take items from the player's inventory override this; the default does nothing.
     */
    public void handleBottomClick(InventoryClickEvent event) {
    }

    /**
     * A drag over this menu; the event is already cancelled. Return {@code true} if the menu handled
     * it itself (nothing is ever moved by vanilla either way).
     */
    public boolean handleDrag(InventoryDragEvent event) {
        return false;
    }

    /** Called when the viewer closes the menu. */
    public void onClose() {
        plugin.getTradePointManager().unregisterViewer(viewer, this);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected void show(Inventory inv) {
        this.inventory = inv;
        render();
        plugin.getTradePointManager().registerViewer(this);
        viewer.openInventory(inv);
    }

    protected void refreshClient() {
        viewer.updateInventory();
    }

    /** A textured head whose name and lore are parsed FOR THE VIEWER, so coin glyphs resolve reliably. */
    protected ItemStack head(String base64, String name, List<String> lore) {
        ItemStack item = GuiUtils.createCustomHead(base64, " ", null);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse(viewer, name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(line -> MessageUtils.parse(viewer, line)).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Closes the menu, asks for a whole number in chat and hands it to {@code onValue}. A wrong
     * number or a cancel runs {@code reopen}; after a valid answer {@code reopen} runs too unless
     * {@code onValue} started another prompt (a follow-up question).
     */
    protected void promptNumber(String messageKey, long min, long max, Runnable reopen, LongConsumer onValue, String... placeholders) {
        viewer.closeInventory();
        List<String> kv = new ArrayList<>(List.of(placeholders));
        kv.add("min");
        kv.add(String.valueOf(min));
        kv.add("max");
        kv.add(String.valueOf(max));
        plugin.getMarketMessages().send(viewer, messageKey, kv.toArray(new String[0]));
        plugin.getChatPromptService().ask(viewer, text -> {
            String digits = text.replace(" ", "").replace("_", "");
            long value;
            try {
                if (!digits.matches("\\d{1,12}")) throw new NumberFormatException();
                value = Long.parseLong(digits);
            } catch (NumberFormatException e) {
                plugin.getMarketMessages().send(viewer, "prompt-invalid");
                reopen.run();
                return;
            }
            if (value < min || value > max) {
                plugin.getMarketMessages().send(viewer, "prompt-out-of-range", "min", String.valueOf(min), "max", String.valueOf(max));
                reopen.run();
                return;
            }
            onValue.accept(value);
            if (!plugin.getChatPromptService().has(viewer)) reopen.run();
        }, reopen);
    }

    /** Like {@link #promptNumber} but for a line of text; {@code "-"} gives an empty string. */
    protected void promptText(String messageKey, Runnable reopen, Consumer<String> onText, String... placeholders) {
        viewer.closeInventory();
        plugin.getMarketMessages().send(viewer, messageKey, placeholders);
        plugin.getChatPromptService().ask(viewer, text -> {
            onText.accept(text.equals("-") ? "" : text);
            if (!plugin.getChatPromptService().has(viewer)) reopen.run();
        }, reopen);
    }

    protected void promptPlayer(String messageKey, Runnable reopen, Consumer<Player> onPlayer, String... placeholders) {
        promptText(messageKey, reopen, text -> {
            Player target = org.bukkit.Bukkit.getPlayerExact(text);
            if (target == null) target = org.bukkit.Bukkit.getPlayer(text);
            if (target == null) {
                plugin.getMarketMessages().send(viewer, "prompt-invalid");
                reopen.run();
                return;
            }
            onPlayer.accept(target);
        }, placeholders);
    }
}
