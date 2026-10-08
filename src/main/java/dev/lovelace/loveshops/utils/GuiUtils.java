package dev.lovelace.loveshops.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
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

    public static ItemStack createFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    public static ItemStack createPlayerProfileHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            skullMeta.displayName(MessageUtils.parse("<gold>Профиль: <white>" + player.getName() + "</white></gold>"));
            skullMeta.lore(List.of(
                Component.empty(),
                MessageUtils.parse("<gray>Вы вошли как: <white>" + player.getName() + "</white></gray>")
            ));
            head.setItemMeta(skullMeta);
        }
        return head;
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
        return createCustomHead(dev.lovelace.loveshops.textures.HeadTextures.BANKER_DEPOSIT_EMPTY,
                "<gray>Пока ничего нет</gray>",
                hint == null || hint.isBlank() ? List.of() : List.of("", hint));
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
            meta.displayName(MessageUtils.parse(name));
            if (lore != null) {
                meta.lore(lore.stream().map(MessageUtils::parse).toList());
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
            if (name != null) meta.displayName(name);
            if (lore != null) meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createHead(String base64, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(name);
            if (lore != null) meta.lore(lore);
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

