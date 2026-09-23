package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.social.BehaviorLevels;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * "Живые" реакции НПС на реальные вежливость/стиль игры игрока (LoveBehavior), отдельно от
 * ручного статуса Скупщика ({@code buyer_reputation_overrides} — как проверялся в момент
 * финализации сделки, так и проверяется, этот класс его не трогает и не заменяет).
 *
 * <p>Ужасная вежливость (ступень 0) или агрессивный стиль игры (ступень 0) может полностью
 * заблокировать открытие GUI ({@code npc-dialogue.reject.*}), а любое настроение — включая
 * дружелюбное при хорошей репутации — может просто сказаться вслух, не мешая взаимодействию
 * ({@code npc-dialogue.ambient.*}). Без LoveBehavior обе фичи молча не действуют.</p>
 */
public class NpcDialogueManager {

    public enum Mood { TERRIBLE, AGGRESSIVE, FRIENDLY, NEUTRAL }

    private final LoveShops plugin;
    private final Random random = new Random();

    public NpcDialogueManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public Mood moodOf(UUID playerId) {
        return LoveCore.service(BehaviorLevels.class).map(levels -> {
            int politeness = levels.politenessLevel(playerId);
            int playstyle = levels.playstyleLevel(playerId);
            if (politeness <= 0) {
                return Mood.TERRIBLE;
            }
            if (playstyle <= 0) {
                return Mood.AGGRESSIVE;
            }
            if (politeness >= 5 || playstyle >= BehaviorLevels.MAX_LEVEL) {
                return Mood.FRIENDLY;
            }
            return Mood.NEUTRAL;
        }).orElse(Mood.NEUTRAL);
    }

    /**
     * Пытается отказать в обслуживании по настроению. Возвращает true, если отказано (GUI
     * открывать не надо) — только если реально нашлась фраза для показа игроку: пустой список
     * (например, старый config.yml без новых ключей) не должен молча блокировать GUI без
     * объяснения причины.
     */
    public boolean tryReject(Player player, Mood mood) {
        if (!plugin.getConfig().getBoolean("npc-dialogue.reject.enabled", true)) {
            return false;
        }
        String key = switch (mood) {
            case TERRIBLE -> "npc-dialogue.reject.terrible-politeness";
            case AGGRESSIVE -> "npc-dialogue.reject.aggressive-playstyle";
            case FRIENDLY, NEUTRAL -> null;
        };
        if (key == null) {
            return false;
        }
        return say(player, key);
    }

    /** С настроенным шансом говорит фразу под настроение, не блокируя взаимодействие. */
    public void maybeSayAmbient(Player player, Mood mood) {
        if (mood == Mood.NEUTRAL || !plugin.getConfig().getBoolean("npc-dialogue.ambient.enabled", true)) {
            return;
        }
        double chance = plugin.getConfig().getDouble("npc-dialogue.ambient.chance", 0.35);
        if (random.nextDouble() >= chance) {
            return;
        }
        String key = switch (mood) {
            case TERRIBLE -> "npc-dialogue.ambient.terrible-politeness";
            case AGGRESSIVE -> "npc-dialogue.ambient.aggressive-playstyle";
            case FRIENDLY -> "npc-dialogue.ambient.friendly";
            case NEUTRAL -> null;
        };
        if (key != null) {
            say(player, key);
        }
    }

    /** @return true, если фраза реально была отправлена (список в конфиге не пуст). */
    private boolean say(Player player, String configPath) {
        List<String> messages = plugin.getConfig().getStringList(configPath);
        if (messages.isEmpty()) {
            return false;
        }
        MessageUtils.sendMessage(player, messages.get(random.nextInt(messages.size())));
        return true;
    }
}
