package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.WandererDeal;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

import dev.lovelace.loveshops.textures.HeadTextures;

public class WandererWaitingGui {

    public static final String TITLE = "Странник (В пути)";
    public static final String HOURGLASS_HEAD_BASE64 = HeadTextures.WANDERER_WAITING;

    private final LoveShops plugin;
    private final Player player;
    private final WandererDeal deal;

    public WandererWaitingGui(LoveShops plugin, Player player, WandererDeal deal) {
        this.plugin = plugin;
        this.player = player;
        this.deal = deal;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE).color(NamedTextColor.YELLOW));

        // gui-gen-5 RULE 2/RULE 6: стекло — только в Header (0-8) и Footer (18-26). Рабочая
        // зона (9-17) стекла не получает — единственный реальный контент здесь (голова в
        // слоте 13), остальные слоты рабочей зоны остаются пустыми, а не стеклянными.
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i <= 8; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 18; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 26: Close button
        inv.setItem(26, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>",
            List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        long remaining = deal != null ? deal.remainingSeconds() : 0;
        String remainingText = TimeUtils.formatRemainingTime(remaining);

        // Slot 13: Waiting Status Head
        ItemStack waitingHead = GuiUtils.createCustomHead(HOURGLASS_HEAD_BASE64, "<yellow><bold>Странник в пути...</bold></yellow>",
            List.of(
                "",
                "<gray>Странник исследует тайные уголки мира,</gray>",
                "<gray>собирая контрабандные предметы для вас.</gray>",
                "",
                "<gray>Осталось ждать: <gold>" + remainingText + "</gold></gray>",
                "",
                "<dark_gray>Возвращайтесь, когда таймер истечёт!</dark_gray>"
            ));

        inv.setItem(13, waitingHead);

        player.openInventory(inv);
    }
}
