package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /tp} · {@code /тп}: teleport to a trade point.
 * <ul>
 *   <li>{@code /tp} - to your own point;</li>
 *   <li>{@code /tp <id>} - to the point with that id (needs {@code loveshops.tp.any}, admins always may);</li>
 *   <li>anything else is the vanilla {@code /tp}: this command replaces it on the server, so
 *       whatever is not about a trade point is passed on to {@code minecraft:tp} for players who
 *       may use it - the admins' teleport must keep working.</li>
 * </ul>
 */
public final class TradePointTeleportCommand implements CommandExecutor, TabCompleter {

    private final LoveShops plugin;

    public TradePointTeleportCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        TradePointManager manager = plugin.getTradePointManager();

        if (args.length > 0) {
            Optional<TradePoint> byId = manager == null ? Optional.empty() : manager.byName(args[0]);
            if (byId.isEmpty() || args.length > 1) {
                return passToVanilla(sender, args);
            }
            if (!(sender instanceof Player player)) {
                plugin.getMarketMessages().send(sender, "cmd-players-only");
                return true;
            }
            TradePoint point = byId.get();
            boolean mine = point.isOwner(player.getUniqueId());
            if (!mine && !player.hasPermission("loveshops.tp.any") && !player.hasPermission("loveshops.admin.point")) {
                plugin.getMarketMessages().send(player, "tp-no-permission");
                return true;
            }
            teleport(player, manager, point);
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.getMarketMessages().send(sender, "cmd-players-only");
            return true;
        }
        if (manager == null) {
            plugin.getMarketMessages().send(player, "cmd-market-off");
            return true;
        }
        Optional<TradePoint> owned = manager.byOwner(player.getUniqueId());
        if (owned.isEmpty()) {
            plugin.getMarketMessages().send(player, "cmd-no-point");
            return true;
        }
        teleport(player, manager, owned.get());
        return true;
    }

    private void teleport(Player player, TradePointManager manager, TradePoint point) {
        Location target = manager.teleportTarget(point);
        if (target == null || target.getWorld() == null) {
            plugin.getMarketMessages().send(player, "tp-unknown-location");
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> {
            if (ok) plugin.getMarketMessages().send(player, "tp-done", "id", manager.nameOf(point));
        });
    }

    /** Not a trade point: hands the command to the vanilla teleport, if the sender may use it. */
    private boolean passToVanilla(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minecraft.command.teleport")) {
            plugin.getMarketMessages().send(sender, "tp-no-permission");
            return true;
        }
        Bukkit.dispatchCommand(sender, "minecraft:tp " + String.join(" ", args));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        TradePointManager manager = plugin.getTradePointManager();
        if (manager != null && (sender.hasPermission("loveshops.tp.any") || sender.hasPermission("loveshops.admin.point"))) {
            for (TradePoint p : manager.all()) options.add(manager.nameOf(p));
        }
        return StringUtil.copyPartialMatches(args[0].toLowerCase(Locale.ROOT), options, new ArrayList<>());
    }
}
