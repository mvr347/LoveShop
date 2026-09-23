package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

import dev.lovelace.loveshops.textures.HeadTextures;

public class WandererDealGui {

    public static final String TITLE = "Странник (Договор)";
    public static final String CONTRACT_HEAD_BASE64 = HeadTextures.WANDERER_CONTRACT;

    private final LoveShops plugin;
    private final Player player;

    public WandererDealGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE).color(NamedTextColor.DARK_PURPLE));

        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head (Golden Rule 2)
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 26: Close button ALWAYS in corner for 27-slot menu
        inv.setItem(26, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>",
            List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        int cost = plugin.getConfig().getInt("wanderer.deal.cost", 150);
        int deliveryMinutes = plugin.getConfig().getInt("wanderer.deal.delivery-time-minutes", 60);
        String currencyName = plugin.getEconomy().map(LoveEconomy::currencyName).orElse("монет");
        String timeStr = TimeUtils.formatRemainingTime(deliveryMinutes * 60L);

        // Slot 13: Contract deal button
        ItemStack dealBtn = GuiUtils.createCustomHead(CONTRACT_HEAD_BASE64, "<gold><bold>Заключить сделку со Странником</bold></gold>",
            List.of(
                "",
                "<gray>Странник отправится в запретные земли и</gray>",
                "<gray>добудет случайный набор редкой контрабанды.</gray>",
                "",
                "<gray>Стоимость аванса: <gold>" + cost + " " + currencyName + "</gold></gray>",
                "<gray>Срок доставки: <gold>" + timeStr + "</gold></gray>",
                "",
                "<green><bold>ЛКМ</bold> </green><gray>— договориться и отправить Странника</gray>"
            ));

        inv.setItem(13, dealBtn);

        player.openInventory(inv);
    }
}
