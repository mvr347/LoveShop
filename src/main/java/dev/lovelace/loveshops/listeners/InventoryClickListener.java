package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.BankerGui;
import dev.lovelace.loveshops.gui.WarMerchantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
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
        // Event NPCs are plugin-created Citizens NPCs: CitizensListener handles their clicks, and an
        // empty spawn point must never open a menu for whatever stands next to it.
        npcOpt = npcOpt.filter(n -> !dev.lovelace.loveshops.managers.NpcManager.isEphemeralType(n.type()));

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
                case "warmerchant" -> new WarMerchantGui(plugin, player).open();
                case "banker" -> new BankerGui(plugin, player).open();
                case "caravaner" -> {
                    if (plugin.getDailyCaravanManager() != null) {
                        plugin.getDailyCaravanManager().openGui(player);
                    }
                }
                case "commissioner" -> {
                    if (plugin.getCommissionManager() != null) {
                        plugin.getCommissionManager().openGui(player);
                    }
                }
                case "lostcaravan" -> {
                    if (plugin.getLostCaravanManager() != null) {
                        plugin.getLostCaravanManager().handleNpcClick(player);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().title() == null) return;
        // Every click on the server passes here: other plugins' and vanilla inventories have a holder of
        // their own and are none of ours, so the title is only serialized for ours and holder-less menus.
        var topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder != null && !topHolder.getClass().getName().startsWith("dev.lovelace.loveshops.")) return;

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
                        dev.lovelace.loveshops.utils.Keys.of(plugin, dev.lovelace.loveshops.gui.WandererDealGui.CATEGORY_INDEX_KEY), PersistentDataType.INTEGER);
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
                        .get(dev.lovelace.loveshops.utils.Keys.of(plugin, "war_merchant_index"), PersistentDataType.INTEGER);
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
        } else {
            var topInv = event.getView().getTopInventory();
            var holder = topInv.getHolder();
            int raw = event.getRawSlot();
            int topSize = topInv.getSize();

            if (holder instanceof dev.lovelace.loveshops.gui.DailyCaravanGui || titleText.contains(dev.lovelace.loveshops.gui.DailyCaravanGui.TITLE)) {
                event.setCancelled(raw < topSize || !isPlainBottomClick(event));
                if (raw >= 0 && raw < topSize) {
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && !cursor.getType().isAir() && dev.lovelace.loveshops.gui.DailyCaravanGui.isCrateSlot(raw)) {
                        dev.lovelace.loveshops.gui.DailyCaravanGui.handleCursorSubmit(plugin, player, raw, cursor);
                        return;
                    }
                    dev.lovelace.loveshops.gui.DailyCaravanGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                } else if (event.getClick().isShiftClick()) {
                    // Shift-click an item of the own inventory: hand it to the first crate that accepts it
                    ItemStack moved = event.getCurrentItem();
                    if (moved != null && !moved.getType().isAir()) {
                        event.setCurrentItem(null);
                        dev.lovelace.loveshops.gui.DailyCaravanGui.handleShiftSubmit(plugin, player, moved.clone());
                    }
                }
            } else if (holder instanceof dev.lovelace.loveshops.gui.CommissionAgentGui || titleText.contains(dev.lovelace.loveshops.gui.CommissionAgentGui.TITLE)) {
                event.setCancelled(raw < topSize || !isPlainBottomClick(event));
                if (raw >= 0 && raw < topSize) {
                    // Drag / cursor onto «Выставить»: предмет с курсора уходит в меню цены
                    if (raw == dev.lovelace.loveshops.gui.CommissionAgentGui.SLOT_CREATE_LOT) {
                        ItemStack cursor = event.getCursor();
                        if (cursor != null && !cursor.getType().isAir()) {
                            ItemStack toList = cursor.clone();
                            event.getView().setCursor(null);
                            var mgr = plugin.getCommissionManager();
                            if (mgr != null) {
                                dev.lovelace.loveshops.gui.CommissionAgentGui.startListing(plugin, player, mgr, toList, false);
                            }
                            return;
                        }
                    }
                    dev.lovelace.loveshops.gui.CommissionAgentGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                } else if (event.getClick().isShiftClick()) {
                    // Shift-click an item of the own inventory: straight into the price menu
                    ItemStack moved = event.getCurrentItem();
                    var mgr = plugin.getCommissionManager();
                    if (moved != null && !moved.getType().isAir() && mgr != null && mgr.isEnabled()) {
                        ItemStack toList = moved.clone();
                        event.setCurrentItem(null);
                        dev.lovelace.loveshops.gui.CommissionAgentGui.startListing(plugin, player, mgr, toList, false);
                    }
                }
            } else if (holder instanceof dev.lovelace.loveshops.gui.CommissionConfirmGui || titleText.contains(dev.lovelace.loveshops.gui.CommissionConfirmGui.TITLE)) {
                event.setCancelled(true);
                if (raw >= 0 && raw < topSize) {
                    dev.lovelace.loveshops.gui.CommissionConfirmGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                }
            } else if (holder instanceof dev.lovelace.loveshops.gui.LostCaravanEntryGui || titleText.contains(dev.lovelace.loveshops.gui.LostCaravanEntryGui.TITLE)) {
                event.setCancelled(true);
                if (raw >= 0 && raw < topSize) {
                    dev.lovelace.loveshops.gui.LostCaravanEntryGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                }
            } else if (holder instanceof dev.lovelace.loveshops.gui.LostCaravanAuctionGui || titleText.contains(dev.lovelace.loveshops.gui.LostCaravanAuctionGui.TITLE)) {
                event.setCancelled(true);
                if (raw >= 0 && raw < topSize) {
                    dev.lovelace.loveshops.gui.LostCaravanAuctionGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                }
            } else if (holder instanceof dev.lovelace.loveshops.gui.LostCaravanInstantGui || titleText.contains(dev.lovelace.loveshops.gui.LostCaravanInstantGui.TITLE)) {
                event.setCancelled(true);
                if (raw >= 0 && raw < topSize) {
                    dev.lovelace.loveshops.gui.LostCaravanInstantGui.handleClick(plugin, player, raw, event.getClick(), topInv);
                }
            }
        }
    }

    /**
     * A click in the player's own inventory under an item menu. Picking an item up and putting it down there is
     * allowed - that is how it gets onto the cursor to be dropped on the menu (drag and drop); blanket-cancelling
     * every click made the drop impossible. Anything that moves items into or out of the menu stays cancelled
     * (Shift-click is handled separately, double-click would gather the menu's own display items).
     */
    private static boolean isPlainBottomClick(InventoryClickEvent event) {
        if (event.getRawSlot() < 0) return false;
        return switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                 SWAP_WITH_CURSOR, HOTBAR_SWAP, NOTHING -> true;
            default -> false;
        };
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
                    .get(dev.lovelace.loveshops.utils.Keys.of(plugin, BankerGui.DENOM_KEY), PersistentDataType.LONG);
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
    public void onCustomGuiDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        var top = event.getView().getTopInventory();
        int topSize = top.getSize();
        boolean targetsTop = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (!targetsTop) return;

        var holder = top.getHolder();
        // A drag over several slots is the usual way to drop a stack on a menu: the first menu slot it touched
        // that can take the item decides. The event itself is cancelled; the real cursor (not the event's copy,
        // whose amount a partial submit would leave untouched) is used on the next tick, after the client was
        // resynced, so what is taken off it is really taken.
        if (holder instanceof dev.lovelace.loveshops.gui.DailyCaravanGui) {
            event.setCancelled(true);
            Player p = (Player) event.getWhoClicked();
            event.getRawSlots().stream().filter(sl -> sl < topSize)
                    .filter(dev.lovelace.loveshops.gui.DailyCaravanGui::isCrateSlot).sorted().findFirst().ifPresent(slot ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ItemStack real = p.getItemOnCursor();
                                if (real != null && !real.getType().isAir()) {
                                    dev.lovelace.loveshops.gui.DailyCaravanGui.handleCursorSubmit(plugin, p, slot, real);
                                }
                            }));
            return;
        }
        if (holder instanceof dev.lovelace.loveshops.gui.CommissionAgentGui) {
            event.setCancelled(true);
            Player p = (Player) event.getWhoClicked();
            if (event.getRawSlots().contains(dev.lovelace.loveshops.gui.CommissionAgentGui.SLOT_CREATE_LOT)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ItemStack real = p.getItemOnCursor();
                    var mgr = plugin.getCommissionManager();
                    if (real != null && !real.getType().isAir() && mgr != null) {
                        ItemStack toList = real.clone();
                        p.setItemOnCursor(null);
                        dev.lovelace.loveshops.gui.CommissionAgentGui.startListing(plugin, p, mgr, toList, false);
                    }
                });
            }
            return;
        }
        if (holder instanceof dev.lovelace.loveshops.gui.CommissionConfirmGui
                || holder instanceof dev.lovelace.loveshops.gui.LostCaravanEntryGui
                || holder instanceof dev.lovelace.loveshops.gui.LostCaravanAuctionGui
                || holder instanceof dev.lovelace.loveshops.gui.LostCaravanInstantGui) {
            event.setCancelled(true);
            return;
        }

        if (event.getView().title() != null) {
            String titleText = serializer.serialize(event.getView().title());
            if (titleText.contains(BankerGui.TITLE)
                    || titleText.contains(WarMerchantGui.TITLE)
                    || titleText.contains(dev.lovelace.loveshops.gui.WandererShopGui.TITLE)
                    || titleText.contains(dev.lovelace.loveshops.gui.WandererDealGui.TITLE)
                    || titleText.contains(dev.lovelace.loveshops.gui.WandererWaitingGui.TITLE)) {
                event.setCancelled(true);
            }
        }
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

    @EventHandler
    public void onCaravanClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView().title() == null) return;
        String titleText = serializer.serialize(event.getView().title());
        boolean daily = titleText.contains(dev.lovelace.loveshops.gui.DailyCaravanGui.TITLE);
        boolean lost = titleText.contains(dev.lovelace.loveshops.gui.LostCaravanEntryGui.TITLE)
                || titleText.contains(dev.lovelace.loveshops.gui.LostCaravanAuctionGui.TITLE)
                || titleText.contains(dev.lovelace.loveshops.gui.LostCaravanInstantGui.TITLE);
        if (!daily && !lost) return;
        // The close event also fires when one caravan menu replaces another: say goodbye only if nothing follows.
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            String next = serializer.serialize(player.getOpenInventory().title());
            boolean stillInCaravan = next.contains(dev.lovelace.loveshops.gui.DailyCaravanGui.TITLE)
                    || next.contains(dev.lovelace.loveshops.gui.LostCaravanEntryGui.TITLE)
                    || next.contains(dev.lovelace.loveshops.gui.LostCaravanAuctionGui.TITLE)
                    || next.contains(dev.lovelace.loveshops.gui.LostCaravanInstantGui.TITLE);
            if (stillInCaravan) return;
            if (daily) plugin.getNpcDialogueManager().sayCaravanerClose(player);
            else plugin.getNpcDialogueManager().sayLostCaravanClose(player);
        });
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
}