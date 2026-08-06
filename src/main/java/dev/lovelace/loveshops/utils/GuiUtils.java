package dev.lovelace.loveshops.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

public class GuiUtils {

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

    public static ItemStack createCustomHead(String base64, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse(name));
            if (lore != null) {
                meta.lore(lore.stream().map(MessageUtils::parse).toList());
            }
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
            profile.setProperty(new ProfileProperty("textures", base64));
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        }
        return item;
    }
}
