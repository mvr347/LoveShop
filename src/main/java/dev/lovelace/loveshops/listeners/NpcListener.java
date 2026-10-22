package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.BankerGui;
import dev.lovelace.loveshops.gui.WarMerchantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.lovecore.api.npc.NpcClick;
import dev.lovelace.lovecore.api.npc.NpcClickEvent;
import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcSpawnEvent;
import dev.lovelace.loveshops.npc.NpcTags;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Clicks on, and the spawn veto for, the NPCs of the configured NPC provider. LoveCore fires its NPC events for
 * every provider that is installed, so each handler first checks that the NPC belongs to the provider chosen in
 * {@code integrations.npc-provider} and ignores the rest.
 */
public class NpcListener implements Listener {

    private final LoveShops plugin;

    public NpcListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    /** Schedule-based spawn veto: an event NPC (or a disabled role) is not allowed to appear out of season. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcSpawn(NpcSpawnEvent event) {
        NpcHandle npc = event.npc();
        if (npc == null || !plugin.getNpcBridge().owns(npc)) return;

        NpcData npcData = plugin.getNpcManager().resolve(npc);
        if (npcData != null) {
            if (!plugin.getNpcManager().isNpcAllowedToSpawn(npcData)) {
                event.setCancelled(true);
            }
            return;
        }

        // Direct tag check as fallback: an NPC we created whose row is not loaded yet
        String type = npc.tag(NpcTags.TYPE).orElse(null);
        if (type != null && !plugin.getNpcManager().isTypeAllowedToSpawn(type)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onNpcRightClick(NpcClickEvent event) {
        if (event.click() != NpcClick.RIGHT) return;
        NpcHandle npc = event.npc();
        if (npc == null || !plugin.getNpcBridge().owns(npc)) return;

        // Market NPCs (stall traders and guards) are tagged on the NPC itself: check them first, the
        // "nearest NPC" fallback below could otherwise pick a server NPC standing beside a stall.
        var feudal = plugin.getFeudalService();
        if (feudal != null && feudal.handleClick(event.getPlayer(), npc)) {
            return;
        }
        var market = plugin.getTradePointManager();
        if (market != null && market.handleNpcClick(event.getPlayer(), npc)) {
            return;
        }

        NpcData npcData = plugin.getNpcManager().resolve(npc);
        if (npcData == null) {
            npcData = plugin.getNpcManager().getNpcNear(event.getPlayer().getLocation(), 3.0)
                    .filter(n -> !dev.lovelace.loveshops.managers.NpcManager.isEphemeralType(n.type())).orElse(null);
        }
        if (npcData != null && dev.lovelace.loveshops.managers.NpcManager.isEphemeralType(npcData.type())
                && !plugin.getNpcManager().isNpcAllowedToSpawn(npcData)) {
            return; // the event is over: its NPC is on its way out, no menu
        }

        if (npcData != null) {
            Player player = event.getPlayer();
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
}
