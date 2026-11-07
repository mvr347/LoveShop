package dev.lovelace.loveshops.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The server's two-button confirmation menu (owner decision 2026-11-05): 27 slots, a resource-pack background in
 * the title ({@code %img_offset_-8%%img_confirm_menu%}) and two big invisible button zones. Every slot of a zone
 * carries the same item, so hovering anywhere in the zone shows the button's name and lore.
 * Without ItemsAdder / PlaceholderAPI (no background, no invisible item) callers keep their 9-slot window.
 */
public final class ConfirmMenuLayout {

    public static final int SIZE = 27;
    public static final int[] CONFIRM_ZONE = {1, 2, 3, 10, 11, 12, 19, 20, 21};
    public static final int[] CANCEL_ZONE = {5, 6, 7, 14, 15, 16, 23, 24, 25};
    public static final String TITLE = "<white>%img_offset_-8%%img_confirm_menu%";
    /** ItemsAdder namespaced id. "itemsadder-" is only the DeluxeMenus prefix; ItemsAdder itself does not know it. */
    public static final String INVISIBLE_ITEM = "voidcore:invisible";

    private static boolean fallbackLogged;

    private static final java.util.regex.Pattern COLON_TOKEN = java.util.regex.Pattern.compile(":[A-Za-z0-9_\\-]{2,}:");

    private ConfirmMenuLayout() {
    }

    public static boolean isConfirm(int slot) {
        return contains(CONFIRM_ZONE, slot);
    }

    public static boolean isCancel(int slot) {
        return contains(CANCEL_ZONE, slot);
    }

    private static boolean contains(int[] zone, int slot) {
        for (int s : zone) if (s == slot) return true;
        return false;
    }

    /**
     * True when the background glyph and the invisible item can both be produced for this viewer. The title is
     * resolved here too: if the font image is missing or its placeholder stays unresolved, the invisible buttons
     * would have no background and no visible labels, so the visible 9-slot window must be kept.
     */
    public static boolean available(Player viewer) {
        String why = unavailableReason(viewer);
        if (why != null && !fallbackLogged) {
            fallbackLogged = true;
            Bukkit.getLogger().warning("[LoveShops] Confirm menu: the 27-slot window is not used, the 9-slot window is shown instead: " + why);
        }
        return why == null;
    }

    /** Why the 27-slot window cannot be shown, or null when it can. */
    static String unavailableReason(Player viewer) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return "ItemsAdder is not enabled";
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return "PlaceholderAPI is not enabled";
        if (ItemResolver.resolveItemsAdder(itemId(INVISIBLE_ITEM)) == null)
            return "ItemsAdder has no item '" + itemId(INVISIBLE_ITEM) + "'";
        if (!backgroundResolved(ItemsAdderFontHook.resolve(viewer, TITLE)))
            return "the background glyph in '" + TITLE + "' does not resolve (font image missing?)";
        return null;
    }

    /** The id as ItemsAdder knows it: the DeluxeMenus prefix "itemsadder-" is dropped. */
    public static String itemId(String id) {
        return id != null && id.startsWith("itemsadder-") ? id.substring("itemsadder-".length()) : id;
    }

    /** A title counts as resolved when no unresolved {@code %placeholder%} is left in it. */
    public static boolean backgroundResolved(String resolved) {
        return resolved != null && !resolved.isBlank()
                && !resolved.contains("%img_") && !resolved.contains("%offset")
                // ItemsAdderFontHook rewrites a placeholder it could not resolve into the ":name:" form.
                && !COLON_TOKEN.matcher(resolved).find();
    }

    public static Component title(Player viewer) {
        return MessageUtils.parse(viewer, TITLE);
    }

    /** The invisible item with a name and lore, or null when ItemsAdder does not know it. */
    @Nullable
    public static ItemStack button(Player viewer, String name, List<String> lore) {
        ItemStack item = ItemResolver.resolveItemsAdder(itemId(INVISIBLE_ITEM));
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(GuiUtils.line(viewer, name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(line -> GuiUtils.line(viewer, line)).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Fills both zones of a 27-slot inventory with the given buttons. */
    public static void fill(org.bukkit.inventory.Inventory inventory, ItemStack confirm, ItemStack cancel) {
        for (int slot : CONFIRM_ZONE) inventory.setItem(slot, confirm);
        for (int slot : CANCEL_ZONE) inventory.setItem(slot, cancel);
    }
}
