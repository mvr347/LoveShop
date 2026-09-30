package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Yes/No confirmation (gui_gen exception 1): 9 slots, {@code [С][✓][С][П][П][П][С][✗][С]}.
 * The summary of what is being confirmed is in the tick's lore; the middle stays empty as the standard says.
 */
public final class StallConfirmGui extends MarketGui {

    private static final int CONFIRM_SLOT = 1;
    private static final int CANCEL_SLOT = 7;

    private final UUID pointId;
    private final String title;
    private final List<String> summary;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public StallConfirmGui(LoveShops plugin, Player viewer, UUID pointId, String title, List<String> summary,
                           Runnable onConfirm, Runnable onCancel) {
        super(plugin, viewer);
        this.pointId = pointId;
        this.title = title;
        this.summary = summary;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public void open() {
        show(Bukkit.createInventory(this, 9, MessageUtils.parse(viewer, title)));
    }

    @Override
    public UUID pointId() {
        return pointId;
    }

    @Override
    public void render() {
        inventory.clear();
        ItemStack glass = GuiUtils.createFiller();
        for (int slot : new int[]{0, 2, 6, 8}) inventory.setItem(slot, glass);
        inventory.setItem(CONFIRM_SLOT, head(HeadTextures.MARKET_OPEN, "<green>Подтвердить</green>", confirmLore()));
        inventory.setItem(CANCEL_SLOT, head(HeadTextures.MARKET_CLOSED, "<red>Отмена</red>",
                List.of("", "<gray>Ничего не изменится.</gray>", "<red>ЛКМ </red><gray>— отменить</gray>")));
    }

    private List<String> confirmLore() {
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add("");
        lore.addAll(summary);
        lore.add("");
        lore.add("<green>ЛКМ </green><gray>— подтвердить</gray>");
        return lore;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == CONFIRM_SLOT) {
            viewer.closeInventory();
            onConfirm.run();
        } else if (slot == CANCEL_SLOT) {
            viewer.closeInventory();
            if (onCancel != null) onCancel.run();
        }
    }
}
