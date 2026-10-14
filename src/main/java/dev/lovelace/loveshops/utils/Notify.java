package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.notify.LoveNotify;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

/**
 * Thin wrapper over LoveCore's {@link LoveNotify} for event-style player notices.
 *
 * <p>Rules (shared across all Love* plugins):
 * <ul>
 *   <li>Never gate-then-send ({@code isChannelEnabled} + {@code sendActionBar}): LoveCore only has
 *       CHAT enabled by default, so a gate silently drops the message. {@code notify(...)} itself
 *       falls back to chat when the text is too long, the channel is off, or it is important.</li>
 *   <li>The {@link LoveNotify} service is fetched on every call and never cached - another plugin may
 *       register it later or on reload.</li>
 *   <li>Command replies, input errors and clickable/multi-line messages stay in plain chat and do
 *       not go through here. Ticking HUD timers use {@link #hud}, never {@link #event}.</li>
 *   <li>Without LoveCore (or if its API classes are not loadable) the message is sent directly.</li>
 * </ul>
 *
 * <p>All LoveCore class references live in {@link Bridge}, so merely loading {@code Notify} never
 * touches the API: a missing LoveCore only ever surfaces as a caught {@link NoClassDefFoundError}.
 */
public final class Notify {

    private Notify() {}

    /** Pure action bar notice (chat only as LoveCore's overflow safety net). */
    public static void actionBar(Player player, Component message) {
        if (!Bridge.safeNotify(player, message, Bridge.ACTION_BAR, false)) {
            player.sendActionBar(message);
        }
    }

    /** Title notice; players who disabled titles get it on the action bar instead. */
    public static void title(Player player, Component message) {
        if (!Bridge.safeNotify(player, message, Bridge.TITLE, false)) {
            player.showTitle(Title.title(message, Component.empty()));
        }
    }

    /** Ordinary event notice: action bar, plus a chat copy for players who opted into chat. */
    public static void event(Player player, Component message) {
        if (!Bridge.safeNotify(player, message, Bridge.EVENT, false)) {
            player.sendActionBar(message);
            player.sendMessage(message);
        }
    }

    /** Critical notice (money spent, item lost): always duplicated to chat by LoveCore. */
    public static void important(Player player, Component message) {
        if (!Bridge.safeNotify(player, message, Bridge.EVENT, true)) {
            player.sendActionBar(message);
            player.sendMessage(message);
        }
    }

    /**
     * Ticking HUD text (countdown timers). Not a notification: it must never spill into chat, so it
     * bypasses {@code notify} and is only gated by the player's action bar toggle.
     */
    public static void hud(Player player, Component message) {
        if (Bridge.safeHudAllowed(player)) {
            player.sendActionBar(message);
        }
    }

    /** Isolates every LoveCore API reference; only ever loaded inside the try/catch blocks below. */
    static final class Bridge {
        static final int ACTION_BAR = 0;
        static final int TITLE = 1;
        static final int EVENT = 2;

        static boolean safeNotify(Player player, Component message, int kind, boolean important) {
            try {
                Optional<LoveNotify> service = LoveCore.service(LoveNotify.class);
                if (service.isEmpty()) {
                    return false;
                }
                service.get().notify(player, message, channels(kind), important);
                return true;
            } catch (NoClassDefFoundError missingApi) {
                return false;
            }
        }

        static boolean safeHudAllowed(Player player) {
            try {
                return LoveCore.service(LoveNotify.class)
                        .map(n -> n.isChannelEnabled(player.getUniqueId(), LoveNotify.Channel.ACTION_BAR))
                        .orElse(true);
            } catch (NoClassDefFoundError missingApi) {
                return true;
            }
        }

        static Set<LoveNotify.Channel> channels(int kind) {
            return switch (kind) {
                case ACTION_BAR -> Set.of(LoveNotify.Channel.ACTION_BAR);
                case TITLE -> Set.of(LoveNotify.Channel.TITLE, LoveNotify.Channel.ACTION_BAR);
                default -> Set.of(LoveNotify.Channel.ACTION_BAR, LoveNotify.Channel.CHAT);
            };
        }
    }
}
