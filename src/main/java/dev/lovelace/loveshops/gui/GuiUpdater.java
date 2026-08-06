package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class GuiUpdater {

    private GuiUpdater() {}

    public static void broadcastSellerGuiUpdate(LoveShops plugin, int soldItemId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                InventoryView view = p.getOpenInventory();
                if (view.title().toString().contains(SellerGui.TITLE)) {
                    // Refresh open seller GUI
                    new SellerGui(plugin, p).open();
                }
            }
        });
    }

    public static ItemStack createSoldItemSkull() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<red>✗ Товар продан</red>"));
            meta.lore(List.of(
                Component.empty(),
                MessageUtils.parse("<gray>Этот предмет только что был куплен другом игроком.</gray>")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }
}
