package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.ConfirmMenuLayout;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Yes/No confirmation: 9 slots, {@code [С][С][С][✓][С][✗][С][С][С]} (owner decision 2026-11-05: the two buttons sit
 * one slot apart, everything else is glass). The summary of what is confirmed is in the tick's lore.
 */
public final class StallConfirmGui extends MarketGui {

    private static final int CONFIRM_SLOT = 3;
    private static final int CANCEL_SLOT = 5;

    private final UUID pointId;
    private final String title;
    private final List<String> summary;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private boolean resolved;

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
        // The server's 27-slot confirm menu when its background and invisible item exist, otherwise 9 slots.
        if (ConfirmMenuLayout.available(viewer)) {
            show(Bukkit.createInventory(this, ConfirmMenuLayout.SIZE, ConfirmMenuLayout.title(viewer)));
        } else {
            show(Bukkit.createInventory(this, 9, MessageUtils.parse(viewer, title)));
        }
    }

    @Override
    public UUID pointId() {
        return pointId;
    }

    @Override
    public void render() {
        inventory.clear();
        if (inventory.getSize() == ConfirmMenuLayout.SIZE) {
            ItemStack yes = ConfirmMenuLayout.button(viewer, t("gui-confirm-ok"), confirmLore());
            ItemStack no = ConfirmMenuLayout.button(viewer, t("gui-confirm-cancel"), lines("gui-confirm-cancel-lore"));
            if (yes != null && no != null) {
                ConfirmMenuLayout.fill(inventory, yes, no);
                return;
            }
        }
        ItemStack glass = GuiUtils.createFiller();
        for (int slot : new int[]{0, 1, 2, 4, 6, 7, 8}) inventory.setItem(slot, glass);
        inventory.setItem(CONFIRM_SLOT, head(HeadTextures.CONFIRM_YES, t("gui-confirm-ok"), confirmLore()));
        inventory.setItem(CANCEL_SLOT, head(HeadTextures.CONFIRM_NO, t("gui-confirm-cancel"), lines("gui-confirm-cancel-lore")));
    }

    private List<String> confirmLore() {
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add(t("gui-confirm-subtitle"));
        lore.add("");
        lore.addAll(summary);
        lore.addAll(lines("gui-confirm-ok-lore"));
        return lore;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (resolved || viewer.getOpenInventory().getTopInventory() != inventory) return;
        int slot = event.getRawSlot();
        boolean large = inventory.getSize() == ConfirmMenuLayout.SIZE;
        boolean yes = large ? ConfirmMenuLayout.isConfirm(slot) : slot == CONFIRM_SLOT;
        boolean no = large ? ConfirmMenuLayout.isCancel(slot) : slot == CANCEL_SLOT;
        if (yes || no) resolved = true;
        if (yes) {
            viewer.closeInventory();
            onConfirm.run();
        } else if (no) {
            viewer.closeInventory();
            if (onCancel != null) onCancel.run();
        }
    }
}
