package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
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
        // A stall trader/guard is handled through Citizens' own click event; the "nearest server NPC"
        // fallback below must not turn a click on it into a click on a buyer standing next to it.
        var market = plugin.getTradePointManager();
        if (market != null && market.isMarketEntity(event.getRightClicked())) {
            return;
        }
        Optional<NpcData> npcOpt = plugin.getNpcManager().getNpcFromEntity(event.getRightClicked());
        if (npcOpt.isEmpty()) {
            npcOpt = plugin.getNpcManager().getNpcNear(event.getRightClicked().getLocation(), 2.0);
        }

        if (npcOpt.isPresent()) {
            event.setCancelled(true);
            NpcData npc = npcOpt.get();
            if (npc.type().equalsIgnoreCase("wanderer")) {
                plugin.getWandererManager().handleWandererInteraction(player);
                return;
            }
            if (npc.type().equalsIgnoreCase("warmerchant") && !plugin.getWarMerchantManager().isEligible(player.getUniqueId())) {
                MessageUtils.sendMessage(player, plugin.getWarMerchantManager().randomDenyMessage());
                return;
            }
            switch (npc.type().toLowerCase()) {
                case "auctioneer" -> new AuctionGui(plugin, player).open();
                case "warmerchant" -> new WarMerchantGui(plugin, player).open();
                case "banker" -> new BankerGui(plugin, player).open();
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

            if (slot == dev.lovelace.loveshops.gui.WandererDealGui.SLOT_STANDARD_DEAL) {
                startWandererDeal(player, null);
                return;
            }

            if (slot == dev.lovelace.loveshops.gui.WandererDealGui.SLOT_CATEGORY_REQUEST) {
                dev.lovelace.loveshops.models.WandererRequestCategory[] categories =
                    dev.lovelace.loveshops.models.WandererRequestCategory.values();

                ItemStack clicked = event.getCurrentItem();
                int currentIndex = 0;
                if (clicked != null && clicked.hasItemMeta()) {
                    Integer ordinal = clicked.getItemMeta().getPersistentDataContainer().get(
                        new NamespacedKey(plugin, dev.lovelace.loveshops.gui.WandererDealGui.CATEGORY_INDEX_KEY), PersistentDataType.INTEGER);
                    if (ordinal != null && ordinal >= 0 && ordinal < categories.length) {
                        currentIndex = ordinal;
                    }
                }

                boolean isRightClick = event.getClick() == org.bukkit.event.inventory.ClickType.RIGHT
                    || event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;

                if (isRightClick) {
                    startWandererDeal(player, categories[currentIndex]);
                } else {
                    int nextIndex = (currentIndex + 1) % categories.length;
                    ItemStack updated = dev.lovelace.loveshops.gui.WandererDealGui.buildCategoryButton(plugin, categories[nextIndex]);
                    event.setCurrentItem(updated);
                }
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
        } else if (titleText.contains(BankerGui.TITLE)) {
            event.setCancelled(true);
            handleBankerClick(player, event);
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

    private void handleBankerClick(Player player, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (raw == BankerGui.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Клик по слоту «Как работает банкир» / «Курс и размен валют»
        if (raw == BankerGui.SLOT_INFO) {
            BankerGui.toggleInfoMode(player);
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            return;
        }

        // Клик по слоту депозита (слот 11)
        if (raw == BankerGui.SLOT_DEPOSIT) {
            // Если игрок нажал цифровую клавишу (1-9) над слотом депозита
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int button = event.getHotbarButton();
                if (button >= 0 && button < 9) {
                    ItemStack hotbarItem = player.getInventory().getItem(button);
                    if (hotbarItem != null && !hotbarItem.getType().isAir()) {
                        long added = BankerGui.depositStack(plugin, player, hotbarItem.clone());
                        if (added > 0) {
                            player.getInventory().setItem(button, null);
                            player.updateInventory();
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
                        } else {
                            MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
                        }
                    }
                }
                return;
            }

            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                if (event.getClick() == ClickType.RIGHT) {
                    // ПКМ курсором — положить ровно 1 монету
                    ItemStack single = cursor.clone();
                    single.setAmount(1);
                    long added = BankerGui.depositStack(plugin, player, single);
                    if (added > 0) {
                        if (cursor.getAmount() > 1) {
                            cursor.setAmount(cursor.getAmount() - 1);
                            player.setItemOnCursor(cursor);
                        } else {
                            player.setItemOnCursor(null);
                        }
                        player.updateInventory();
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
                    } else {
                        MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
                    }
                    return;
                }

                // ЛКМ (или другой клик) курсором — положить весь стак
                long added = BankerGui.depositStack(plugin, player, cursor.clone());
                if (added > 0) {
                    player.setItemOnCursor(null);
                    player.updateInventory();
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
                } else {
                    MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
                }
                return;
            }

            // Клик с пустым курсором по слоту депозита — забрать всё назад
            if (BankerGui.withdrawAll(plugin, player)) {
                player.updateInventory();
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            }
            return;
        }

        // Клик по слоту опций обмена
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
                player.updateInventory();
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
            }
            return;
        }

        // Клики в нижнем инвентаре (инвентарь игрока)
        if (raw >= topSize) {
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                ItemStack current = event.getCurrentItem();
                if (current == null || current.getType().isAir()) return;
                long added = BankerGui.depositStack(plugin, player, current.clone());
                if (added > 0) {
                    event.setCurrentItem(null);
                    player.updateInventory();
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> BankerGui.refresh(plugin, player));
                } else {
                    MessageUtils.sendMessage(player, "<red>Сюда можно класть только монеты LoveEconomy.</red>");
                }
                return;
            }

            // Разрешаем обычные клики в нижнем инвентаре (взять/положить на курсор)
            event.setCancelled(false);
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

    @EventHandler
    public void onWandererClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView().title() == null) return;
        String titleText = serializer.serialize(event.getView().title());
        if (titleText.contains(dev.lovelace.loveshops.gui.WandererShopGui.TITLE)
                || titleText.contains(dev.lovelace.loveshops.gui.WandererDealGui.TITLE)
                || titleText.contains(dev.lovelace.loveshops.gui.WandererWaitingGui.TITLE)) {
            plugin.getNpcDialogueManager().sayWandererClose(player);
        }
    }

    /** Shared by the standard deal button and the cycling category button's ПКМ confirm. */
    private void startWandererDeal(Player player, dev.lovelace.loveshops.models.WandererRequestCategory requestedCategory) {
        plugin.getWandererManager().startDeal(player, requestedCategory).thenAccept(success -> {
            if (success) {
                plugin.getWandererManager().getPlayerDeal(player.getUniqueId()).thenAccept(optDeal -> {
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        optDeal.ifPresent(deal -> new dev.lovelace.loveshops.gui.WandererWaitingGui(plugin, player, deal).open());
                    });
                });
            }
        });
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