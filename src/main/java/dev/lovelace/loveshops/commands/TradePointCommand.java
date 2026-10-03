package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.BlacklistEntry;
import dev.lovelace.loveshops.market.model.DiscountEntry;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.UUID;

/**
 * Player command for trade point management:
 * /tradepoint returns
 * /tradepoint transfer <player>
 * /tradepoint blacklist add|remove|list <player> [reason]
 * /tradepoint discount set|remove <player> [percent]
 * /tradepoint mode [BOTH|SELL_ONLY|BUY_ONLY]
 */
public final class TradePointCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("tp", "returns", "transfer", "blacklist", "discount", "mode");
    private static final List<String> BLACKLIST_SUBS = List.of("add", "remove", "list");
    private static final List<String> DISCOUNT_SUBS = List.of("set", "remove");
    private static final List<String> MODE_SUBS = List.of("BOTH", "SELL_ONLY", "BUY_ONLY");

    private final LoveShops plugin;

    public TradePointCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtils.parse("<red>Команда только для игроков!</red>"));
            return true;
        }

        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Модуль торговых точек отключён.</red>"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "tp" -> {
                handleTeleport(player, manager, args);
                return true;
            }
            case "returns" -> {
                manager.claimReturns(player);
                return true;
            }
            case "transfer" -> {
                handleTransfer(player, manager, args);
                return true;
            }
            case "blacklist" -> {
                handleBlacklist(player, manager, args);
                return true;
            }
            case "discount" -> {
                handleDiscount(player, manager, args);
                return true;
            }
            case "mode" -> {
                handleMode(player, manager, args);
                return true;
            }
            default -> {
                sendHelp(player);
                return true;
            }
        }
    }

    private TradePoint getOwnedPoint(Player player, TradePointManager manager) {
        var opt = manager.byOwner(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage(MessageUtils.parse(player, "<red>У вас нет арендованной торговой точки!</red>"));
            return null;
        }
        return opt.get();
    }

    private void handleTransfer(Player player, TradePointManager manager, String[] args) {
        if (args.length < 2) {
            player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint transfer <игрок></yellow>"));
            return;
        }
        TradePoint point = getOwnedPoint(player, manager);
        if (point == null) return;

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(MessageUtils.parse(player, "<red>Игрок " + args[1] + " не найден или не в сети!</red>"));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(MessageUtils.parse(player, "<red>Нельзя передать точку самому себе!</red>"));
            return;
        }

        boolean ok = manager.transfer(point, target.getUniqueId());
        if (ok) {
            player.sendMessage(MessageUtils.parse(player, "<green>Торговая точка успешно передана игроку " + target.getName() + "!</green>"));
            target.sendMessage(MessageUtils.parse(target, "<green>Вам передана торговая точка от " + player.getName() + "!</green>"));
        } else {
            player.sendMessage(MessageUtils.parse(player, "<red>Не удалось передать точку.</red>"));
        }
    }

    private void handleBlacklist(Player player, TradePointManager manager, String[] args) {
        TradePoint point = getOwnedPoint(player, manager);
        if (point == null) return;

        if (args.length < 2) {
            player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint blacklist <add|remove|list> [игрок] [причина]</yellow>"));
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                try {
                    List<BlacklistEntry> list = plugin.getMarketRepository().loadBlacklist(point.claimId());
                    player.sendMessage(MessageUtils.parse(player, "<gold>=== Чёрный список точки (" + list.size() + ") ===</gold>"));
                    if (list.isEmpty()) player.sendMessage(MessageUtils.parse(player, "<gray>Список пуст.</gray>"));
                    for (BlacklistEntry be : list) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
                        String name = op.getName() != null ? op.getName() : be.playerUuid().toString().substring(0, 8);
                        player.sendMessage(MessageUtils.parse(player, "<red>• " + name + "</red> <gray>(" + (be.reason() != null ? be.reason() : "без причины") + ")</gray>"));
                    }
                } catch (Exception e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Ошибка при чтении чёрного списка.</red>"));
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint blacklist add <игрок> [причина]</yellow>"));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Нельзя внести себя в чёрный список!</red>"));
                    return;
                }
                if (!target.isOnline() && !target.hasPlayedBefore()) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Игрок с ником " + args[2] + " на сервере не играл.</red>"));
                    return;
                }
                String reason = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null;
                try {
                    if (plugin.getMarketRepository().addBlacklist(point.claimId(), target.getUniqueId(), reason,
                            plugin.getMarketConfig().blacklistMaxEntries())) {
                        player.sendMessage(MessageUtils.parse(player, "<green>Игрок " + (target.getName() != null ? target.getName() : args[2]) + " добавлен в чёрный список точки.</green>"));
                    } else {
                        player.sendMessage(MessageUtils.parse(player, "<red>Чёрный список заполнен.</red>"));
                    }
                } catch (Exception e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Ошибка при добавлении в чёрный список.</red>"));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint blacklist remove <игрок></yellow>"));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                try {
                    plugin.getMarketRepository().removeBlacklist(point.claimId(), target.getUniqueId());
                    player.sendMessage(MessageUtils.parse(player, "<green>Игрок " + (target.getName() != null ? target.getName() : args[2]) + " удалён из чёрного списка точки.</green>"));
                } catch (Exception e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Ошибка при удалении из чёрного списка.</red>"));
                }
            }
            default -> player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint blacklist <add|remove|list></yellow>"));
        }
    }

    private void handleDiscount(Player player, TradePointManager manager, String[] args) {
        TradePoint point = getOwnedPoint(player, manager);
        if (point == null) return;

        if (args.length < 2) {
            player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint discount <set|remove> <игрок> [процент] [дни]</yellow>"));
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "set" -> {
                if (args.length < 4) {
                    player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint discount set <игрок> <процент (1-50)> [дни]</yellow>"));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                int percent;
                try {
                    percent = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Неверный процент!</red>"));
                    return;
                }
                int max = plugin.getMarketConfig().discountMaxPercent();
                if (percent < 1 || percent > max) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Скидка должна быть от 1 до " + max + "%!</red>"));
                    return;
                }
                int days = args.length > 4 ? Math.max(0, Integer.parseInt(args[4])) : 0;
                Long expiresAt = days > 0 ? System.currentTimeMillis() + (days * 86_400_000L) : null;
                try {
                    plugin.getMarketRepository().setDiscount(point.claimId(), target.getUniqueId(), percent, expiresAt);
                    player.sendMessage(MessageUtils.parse(player, "<green>Скидка " + percent + "% установлена для игрока " + (target.getName() != null ? target.getName() : args[2]) + "!</green>"));
                } catch (Exception e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Ошибка при сохранении скидки.</red>"));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint discount remove <игрок></yellow>"));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                try {
                    plugin.getMarketRepository().removeDiscount(point.claimId(), target.getUniqueId());
                    player.sendMessage(MessageUtils.parse(player, "<green>Скидка игрока " + (target.getName() != null ? target.getName() : args[2]) + " удалена.</green>"));
                } catch (Exception e) {
                    player.sendMessage(MessageUtils.parse(player, "<red>Ошибка при удалении скидки.</red>"));
                }
            }
            default -> player.sendMessage(MessageUtils.parse(player, "<yellow>Использование: /tradepoint discount <set|remove></yellow>"));
        }
    }

    private void handleMode(Player player, TradePointManager manager, String[] args) {
        TradePoint point = getOwnedPoint(player, manager);
        if (point == null) return;

        if (args.length < 2) {
            player.sendMessage(MessageUtils.parse(player, "<gray>Текущий режим торговли: <white>" + point.tradingMode() + "</white></gray>"));
            player.sendMessage(MessageUtils.parse(player, "<gray>Доступные режимы: <white>BOTH, SELL_ONLY, BUY_ONLY</white></gray>"));
            return;
        }

        try {
            TradingMode mode = TradingMode.valueOf(args[1].toUpperCase(Locale.ROOT));
            point.tradingMode(mode);
            manager.save(point);
            manager.updateNpc(point);
            manager.refreshViewers(point.claimId());
            player.sendMessage(MessageUtils.parse(player, "<green>Режим торговли установлен на: " + mode + "</green>"));
        } catch (IllegalArgumentException e) {
            player.sendMessage(MessageUtils.parse(player, "<red>Неизвестный режим торговли! Допустимо: BOTH, SELL_ONLY, BUY_ONLY</red>"));
        }
    }

    private void handleTeleport(Player player, TradePointManager manager, String[] args) {
        TradePoint point = null;
        if (args.length >= 2) {
            if (!player.hasPermission("loveshops.admin.market") && !player.hasPermission("loveshops.admin")) {
                player.sendMessage(MessageUtils.parse(player, "<red>Нет прав телепортироваться к чужой точке.</red>"));
                return;
            }
            try {
                UUID id = UUID.fromString(args[1]);
                point = manager.getPoint(id);
            } catch (IllegalArgumentException e) {
                player.sendMessage(MessageUtils.parse(player, "<red>Неверный UUID точки.</red>"));
                return;
            }
        } else {
            Optional<TradePoint> owned = manager.byOwner(player.getUniqueId());
            if (owned.isEmpty()) {
                player.sendMessage(MessageUtils.parse(player, "<yellow>У вас нет торговой точки.</yellow>"));
                return;
            }
            point = owned.get();
        }

        if (point == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Точка не найдена.</red>"));
            return;
        }
        org.bukkit.Location loc = manager.getNpcOrPointLocation(point);
        if (loc == null || loc.getWorld() == null) {
            player.sendMessage(MessageUtils.parse(player, "<red>Локация точки неизвестна.</red>"));
            return;
        }
        player.teleport(loc);
        player.sendMessage(MessageUtils.parse(player, "<green>Вы телепортированы к торговой точке.</green>"));
    }

    private void sendHelp(Player player) {
        player.sendMessage(MessageUtils.parse(player, "<gold>=== Управление торговой точкой ===</gold>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint tp [uuid]</yellow> <gray>— телепортироваться к своей торговой точке</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint returns</yellow> <gray>— забрать возвраты товаров и монет</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint transfer <игрок></yellow> <gray>— передать точку другому игроку</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint blacklist <add|remove|list></yellow> <gray>— чёрный список точки</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint discount <set|remove></yellow> <gray>— персональные скидки</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<yellow>/tradepoint mode [режим]</yellow> <gray>— режим торговли (BOTH, SELL_ONLY, BUY_ONLY)</gray>"));
        player.sendMessage(MessageUtils.parse(player, "<gold>====================================</gold>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) return List.of();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBS, new ArrayList<>());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "tp" -> (sender.hasPermission("loveshops.admin.market") || sender.hasPermission("loveshops.admin"))
                        ? StringUtil.copyPartialMatches(args[1], manager.all().stream().map(p -> p.claimId().toString()).toList(), new ArrayList<>())
                        : List.of();
                case "transfer" -> StringUtil.copyPartialMatches(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), new ArrayList<>());
                case "blacklist" -> StringUtil.copyPartialMatches(args[1], BLACKLIST_SUBS, new ArrayList<>());
                case "discount" -> StringUtil.copyPartialMatches(args[1], DISCOUNT_SUBS, new ArrayList<>());
                case "mode" -> StringUtil.copyPartialMatches(args[1], MODE_SUBS, new ArrayList<>());
                default -> List.of();
            };
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("blacklist") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                return StringUtil.copyPartialMatches(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), new ArrayList<>());
            }
            if (sub.equals("discount") && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove"))) {
                return StringUtil.copyPartialMatches(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), new ArrayList<>());
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("discount") && args[1].equalsIgnoreCase("set")) {
            return StringUtil.copyPartialMatches(args[3], List.of("5", "10", "15", "20", "25", "50"), new ArrayList<>());
        }
        return List.of();
    }
}
