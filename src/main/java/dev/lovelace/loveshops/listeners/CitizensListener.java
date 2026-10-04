package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.BankerGui;
import dev.lovelace.loveshops.gui.WarMerchantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class CitizensListener implements Listener {

    private final LoveShops plugin;

    public CitizensListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizensSpawn(NPCSpawnEvent event) {
        NPC npc = event.getNPC();
        if (npc == null) return;

        NpcData npcData = resolveNpcData(npc);
        if (npcData != null) {
            if (!plugin.getNpcManager().isNpcAllowedToSpawn(npcData)) {
                event.setCancelled(true);
            }
            return;
        }

        // Direct metadata check as fallback
        if (npc.data().has("loveshops_type")) {
            String type = npc.data().get("loveshops_type", null);
            if (!plugin.getNpcManager().isTypeAllowedToSpawn(type)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onCitizensRightClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        if (npc == null) return;

        // Market NPCs (stall traders and guards) are tagged on the NPC itself: check them first, the
        // "nearest NPC" fallback below could otherwise pick a server NPC standing beside a stall.
        var feudal = plugin.getFeudalService();
        if (feudal != null && feudal.handleClick(event.getClicker(), npc)) {
            return;
        }
        var market = plugin.getTradePointManager();
        if (market != null && market.handleNpcClick(event.getClicker(), npc)) {
            return;
        }

        NpcData npcData = resolveNpcData(npc);
        if (npcData == null) {
            npcData = plugin.getNpcManager().getNpcNear(event.getClicker().getLocation(), 3.0)
                    .filter(n -> !dev.lovelace.loveshops.managers.NpcManager.isEphemeralType(n.type())).orElse(null);
        }
        if (npcData != null && dev.lovelace.loveshops.managers.NpcManager.isEphemeralType(npcData.type())
                && !plugin.getNpcManager().isNpcAllowedToSpawn(npcData)) {
            return; // the event is over: its NPC is on its way out, no menu
        }

        if (npcData != null) {
            Player player = event.getClicker();
            if (npcData.type().equalsIgnoreCase("wanderer")) {
                plugin.getWandererManager().handleWandererInteraction(player);
                return;
            }

            if (npcData.type().equalsIgnoreCase("warmerchant")) {
                // Отдельный гейт по стилю игры вместо обычной вежливость/агрессия mood-проверки
                // ниже - этот НПС наоборот открыт ТОЛЬКО агрессивным, а не закрыт для них.
                if (!plugin.getWarMerchantManager().isEligible(player.getUniqueId())) {
                    MessageUtils.sendMessage(player, plugin.getWarMerchantManager().randomDenyMessage());
                    return;
                }
                new WarMerchantGui(plugin, player).open();
                return;
            }

            var dialogue = plugin.getNpcDialogueManager();
            var mood = dialogue.moodOf(player.getUniqueId());
            if (dialogue.tryReject(player, mood)) {
                switch (npcData.type().toLowerCase()) {
                    case "banker" -> dialogue.sayBankerDismiss(player);
                    case "caravaner" -> dialogue.sayCaravanerDismiss(player);
                    case "lostcaravan" -> dialogue.sayLostCaravanDismiss(player);
                    default -> { }
                }
                return;
            }

            switch (npcData.type().toLowerCase()) {
                case "banker" -> {
                    dialogue.sayBankerGreeting(player);
                    new BankerGui(plugin, player).open();
                }
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
            dialogue.maybeSayAmbient(player, mood);
        }
    }

    private NpcData resolveNpcData(NPC npc) {
        if (npc == null) return null;
        String uuidStr = npc.data().get("loveshops_uuid", null);
        if (uuidStr != null) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                NpcData data = plugin.getNpcManager().getNpcByUuid(uuid);
                if (data != null) return data;
            } catch (IllegalArgumentException ignored) {}
        }
        return plugin.getNpcManager().getNpcByCitizensId(npc.getId()).orElse(null);
    }
}
