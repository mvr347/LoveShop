package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /tp · /тп — teleport to own trade point (or by claim UUID for admins).
 */
public final class TradePointTeleportCommand implements CommandExecutor, TabCompleter {

    private final LoveShops plugin;

    public TradePointTeleportCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtils.parse("<red>Только для игроков.</red>"));
            return true;
        }
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Модуль торговых точек отключён.</red>"));
            return true;
        }

        TradePoint point = null;
        if (args.length >= 1) {
            if (!player.hasPermission("loveshops.admin.market") && !player.hasPermission("loveshops.admin")) {
                player.sendMessage(MessageUtils.parse(player, "<red>Нет прав телепортироваться к чужой точке.</red>"));
                return true;
            }
            try {
                UUID id = UUID.fromString(args[0]);
                point = manager.getPoint(id);
            } catch (IllegalArgumentException e) {
                player.sendMessage(MessageUtils.parse(player, "<red>Неверный UUID точки.</red>"));
                return true;
            }
        } else {
            Optional<TradePoint> owned = manager.byOwner(player.getUniqueId());
            if (owned.isEmpty()) {
                player.sendMessage(MessageUtils.parse(player, "<yellow>У вас нет торговой точки.</yellow>"));
                return true;
            }
            point = owned.get();
        }

        if (point == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Точка не найдена.</red>"));
            return true;
        }
        Location loc = manager.getNpcOrPointLocation(point);
        if (loc == null || loc.getWorld() == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Локация точки неизвестна.</red>"));
            return true;
        }
        player.teleport(loc);
        player.sendMessage(MessageUtils.parse(player, "<green>Вы телепортированы к торговой точке.</green>"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return new ArrayList<>();
    }
}
