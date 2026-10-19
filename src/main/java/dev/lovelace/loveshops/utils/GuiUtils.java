package dev.lovelace.loveshops.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

public class GuiUtils {

    public static final String BTN_CLOSE_BASE64 = dev.lovelace.loveshops.textures.HeadTextures.BUTTON_CLOSE;

    /**
     * Item names and lore are italic by default on the client. The standard strips it in one place instead of a
     * {@code <!i>} tag per line: italics is turned off unless the text sets it itself (an explicit {@code <i>} stays).
     */
    public static Component noItalic(Component component) {
        return component == null ? null : component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> noItalic(List<Component> components) {
        return components == null ? null : components.stream().map(GuiUtils::noItalic).toList();
    }

    /** A GUI text line: parsed for the viewer (coin glyphs resolve) with italics stripped. */
    public static Component line(Player viewer, String miniMessage) {
        return noItalic(MessageUtils.parse(viewer, miniMessage));
    }

    public static ItemStack createFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(Component.text(" ")));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    public static ItemStack createPlayerProfileHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            skullMeta.displayName(line(player, profileText("gui.profile.name", "<gold>Профиль <dark_gray>| <white>{player}", player)));
            skullMeta.lore(profileLore(player));
            head.setItemMeta(skullMeta);
        }
        return head;
    }

    private static String profileText(String path, String fallback, Player player) {
        dev.lovelace.loveshops.LoveShops plugin = dev.lovelace.loveshops.LoveShops.getInstance();
        String raw = plugin == null || plugin.getLangManager() == null ? fallback : plugin.getLangManager().getRaw(path, fallback);
        return raw.replace("{player}", player.getName());
    }

    private static List<Component> profileLore(Player player) {
        dev.lovelace.loveshops.LoveShops plugin = dev.lovelace.loveshops.LoveShops.getInstance();
        List<String> raw = plugin == null || plugin.getLangManager() == null
                ? List.of("<dark_gray>Профиль игрока", "", "<dark_gray>▪ <gray>Вы вошли как:", "  <white>{player}")
                : plugin.getLangManager().lines("gui.profile.lore", "player", player.getName());
        return raw.stream().map(l -> line(player, l.replace("{player}", player.getName()))).toList();
    }

    /**
     * Writes a slot only when its content changed. Menus that refresh every second must not resend identical items:
     * the client reloads head skins on every resend, which shows up as flicker.
     */
    public static void putIfChanged(Inventory inventory, int slot, ItemStack item) {
        ItemStack current = inventory.getItem(slot);
        if (item == null) {
            if (current != null) inventory.setItem(slot, null);
            return;
        }
        if (current == null || current.getAmount() != item.getAmount() || !current.isSimilar(item)) {
            inventory.setItem(slot, item);
        }
    }

    /** Centre card of a list menu that has nothing to show: "Пока ничего нет". */
    public static ItemStack emptyCard(String hint) {
        dev.lovelace.loveshops.LoveShops plugin = dev.lovelace.loveshops.LoveShops.getInstance();
        boolean haveLang = plugin != null && plugin.getLangManager() != null;
        String title = haveLang ? plugin.getLangManager().getRaw("gui.empty-title", "<gray>Список <dark_gray>| <yellow>Пуст")
                : "<gray>Список <dark_gray>| <yellow>Пуст";
        String subtitle = haveLang ? plugin.getLangManager().getRaw("gui.empty-subtitle", "<dark_gray>Список пуст")
                : "<dark_gray>Список пуст";
        return createCustomHead(dev.lovelace.loveshops.textures.HeadTextures.BANKER_DEPOSIT_EMPTY,
                title,
                hint == null || hint.isBlank() ? List.of(subtitle) : List.of(subtitle, "", hint));
    }

    /**
     * Same texture, same profile id. A random id per call made every rebuilt head look different to the client,
     * which reloaded the skin each time the menu refreshed (flicker).
     */
    static UUID textureProfileId(String base64) {
        return UUID.nameUUIDFromBytes(base64.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ItemStack createCustomHead(String base64, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(MessageUtils.parse(name)));
            if (lore != null) {
                meta.lore(lore.stream().map(l -> noItalic(MessageUtils.parse(l))).toList());
            }
            try {
                PlayerProfile profile = Bukkit.createProfile(textureProfileId(base64));
                profile.setProperty(new ProfileProperty("textures", base64));
                meta.setPlayerProfile(profile);
            } catch (Throwable ignored) {}
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createItem(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(noItalic(name));
            if (lore != null) meta.lore(noItalic(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createHead(String base64, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(noItalic(name));
            if (lore != null) meta.lore(noItalic(lore));
            try {
                PlayerProfile profile = Bukkit.createProfile(textureProfileId(base64));
                profile.setProperty(new ProfileProperty("textures", base64));
                meta.setPlayerProfile(profile);
            } catch (Throwable ignored) {}
            item.setItemMeta(meta);
        }
        return item;
    }
}

