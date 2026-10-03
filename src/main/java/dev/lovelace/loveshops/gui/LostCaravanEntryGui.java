package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.LostCaravanManager;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
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

import java.util.List;

/**
 * GUI Регистрации в Потерянном Караване (27 слотов):
 * - Header (0-8): профиль игрока (0), инфо (4), стекло (1-3, 5-8)
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

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, Component.text(TITLE).color(NamedTextColor.GOLD));
        render();
        player.openInventory(inventory);
    }

    public void render() {
        inventory.clear();
        ItemStack filler = GuiUtils.createFiller();

        // 1. Header (слоты 0-8)
        for (int i = 0; i <= 8; i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(0, GuiUtils.createPlayerProfileHead(player));

        int fee = plugin.getConfig().getInt("caravan.lost.entry-fee.amount", 1);
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold><bold>⚔ Правила Потерянного Каравана</bold></gold>",
                List.of(
                        "",
                        "<gray>Еженедельное торговое событие с ценными ящиками!</gray>",
                        "<gray>Залог за участие: </gray>" + CoinFormat.formatGlyphs(eco, fee),
                        "<gray>Кулдаун участия: <yellow>1 раз в 7 дней</yellow></gray>",
                        "<gray>Если вы ничего не выиграете — вернётся <green>50% залога</green>.</gray>",
                        "<gray>При >6 участниках — открывается аукцион.</gray>",
                        "<gray>При ≥12 участниках — появляется <red>6-й Секретный Ящик</red>!</gray>"
                )
        );
        inventory.setItem(4, infoItem);

        // 2. Рабочая зона (слоты 9-17)
        // В 27-слотовом меню по gui-gen-5: в рабочей зоне стекла НЕТ.
        boolean registered = manager.getCurrentSession() != null && manager.isParticipant(manager.getCurrentSession().id(), player.getUniqueId());
        long lastPart = manager.getPlayerLastParticipated(player.getUniqueId());
        long now = System.currentTimeMillis() / 1000;
        long cdRemain = (7L * 86400L) - (now - lastPart);

        ItemStack registerBtn;
        if (registered) {
            registerBtn = GuiUtils.createCustomHead(
                    HeadTextures.HEAD_CONFIRM,
                    "<green><bold>✓ Вы уже зарегистрированы!</bold></green>",
                    List.of("", "<gray>Ожидайте окончания фазы регистрации и начала торгов.</gray>")
            );
        } else if (lastPart > 0 && cdRemain > 0) {
            long days = cdRemain / 86400;
            long hours = (cdRemain % 86400) / 3600;
            registerBtn = GuiUtils.createCustomHead(
                    HeadTextures.HEAD_DELETE_NO,
                    "<red><bold>Кулдаун участия</bold></red>",
                    List.of("", "<gray>Вы сможете участвовать снова через: <yellow>" + days + " д. " + hours + " ч.</yellow></gray>")
            );
        } else {
            registerBtn = GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<gold><bold>Внести залог и участвовать</bold></gold>",
                    List.of(
                            "",
                            "<gray>Стоимость залога: </gray>" + CoinFormat.formatGlyphs(eco, fee),
                            "<gray>Неуспешным участникам возвращается <green>50%</green>.</gray>",
                            "",
                            "<yellow>Нажмите, чтобы зарегистрироваться!</yellow>"
                    )
            );
        }
        inventory.setItem(SLOT_REGISTER, registerBtn);

        // 3. Footer (слоты 18-26)
        for (int i = 18; i <= 26; i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>")
        ));
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
                MessageUtils.sendMessage(player, "<red>Фаза регистрации уже завершена или событие не активно!</red>");
                player.closeInventory();
                return;
            }

            LostCaravanManager.RegisterResult result = manager.registerParticipant(player);
            switch (result) {
                case ALREADY_REGISTERED -> MessageUtils.sendMessage(player, "<yellow>Вы уже зарегистрированы в этом караване.</yellow>");
                case COOLDOWN -> MessageUtils.sendMessage(player, "<red>Вы можете участвовать в Потерянном Караване только 1 раз в 7 дней!</red>");
                case NO_FEE -> MessageUtils.sendMessage(player, "<red>У вас нет достаточного количества монет для внесения залога!</red>");
                case EVENT_NOT_REGISTRATION -> MessageUtils.sendMessage(player, "<red>Регистрация уже закрыта!</red>");
                default -> {}
            }
            gui.render();
        }
    }
}
