package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.LostCaravanManager;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.LoreBlocks;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI Регистрации в Потерянном Караване (27 слотов):
 * - Header (0-8): правила (0), стекло (1-8)
 * - Рабочая зона (9-17): без стекла! Слот 13 — «Внести залог»
 * - Footer (18-26): стекло, слот 26 — «Закрыть»
 */
public class LostCaravanEntryGui implements InventoryHolder {

    public static final String TITLE = "Регистрация: Караван";
    public static final int SLOT_REGISTER = 13;
    public static final int SLOT_CLOSE = 26;

    private final LoveShops plugin;
    private final Player player;
    private final LostCaravanManager manager;
    private Inventory inventory;

    public LostCaravanEntryGui(LoveShops plugin, Player player, LostCaravanManager manager) {
        this.plugin = plugin;
        this.player = player;
        this.manager = manager;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private dev.lovelace.loveshops.managers.LangManager lang() {
        return plugin.getLangManager();
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, Component.text(TITLE).color(NamedTextColor.GOLD));
        render();
        player.openInventory(inventory);
    }

    private String remainingLabel() {
        var session = manager.getCurrentSession();
        if (session == null || !"ANNOUNCED".equalsIgnoreCase(session.status())) {
            return lang().text("gui.lost.entry-closed");
        }
        long left = Math.max(0, session.openedAt() - System.currentTimeMillis() / 1000);
        long m = left / 60;
        long s = left % 60;
        return "<white>" + m + ":" + (s < 10 ? "0" : "") + s + "</white>";
    }

    public void render() {
        ItemStack filler = GuiUtils.createFiller();

        for (int i = 0; i <= 8; i++) {
            GuiUtils.putIfChanged(inventory, i, filler);
        }

        int fee = plugin.getLostCaravanManager().entryFee();
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        int refund = plugin.getConfig().getInt("caravan.lost.entry-fee.refund-percent-if-no-win", 50);
        String feeText = CoinFormat.formatGlyphs(eco, fee);
        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.CARAVAN_LOST_INFO,
                lang().text("gui.lost.entry-info"),
                lang().lines("gui.lost.entry-info-lore", "fee", feeText, "refund", String.valueOf(refund),
                        "min", String.valueOf(plugin.getConfig().getInt("caravan.lost.min-players-for-auction", 6)),
                        "secret", String.valueOf(plugin.getConfig().getInt("caravan.lost.secret-crate-chance-players", 12)),
                        "remaining", remainingLabel())
        );
        GuiUtils.putIfChanged(inventory, 0, infoItem);

        for (int i = 9; i <= 17; i++) {
            GuiUtils.putIfChanged(inventory, i, null);
        }

        boolean already = manager.getCurrentSession() != null
                && manager.isParticipant(manager.getCurrentSession().id(), player.getUniqueId());

        ItemStack registerBtn;
        if (already) {
            registerBtn = GuiUtils.createCustomHead(
                    HeadTextures.HEAD_CONFIRM,
                    lang().text("gui.lost.entry-done"),
                    lang().lines("gui.lost.entry-done-lore", "remaining", remainingLabel())
            );
        } else if (!manager.isRegistrationPhase()) {
            // Display only: the click handler already refuses a closed registration
            List<String> lore = new ArrayList<>();
            lore.add(lang().text("gui.lost.entry-subtitle"));
            lore.add("");
            lore.addAll(LoreBlocks.blocked(lang().style(), "unavailable", lang().text("gui.lost.entry-register-off-reason"),
                    LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.lost.entry-register-click"))));
            registerBtn = GuiUtils.createCustomHead(HeadTextures.BUTTON_PLUS, lang().text("gui.lost.entry-register-off"), lore);
        } else {
            registerBtn = GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    lang().text("gui.lost.entry-register"),
                    lang().lines("gui.lost.entry-register-lore", "fee", feeText, "refund", String.valueOf(refund),
                            "remaining", remainingLabel())
            );
        }
        GuiUtils.putIfChanged(inventory, SLOT_REGISTER, registerBtn);

        for (int i = 18; i <= 26; i++) {
            GuiUtils.putIfChanged(inventory, i, filler);
        }
        GuiUtils.putIfChanged(inventory, SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                lang().text("gui.close"),
                lang().lines("gui.close-lore")
        ));
    }

    /** Redraws every open registration menu (the countdown); unchanged slots are not resent. */
    public static void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanEntryGui gui) {
                gui.render();
            }
        }
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (!(openInv.getHolder() instanceof LostCaravanEntryGui gui)) return;

        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        if (rawSlot == SLOT_REGISTER) {
            LostCaravanManager manager = plugin.getLostCaravanManager();
            if (manager == null || !manager.isRegistrationPhase()) {
                MessageUtils.event(player, "<red>Фаза регистрации уже завершена или событие не активно!</red>");
                player.closeInventory();
                return;
            }

            LostCaravanManager.RegisterResult result = manager.registerParticipant(player);
            switch (result) {
                case ALREADY_REGISTERED -> MessageUtils.event(player, "<yellow>Вы уже зарегистрированы в этом караване.</yellow>");
                case NO_FEE -> MessageUtils.event(player, "<red>У вас нет достаточного количества монет для внесения залога!</red>");
                case EVENT_NOT_REGISTRATION -> MessageUtils.event(player, "<red>Регистрация уже закрыта!</red>");
                default -> {}
            }
            gui.render();
        }
    }
}
