package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.CommissionManager;
import dev.lovelace.loveshops.models.commission.CommissionLot;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Окно подтверждения (9 слотов по стандарту Исключение 1):
 * [0: Стекло] [1: Подтвердить ✓] [2,3: Стекло] [4: Предмет лота] [5,6: Стекло] [7: Отмена ✗] [8: Стекло]
 */
public class CommissionConfirmGui implements InventoryHolder {

    public enum ConfirmAction {
        BUY,
        CANCEL_OWN
    }

    public static final String TITLE = "Подтверждение";
    private final LoveShops plugin;
    private final Player player;
    private final CommissionLot lot;
    private final ConfirmAction action;
    private Inventory inventory;

    public CommissionConfirmGui(LoveShops plugin, Player player, CommissionLot lot, ConfirmAction action) {
        this.plugin = plugin;
        this.player = player;
        this.lot = lot;
        this.action = action;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private dev.lovelace.loveshops.managers.LangManager lang() {
        return plugin.getLangManager();
    }

    public void open() {
        String titleStr = TITLE + ": " + (action == ConfirmAction.BUY ? "Покупка лота" : "Снятие лота");
        this.inventory = Bukkit.createInventory(this, 9, MessageUtils.parse("<dark_gray>" + titleStr + "</dark_gray>"));
        render();
        player.openInventory(inventory);
    }

    static final int SLOT_CONFIRM = 3;
    static final int SLOT_CANCEL = 5;

    /** Name and amount of the lot, as a lore line under the first line of the confirm button. */
    private List<String> withLotLine(List<String> lore) {
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lot.item().displayName());
        List<String> out = new ArrayList<>(lore);
        out.add(Math.min(1, out.size()), "<gray>" + name + " <white>x" + lot.item().getAmount() + "</white></gray>");
        return out;
    }

    public void render() {
        inventory.clear();
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);

        // Слот 1: Подтвердить
        // The price goes into the lore, never into the name (gui-gen-6)
        boolean buy = action == ConfirmAction.BUY;
        // Owner decision 2026-11-05: the two buttons sit in slots 3 and 5, everything else is glass; the lot
        // preview that used to fill slot 4 is now part of the confirm button's lore.
        inventory.setItem(SLOT_CONFIRM, GuiUtils.createCustomHead(
                HeadTextures.CONFIRM_YES,
                lang().text(buy ? "gui.confirm.buy" : "gui.confirm.cancel-own"),
                withLotLine(lang().lines(buy ? "gui.confirm.buy-lore" : "gui.confirm.cancel-own-lore", "price", CoinFormat.formatGlyphs(eco, lot.price())))
        ));

        // Слот 7: Отмена
        inventory.setItem(SLOT_CANCEL, GuiUtils.createCustomHead(
                HeadTextures.CONFIRM_NO,
                lang().text("gui.confirm.cancel"),
                lang().lines("gui.confirm.cancel-lore")
        ));
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (openInv.getHolder() instanceof CommissionConfirmGui gui) {
            if (rawSlot == SLOT_CANCEL) {
                // Отмена
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> new CommissionAgentGui(plugin, player, plugin.getCommissionManager(), 0).open());
                return;
            }

            if (rawSlot == SLOT_CONFIRM) {
                // Подтверждение
                player.closeInventory();
                CommissionManager manager = plugin.getCommissionManager();
                if (manager == null) return;

                if (gui.action == ConfirmAction.BUY) {
                    CommissionManager.LotResult result = manager.buyLot(player, gui.lot.id());
                    switch (result) {
                        case NO_MONEY -> MessageUtils.event(player, "<red>У вас недостаточно монет для покупки!</red>");
                        case NO_SPACE -> MessageUtils.event(player, "<red>В вашем инвентаре недостаточно свободного места!</red>");
                        case NOT_FOUND -> MessageUtils.event(player, "<red>Этот лот уже был куплен или снят с продажи.</red>");
                        case CANT_BUY_SELF -> MessageUtils.event(player, "<red>Вы не можете купить свой собственный лот!</red>");
                        default -> {}
                    }
                } else if (gui.action == ConfirmAction.CANCEL_OWN) {
                    CommissionManager.LotResult result = manager.cancelLot(player, gui.lot.id());
                    if (result == CommissionManager.LotResult.NOT_FOUND) {
                        MessageUtils.event(player, "<red>Лот не найден или уже был продан!</red>");
                    }
                }

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        new CommissionAgentGui(plugin, player, manager, 0).open();
                    }
                }, 2L);
            }
        }
    }
}
