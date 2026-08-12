package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class CitizensListener implements Listener {

    private final LoveShops plugin;

    public CitizensListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCitizensRightClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        if (npc == null) return;

        String uuidStr = npc.data().get("loveshops_uuid", null);
        NpcData npcData = null;

        if (uuidStr != null) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                npcData = plugin.getNpcManager().getNpcByUuid(uuid);
            } catch (IllegalArgumentException ignored) {}
        }

        if (npcData == null) {
            npcData = plugin.getNpcManager().getNpcNear(event.getClicker().getLocation(), 3.0).orElse(null);
        }

        if (npcData != null) {
            Player player = event.getClicker();
            if (npcData.type().equalsIgnoreCase("seller") && !plugin.getSellerManager().isSellerActive()) {
                MessageUtils.sendMessage(player, "<red>Торговец-барахолка открыт только по воскресеньям с 10:00 до 18:00!</red>");
                return;
            }

            var dialogue = plugin.getNpcDialogueManager();
            var mood = dialogue.moodOf(player.getUniqueId());
            if (dialogue.tryReject(player, mood)) {
                return;
            }

            switch (npcData.type().toLowerCase()) {
                case "buyer" -> new BuyerGui(plugin, player).open();
                case "seller" -> new SellerGui(plugin, player).open();
                case "auctioneer" -> new AuctionGui(plugin, player).open();
            }
            dialogue.maybeSayAmbient(player, mood);
        }
    }
}
