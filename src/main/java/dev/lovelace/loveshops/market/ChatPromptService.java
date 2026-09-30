package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type a number in chat" prompts for the market GUIs. The answer is swallowed (never reaches public
 * chat) and handled on the main thread. One prompt per player; a prompt dies on timeout, on quit,
 * or when the player types {@code отмена}.
 */
public final class ChatPromptService implements Listener {

    private record Prompt(long expiresAt, Consumer<String> onInput, Runnable onCancel) {}

    private static final int PRUNE_THRESHOLD = 64;

    private final LoveShops plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ChatPromptService(LoveShops plugin) {
        this.plugin = plugin;
    }

    /** Asks {@code player} for one line of chat. Replaces any earlier prompt (its {@code onCancel} runs). */
    public void ask(Player player, Consumer<String> onInput, Runnable onCancel) {
        prune();
        long expires = System.currentTimeMillis() + plugin.getMarketConfig().promptTimeoutSeconds() * 1000L;
        Prompt old = prompts.put(player.getUniqueId(), new Prompt(expires, onInput, onCancel));
        if (old != null && old.onCancel() != null) old.onCancel().run();
    }

    public boolean has(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    public void cancel(Player player) {
        Prompt p = prompts.remove(player.getUniqueId());
        if (p != null && p.onCancel() != null) p.onCancel().run();
    }

    public void clear() {
        prompts.clear();
    }

    /** Timed-out prompts of players who never typed anything must not pile up. */
    private void prune() {
        if (prompts.size() < PRUNE_THRESHOLD) return;
        long now = System.currentTimeMillis();
        prompts.values().removeIf(p -> p.expiresAt() < now);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (System.currentTimeMillis() > prompt.expiresAt()) {
                plugin.getMarketMessages().send(player, "prompt-timeout");
                if (prompt.onCancel() != null) prompt.onCancel().run();
                return;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.equals("отмена") || lower.equals("cancel")) {
                plugin.getMarketMessages().send(player, "prompt-cancelled");
                if (prompt.onCancel() != null) prompt.onCancel().run();
                return;
            }
            prompt.onInput().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }
}
