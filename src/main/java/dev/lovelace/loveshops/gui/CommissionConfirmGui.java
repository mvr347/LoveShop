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
 * [0: Предмет лота] [1: Подтвердить ✓] [2: Стекло] [3: Отмена ✗] [4-8: Стекло]
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

    public void open() {
        String titleStr = TITLE + ": " + (action == ConfirmAction.BUY ? "Покупка лота" : "Снятие лота");
        this.inventory = Bukkit.createInventory(this, 9, MessageUtils.parse("<dark_gray>" + titleStr + "</dark_gray>"));
        render();
        player.openInventory(inventory);
    }

    public void render() {
        inventory.clear();
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);

        // Слот 0: Превью предмета
        ItemStack preview = lot.item().clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Цена: </gray>" + CoinFormat.formatGlyphs(eco, lot.price())));
            if (action == ConfirmAction.CANCEL_OWN) {
                lore.add(MessageUtils.parse("<yellow>Вы собираетесь вернуть этот предмет в инвентарь.</yellow>"));
            } else {
                lore.add(MessageUtils.parse("<gray>Продавец: <gold>" + Bukkit.getOfflinePlayer(lot.sellerUuid()).getName() + "</gold></gray>"));
            }
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        inventory.setItem(0, preview);

        // Слот 1: Подтвердить
        String confirmTitle = action == ConfirmAction.BUY ? "<green>Купить за " + CoinFormat.formatGlyphs(eco, lot.price()) + "</green>"
                : "<green>Забрать предмет</green>";
        inventory.setItem(1, GuiUtils.createCustomHead(
                HeadTextures.HEAD_CONFIRM,
                confirmTitle,
                List.of("", "<gray>Нажмите, чтобы подтвердить действие</gray>")
        ));

        // Слот 3: Отмена
        inventory.setItem(3, GuiUtils.createCustomHead(
                HeadTextures.HEAD_DELETE_NO,
                "<red>Отмена</red>",
                List.of("", "<gray>Вернуться назад</gray>")
        ));
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (openInv.getHolder() instanceof CommissionConfirmGui gui) {
            if (rawSlot == 3) {
                // Отмена
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> new CommissionAgentGui(plugin, player, plugin.getCommissionManager(), 0).open());
                return;
            }

            if (rawSlot == 1) {
                // Подтверждение
                player.closeInventory();
                CommissionManager manager = plugin.getCommissionManager();
                if (manager == null) return;

                if (gui.action == ConfirmAction.BUY) {
                    CommissionManager.LotResult result = manager.buyLot(player, gui.lot.id());
                    switch (result) {
                        case NO_MONEY -> MessageUtils.sendMessage(player, "<red>У вас недостаточно монет для покупки!</red>");
                        case NO_SPACE -> MessageUtils.sendMessage(player, "<red>В вашем инвентаре недостаточно свободного места!</red>");
                        case NOT_FOUND -> MessageUtils.sendMessage(player, "<red>Этот лот уже был куплен или снят с продажи.</red>");
                        case CANT_BUY_SELF -> MessageUtils.sendMessage(player, "<red>Вы не можете купить свой собственный лот!</red>");
                        default -> {}
                    }
                } else if (gui.action == ConfirmAction.CANCEL_OWN) {
                    CommissionManager.LotResult result = manager.cancelLot(player, gui.lot.id());
                    if (result == CommissionManager.LotResult.NOT_FOUND) {
                        MessageUtils.sendMessage(player, "<red>Лот не найден или уже был продан!</red>");
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
