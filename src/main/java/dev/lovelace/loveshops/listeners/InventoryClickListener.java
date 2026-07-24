package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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

        if (npcOpt.isPresent()) {
            event.setCancelled(true);
            NpcData npc = npcOpt.get();
            if (npc.type().equalsIgnoreCase("seller") && !plugin.getSellerManager().isSellerActive()) {
                MessageUtils.sendMessage(player, "<red>Торговец-барахолка открыт только по воскресеньям с 10:00 до 18:00!</red>");
                return;
            }
            switch (npc.type().toLowerCase()) {
                case "buyer" -> new BuyerGui(plugin, player).open();
                case "seller" -> new SellerGui(plugin, player).open();
                case "auctioneer" -> new AuctionGui(plugin, player).open();
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().title() == null) return;

        String titleText = serializer.serialize(event.getView().title());

        if (titleText.contains(BuyerGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
                return;
            }

            // Tab navigation (Slots 2-4)
            if (slot == 3) {
                new SellerGui(plugin, player).open();
                return;
            } else if (slot == 4) {
                new AuctionGui(plugin, player).open();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir() && !clicked.getType().name().endsWith("GLASS_PANE") && slot >= 10 && slot <= 16) {
                ItemStack cleanItem = clicked.clone();
                ItemMeta meta = cleanItem.getItemMeta();
                if (meta != null && meta.lore() != null) {
                    java.util.List<net.kyori.adventure.text.Component> currentLore = new java.util.ArrayList<>(meta.lore());
                    if (currentLore.size() >= 2) {
                        currentLore.subList(currentLore.size() - 2, currentLore.size()).clear();
                    }
                    meta.lore(currentLore.isEmpty() ? null : currentLore);
                    cleanItem.setItemMeta(meta);
                }

                plugin.getBuyerManager().processSale(player, cleanItem).thenAccept(success -> {
                    if (success) {
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> new BuyerGui(plugin, player).open());
                    }
                });
            }
        } else if (titleText.contains(SellerGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;

            if (slot == 53) {
                player.closeInventory();
                return;
            }

            // Tab navigation (Slots 2-4)
            if (slot == 2) {
                new BuyerGui(plugin, player).open();
                return;
            } else if (slot == 4) {
                new AuctionGui(plugin, player).open();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().lore() != null) {
                int itemId = extractIdFromLore(clicked);
                if (itemId > 0) {
                    plugin.getSellerManager().buyItem(player, itemId);
                }
            }
        } else if (titleText.contains(AuctionGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
                return;
            }

            // Tab navigation (Slots 2-4)
            if (slot == 2) {
                new BuyerGui(plugin, player).open();
                return;
            } else if (slot == 3) {
                new SellerGui(plugin, player).open();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().lore() != null) {
                int auctionId = extractIdFromLore(clicked);
                if (auctionId > 0) {
                    plugin.getAuctionManager().getActiveAuctions().thenAccept(auctions -> {
                        auctions.stream().filter(a -> a.id() == auctionId).findFirst().ifPresent(auc -> {
                            int minBid = plugin.getAuctionManager().getMinimumNextBid(auc);
                            plugin.getAuctionManager().placeBid(player, auctionId, minBid);
                        });
                    });
                }
            }
        }
    }

    private int extractIdFromLore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) return -1;
        for (var lineComponent : item.getItemMeta().lore()) {
            String text = serializer.serialize(lineComponent);
            if (text.contains("ID Лота:")) {
                String idStr = text.substring(text.indexOf("#") + 1).trim();
                try {
                    return Integer.parseInt(idStr);
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }
}
