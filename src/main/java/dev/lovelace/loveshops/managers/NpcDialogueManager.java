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

    public void sayBankerGreeting(Player player) {
        say(player, "banker.dialogues.greeting", List.of(
                "<gold>[Банкир]</gold> <gray>Приветствую! Звонкая монета всегда в цене. Желаете разменять или укрупнить капитал?</gray>",
                "<gold>[Банкир]</gold> <gray>Добро пожаловать в хранилище. Все номиналы в наличии, комиссия минимальна!</gray>",
                "<gold>[Банкир]</gold> <gray>Деньги любят счёт и порядок. Выкладывайте монеты на стол — всё пересчитаем без обмана.</gray>"
        ));
    }

    public void sayBankerDismiss(Player player) {
        say(player, "banker.dialogues.dismiss", List.of(
                "<red>[Банкир]</red> <gray>С такими сомнительными личностями я дел не веду. Убирайтесь от сейфа!</gray>",
                "<red>[Банкир]</red> <gray>Охрана! Следите за этим типом. Никаких операций для вас сегодня!</gray>",
                "<red>[Банкир]</red> <gray>Твоя дурная репутация идёт впереди тебя. Я не доверяю свои сундуки подозрительным бродягам.</gray>"
        ));
    }

    public void sayBankerClose(Player player) {
        say(player, "banker.dialogues.close", List.of(
                "<gold>[Банкир]</gold> <gray>Всего доброго! Не забывайте свои монеты, берегите сбережения.</gray>",
                "<gold>[Банкир]</gold> <gray>Удачных сделок! Возвращайтесь, если потребуется размен.</gray>"
        ));
    }

    public void sayBankerError(Player player) {
        say(player, "banker.dialogues.error", List.of(
                "<red>[Банкир]</red> <gray>Здесь какая-то ошибка в расчётах. Проверьте сумму на столе.</gray>",
                "<red>[Банкир]</red> <gray>Не хватает монет с учётом комиссии. Положите больше средств.</gray>"
        ));
    }

    public void sayWandererGreeting(Player player) {
        say(player, "wanderer.dialogues.greeting", List.of(
                "<dark_purple>[Странник]</dark_purple> <gray>Тише... Я принёс диковинки из далёких земель, каких в этих краях ещё не видали.</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Дороги были опасны, но товар того стоит. Взгляни, путник, пока я не ушёл дальше.</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Шёпот ветров привёл меня сюда. Ищешь нечто особенное? Загляни в мой мешок.</gray>"
        ));
    }

    public void sayWandererDismiss(Player player) {
        say(player, "wanderer.dialogues.dismiss", List.of(
                "<dark_purple>[Странник]</dark_purple> <gray>От тебя разит бедой. Я странствую не для того, чтобы связываться со слабаками или бандитами. Прочь!</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Мои товары не для тебя. Скройся с глаз, пока я не растворился в тенях.</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Не подходи! Чутьё никогда не подводило меня — от тебя не жди добра.</gray>"
        ));
    }

    public void sayWandererClose(Player player) {
        say(player, "wanderer.dialogues.close", List.of(
                "<dark_purple>[Странник]</dark_purple> <gray>Ветер снова зовёт меня в дорогу... До новой встречи, если судьбе будет угодно.</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Береги то, что приобрёл. В этих краях такие сокровища — редкость.</gray>"
        ));
    }

    public void sayWandererError(Player player) {
        say(player, "wanderer.dialogues.error", List.of(
                "<dark_purple>[Странник]</dark_purple> <gray>Ты предлагаешь слишком мало за такие редкости. Приходи с полной мошной.</gray>",
                "<dark_purple>[Странник]</dark_purple> <gray>Сделка не может состояться. Дорога не терпит пустых обещаний.</gray>"
        ));
    }

    public void sayCaravanerGreeting(Player player) {
        say(player, "caravan.daily.dialogues.greeting", List.of(
                "<gold>[Караванщик]</gold> <gray>Здравствуй, путник! Ящики открыты — клади то, что нужно, и получай монеты.</gray>",
                "<gold>[Караванщик]</gold> <gray>Дороги длинные, а склады пустые. Если есть чем поделиться — я хорошо плачу.</gray>",
                "<gold>[Караванщик]</gold> <gray>Подходи, не стесняйся. Смотри на ящики: там написано, что и по какой цене беру.</gray>"
        ));
    }

    public void sayCaravanerDismiss(Player player) {
        say(player, "caravan.daily.dialogues.dismiss", List.of(
                "<red>[Караванщик]</red> <gray>С тобой торговать не стану. Слухи о тебе идут впереди обоза.</gray>",
                "<red>[Караванщик]</red> <gray>Проходи мимо. Мой груз не для таких, как ты.</gray>"
        ));
    }

    public void sayCaravanerClose(Player player) {
        say(player, "caravan.daily.dialogues.close", List.of(
                "<gold>[Караванщик]</gold> <gray>Хорошая сделка! Заходи ещё, пока мы не тронулись в путь.</gray>",
                "<gold>[Караванщик]</gold> <gray>Удачной дороги. Завтра обоз может быть уже далеко.</gray>"
        ));
    }

    public void sayLostCaravanGreeting(Player player) {
        say(player, "caravan.lost.dialogues.greeting", List.of(
                "<gold>[Торговец каравана]</gold> <gray>Мы сбились с пути, зато груз цел. Желаешь заглянуть в ящики?</gray>",
                "<gold>[Торговец каравана]</gold> <gray>Тише, не шуми. Этот обоз потерялся давно — и нашёл тебя. Подходи.</gray>",
                "<gold>[Торговец каравана]</gold> <gray>Торги скоро начнутся. Внеси залог, и ящики станут твоими, если хватит смелости.</gray>"
        ));
    }

    public void sayLostCaravanDismiss(Player player) {
        say(player, "caravan.lost.dialogues.dismiss", List.of(
                "<red>[Торговец каравана]</red> <gray>Тебе здесь не рады. Убирайся, пока я не позвал охрану.</gray>",
                "<red>[Торговец каравана]</red> <gray>Знаю я таких. Ящики не для тебя.</gray>"
        ));
    }

    public void sayLostCaravanClose(Player player) {
        say(player, "caravan.lost.dialogues.close", List.of(
                "<gold>[Торговец каравана]</gold> <gray>Что ж, ящики никуда не денутся — до конца торгов.</gray>",
                "<gold>[Торговец каравана]</gold> <gray>Удачи на торгах. Помни: побеждает тот, кто не торопится.</gray>"
        ));
    }

    /** @return true, если фраза реально была отправлена (список в конфиге не пуст). */
    private boolean say(Player player, String configPath) {
        return say(player, configPath, List.of());
    }

    private boolean say(Player player, String configPath, List<String> defaults) {
        List<String> messages = plugin.getConfig().getStringList(configPath);
        if (messages.isEmpty()) {
            messages = defaults;
        }
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        MessageUtils.sendMessage(player, messages.get(random.nextInt(messages.size())));
        return true;
    }
}
