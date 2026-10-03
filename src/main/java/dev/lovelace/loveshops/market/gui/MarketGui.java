package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Click handlers by raw slot, filled while rendering; the default {@link #handleClick} runs them. */
    protected final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    /** Current page of a paged menu (0-based). */
    protected int page = 0;

    /** A header control button together with what it does. */
    public record Control(ItemStack item, Consumer<InventoryClickEvent> action) {}

    protected MarketGui(LoveShops plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public Player viewer() { return viewer; }

    /** The trade point this menu shows, {@code null} for menus that are not tied to one. */
    public abstract UUID pointId();

    /** {@code true} for the owner's own menus: they are closed when the point changes hands. */
    public boolean ownerMenu() { return false; }

    /** (Re)draws the whole menu into {@link #inventory}. */
    public abstract void render();

    /** A click inside the top inventory; the event is already cancelled. Runs the slot's registered action. */
    public void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) action.accept(event);
    }

    /**
     * An item the viewer put on a top-inventory slot (a click with the cursor, or a drag that ended
     * on exactly one slot). Return {@code true} when the menu took the whole stack: the listener then
     * empties the cursor. The default takes nothing, so the item simply stays on the cursor.
     */
    public boolean acceptCursor(int topSlot, ItemStack cursor) {
        return false;
    }

    /**
     * A click in the viewer's own inventory while this menu is open; the event is already cancelled.
     * Menus that take items from the player's inventory override this; the default does nothing.
     */
    public void handleBottomClick(InventoryClickEvent event) {
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

    // ------------------------------------------------------------------ layout helpers

    /** Clears the menu and redraws the glass frame; handlers registered earlier are dropped. */
    protected void frame() {
        MarketLayout.frame(inventory);
        actions.clear();
    }

    /** Text from lang.yml ({@code market.<key>}) for the viewer, placeholders applied. */
    protected String t(String key, String... kv) {
        return plugin.getMarketMessages().raw(key, kv);
    }

    /** All lines of a lore text from lang.yml. */
    protected List<String> lines(String key, String... kv) {
        return plugin.getMarketMessages().lines(key, kv);
    }

    /** A duration written with the unit words of lang.yml ({@code market.time-units}). */
    protected String duration(long millis) {
        List<String> units = lines("time-units");
        return dev.lovelace.loveshops.market.DurationText.format(millis,
                units.size() >= 4 ? units.toArray(new String[0]) : new String[]{"d", "h", "min", "<1 min"});
    }

    /** A textured head whose name and lore both come from lang.yml. */
    protected ItemStack tile(String base64, String nameKey, String loreKey, String... kv) {
        return head(base64, t(nameKey, kv), loreKey == null ? List.of() : lines(loreKey, kv));
    }

    protected void button(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    /** Puts the menu's control buttons into header slots 2-7, spread by their number (gui_gen rule 4). */
    protected void controls(List<Control> controls) {
        int[] slots = MarketLayout.controlSlots(controls.size());
        for (int i = 0; i < slots.length; i++) {
            button(slots[i], controls.get(i).item(), controls.get(i).action());
        }
    }

    /**
     * Puts the buttons of a menu into one row of the work zone (columns 1-7, centred), the header
     * keeping only the head and glass. Several rows: call it once per row.
     */
    protected void rowButtons(int rowStart, List<Control> controls) {
        int[] slots = MarketLayout.rowSlots(rowStart, controls.size());
        for (int i = 0; i < slots.length; i++) {
            button(slots[i], controls.get(i).item(), controls.get(i).action());
        }
    }

    /** A free shelf / order slot: a plus head that is also the drop target for an item. */
    protected ItemStack freeShelfTile() {
        return tile(HeadTextures.BUTTON_PLUS, "gui-shelf-free", "gui-shelf-free-lore");
    }

    /** Footer: Back (only when {@code back} is given, else the glass stays) and Close, always. */
    protected void footer(Runnable back) {
        int size = inventory.getSize();
        if (back != null) {
            button(MarketLayout.backSlot(size), tile(HeadTextures.BUTTON_BACK, "gui-back", "gui-back-lore"), e -> back.run());
        }
        button(MarketLayout.closeSlot(size), tile(HeadTextures.BUTTON_CLOSE, "gui-close", "gui-close-lore"),
                e -> viewer.closeInventory());
    }

    /**
     * Pagination arrows of a 54-slot menu, on the side walls of the last work row (slots 36 and 44,
     * gui_gen rule 6); a wall without an arrow stays empty. Returns the page, clamped to the range.
     */
    protected int pager(int totalPages) {
        int pages = Math.max(1, totalPages);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        if (page > 0) {
            button(36, tile(HeadTextures.ARROW_LEFT, "gui-page-prev", "gui-page-lore",
                    "page", String.valueOf(page), "pages", String.valueOf(pages)), e -> { page--; render(); });
        }
        if (page < pages - 1) {
            button(44, tile(HeadTextures.ARROW_RIGHT, "gui-page-next", "gui-page-lore",
                    "page", String.valueOf(page + 2), "pages", String.valueOf(pages)), e -> { page++; render(); });
        }
        return page;
    }

    /** Takes the clicked stack out of the clicked inventory and returns a copy; {@code null} for an empty slot. */
    protected ItemStack takeClicked(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir() || event.getClickedInventory() == null) return null;
        ItemStack copy = current.clone();
        event.getClickedInventory().setItem(event.getSlot(), null);
        return copy;
    }

    /** Returns an item to the viewer; whatever does not fit is dropped at their feet. */
    protected void giveBack(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        for (ItemStack rest : viewer.getInventory().addItem(item).values()) {
            viewer.getWorld().dropItem(viewer.getLocation(), rest);
        }
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
