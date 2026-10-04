package dev.lovelace.loveshops.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Аудиовизуальные эффекты караванов: звуки колокола, торговля жителей, частицы дыма и фейерверков.
 */
public final class CaravanEffects {

    private CaravanEffects() {}

    /**
     * Эффекты прибытия каравана: звон колокола, облака и частицы довольного торговца.
     */
    public static void playArrivalEffects(@Nullable Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 0.8f);
        loc.getWorld().playSound(loc, Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.0f);
        loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 1.0, 0), 30, 0.6, 0.8, 0.6, 0.05);
        loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1.2, 0), 15, 0.5, 0.5, 0.5, 0.0);
    }

    /**
     * Эффекты ухода каравана: стук копыт, закрытие ящиков и клубы дыма.
     */
    public static void playDepartureEffects(@Nullable Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        loc.getWorld().playSound(loc, Sound.ENTITY_HORSE_GALLOP, 1.2f, 0.9f);
        loc.getWorld().playSound(loc, Sound.BLOCK_CHEST_CLOSE, 1.0f, 0.8f);
        loc.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0, 0.5, 0), 25, 0.5, 0.5, 0.5, 0.02);
        loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 1.0, 0), 20, 0.5, 0.5, 0.5, 0.03);
    }

    /**
     * Эффект успешной сдачи товаров игроком: звон монет и частицы радости.
     */
    public static void playSubmitEffects(Player player) {
        if (player == null || !player.isOnline()) return;
        Location loc = player.getLocation();
        player.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        player.playSound(loc, Sound.ENTITY_VILLAGER_YES, 0.9f, 1.1f);
        if (loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1.2, 0), 12, 0.4, 0.4, 0.4, 0.0);
        }
    }

    /** A leading "[Topic]" tag of the old caravan messages: turned into a plain "Topic:" under the herald prefix. */
    private static final java.util.regex.Pattern LEGACY_TAG = java.util.regex.Pattern.compile(
            "^<(gold|red|yellow|gray)>(?:<bold>)?(?:⚔ )?\\[([^\\]]+)\\](?:</bold>)?</\\1>\\s*");

    /**
     * Глобальное оповещение: идёт через глашатая (LoveTweaks), чтобы все объявления сервера выглядели одинаково.
     */
    public static void broadcast(String message) {
        if (message == null || message.isBlank()) return;
        String body = LEGACY_TAG.matcher(message).replaceFirst("<gold>$2</gold><dark_gray>:</dark_gray> ");
        broadcast(MessageUtils.parse(body));
    }

    public static void broadcast(Component component) {
        if (component == null) return;
        dev.lovelace.loveshops.integration.HeraldBridge.announce(
                dev.lovelace.loveshops.LoveShops.getInstance().getLogger(), component);
    }
}
