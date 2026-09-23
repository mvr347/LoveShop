package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.gui.WarMerchantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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

        if (npcOpt.isPresent()) {
            event.setCancelled(true);
            NpcData npc = npcOpt.get();
            if (npc.type().equalsIgnoreCase("seller") && !plugin.getSellerManager().isSellerActive()) {
                MessageUtils.sendMessage(player, "<red>Торговец-барахолка открыт только по воскресеньям с 10:00 до 18:00!</red>");
                return;
            }
            if (npc.type().equalsIgnoreCase("auctioneer") && !plugin.getSellerManager().isSellerActive()) {
                MessageUtils.sendMessage(player, "<red>Аукционист появляется вместе с барахолкой, по воскресеньям с 10:00 до 18:00!</red>");
                return;
            }
            if (npc.type().equalsIgnoreCase("wanderer")) {
                plugin.getWandererManager().handleWandererInteraction(player);
                return;
            }
            if (npc.type().equalsIgnoreCase("warmerchant") && !plugin.getWarMerchantManager().isEligible(player.getUniqueId())) {
                MessageUtils.sendMessage(player, plugin.getWarMerchantManager().randomDenyMessage());
                return;
            }
            switch (npc.type().toLowerCase()) {
                case "buyer" -> new BuyerGui(plugin, player).open();
                case "seller" -> new SellerGui(plugin, player).open();
                case "auctioneer" -> new AuctionGui(plugin, player).open();
                case "warmerchant" -> new WarMerchantGui(plugin, player).open();
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().title() == null) return;

        String titleText = serializer.serialize(event.getView().title());

        if (titleText.contains(dev.lovelace.loveshops.gui.WandererDealGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
                return;
            }

            if (slot == 13) {
                plugin.getWandererManager().startDeal(player).thenAccept(success -> {
                    if (success) {
                        plugin.getWandererManager().getPlayerDeal(player.getUniqueId()).thenAccept(optDeal -> {
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                optDeal.ifPresent(deal -> new dev.lovelace.loveshops.gui.WandererWaitingGui(plugin, player, deal).open());
                            });
                        });
                    }
                });
            }
        } else if (titleText.contains(dev.lovelace.loveshops.gui.WandererWaitingGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
            }
        } else if (titleText.contains(dev.lovelace.loveshops.gui.WandererShopGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;

            if (slot == 53) {
                player.closeInventory();
                return;
            }

            if (slot == 51) {
                plugin.getWandererManager().resetDeal(player.getUniqueId()).thenRun(() -> {
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        MessageUtils.sendMessage(player, "<green>Заказ завершён! Вы можете заключить новый договор.</green>");
                        new dev.lovelace.loveshops.gui.WandererDealGui(plugin, player).open();
                    });
                });
                return;
            }

            boolean isContentSlot = false;
            for (int s : dev.lovelace.loveshops.gui.WandererShopGui.CONTENT_SLOTS) {
                if (s == slot) {
                    isContentSlot = true;
                    break;
                }
            }

            if (isContentSlot) {
                plugin.getWandererManager().getPlayerDeal(player.getUniqueId()).thenAccept(optDeal -> {
                    if (optDeal.isEmpty()) return;
                    var deal = optDeal.get();
                    int slotIndex = -1;
                    for (int i = 0; i < dev.lovelace.loveshops.gui.WandererShopGui.CONTENT_SLOTS.length; i++) {
                        if (dev.lovelace.loveshops.gui.WandererShopGui.CONTENT_SLOTS[i] == slot) {
                            slotIndex = i;
                            break;
                        }
                    }

                    if (slotIndex >= 0 && slotIndex < deal.items().size()) {
                        var item = deal.items().get(slotIndex);
                        if (!item.bought()) {
                            plugin.getWandererManager().buyDealItem(player, item.id()).thenAccept(bought -> {
                                if (bought) {
                                    plugin.getWandererManager().getPlayerDeal(player.getUniqueId()).thenAccept(updated -> {
                                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                            updated.ifPresent(d -> new dev.lovelace.loveshops.gui.WandererShopGui(plugin, player, d).open());
                                        });
                                    });
                                }
                            });
                        }
                    }
                });
            }
        } else if (titleText.contains(BuyerGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
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

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().lore() != null) {
                int itemId = extractIdFromLore(clicked);
                if (itemId > 0) {
                    plugin.getSellerManager().buyItem(player, itemId);
                }
            }
        } else if (titleText.contains(WarMerchantGui.TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            if (slot == 26) {
                player.closeInventory();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta()) {
                Integer index = clicked.getItemMeta().getPersistentDataContainer()
                        .get(new NamespacedKey(plugin, "war_merchant_index"), PersistentDataType.INTEGER);
                if (index != null) {
                    boolean success = plugin.getWarMerchantManager().purchase(player, index);
                    if (success) {
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> new WarMerchantGui(plugin, player).open());
                    }
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

            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().lore() != null) {
                int auctionId = extractIdFromLore(clicked);
                if (auctionId > 0) {
                    boolean buyout = event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                        || event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;
                    if (buyout) {
                        plugin.getAuctionManager().buyoutAuction(player, auctionId);
                    } else {
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
