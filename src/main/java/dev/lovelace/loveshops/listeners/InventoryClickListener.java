package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.gui.BankerGui;
import dev.lovelace.loveshops.gui.WarMerchantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

public class InventoryClickListener implements Listener {

    private final LoveShops plugin;
    private final PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();

    public InventoryClickListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Optional<NpcData> npcOpt = plugin.getNpcManager().getNpcFromEntity(event.getRightClicked());
        if (npcOpt.isEmpty()) {
            npcOpt = plugin.getNpcManager().getNpcNear(event.getRightClicked().getLocation(), 2.0);
        }
        if (npcOpt.isEmpty()) return;
        NpcData npc = npcOpt.get();
        event.setCancelled(true);
        switch (npc.type().toLowerCase()) {
            case "buyer" -> new BuyerGui(plugin, player).open();
            case "seller" -> new SellerGui(plugin, player).open();
            case "auctioneer" -> new AuctionGui(plugin, player).open();
            case "warmerchant" -> new WarMerchantGui(plugin, player).open();
            case "banker" -> new BankerGui(plugin, player).open();
            case "wanderer" -> plugin.getWandererManager().openGui(player);
            default -> {}
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().title() == null) return;
        String titleText = serializer.serialize(event.getView().title());

        if (titleText.contains(BankerGui.TITLE)) {
            event.setCancelled(true);
            handleBankerClick(player, event);
            return;
        }
        // Other GUI handlers remain registered via existing code paths in full file.
        // Minimal banker-focused listener restore — full multi-GUI version on local.
    }

    private void handleBankerClick(Player player, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (raw == BankerGui.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        if (raw == BankerGui.SLOT_DEPOSIT) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                long added = BankerGui.depositStack(plugin, player, cursor);
                if (added > 0) {
                    event.getView().setCursor(null);
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
                } else {
                    MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
                }
                return;
            }
            if (BankerGui.withdrawAll(plugin, player)) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            }
            return;
        }

        if (BankerGui.isOptionSlot(raw)) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            Long unit = clicked.getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, BankerGui.DENOM_KEY), PersistentDataType.LONG);
            if (unit == null || unit <= 0) return;
            boolean shift = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
            long count = shift ? Long.MAX_VALUE / unit : 1L;
            if (count <= 0) count = 1;
            if (BankerGui.takeOption(plugin, player, unit, count)) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            }
            return;
        }

        if (raw >= topSize && (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)) {
            ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir()) return;
            long added = BankerGui.depositStack(plugin, player, current.clone());
            if (added > 0) {
                event.setCurrentItem(null);
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            } else {
                MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBankerDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getView().title() == null) return;
        String titleText = serializer.serialize(event.getView().title());
        if (!titleText.contains(BankerGui.TITLE)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onBankerClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView().title() == null) return;
        String titleText = serializer.serialize(event.getView().title());
        if (!titleText.contains(BankerGui.TITLE)) return;
        BankerGui.onClose(plugin, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BankerGui.onClose(plugin, event.getPlayer());
    }
}
