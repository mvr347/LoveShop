package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.gui.StallOwnerGui;
import dev.lovelace.loveshops.market.model.BlacklistEntry;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
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

/**
 * {@code /tradepoint} (торговая точка): managing your own shop.
 * <pre>
 * /tradepoint                              open the shop menu
 * /tradepoint returns                      collect returned goods and coins
 * /tradepoint transfer &lt;player&gt;            hand the point to another player
 * /tradepoint blacklist add|remove|list    the point's blacklist
 * /tradepoint discount set|remove          personal discounts
 * /tradepoint mode [BOTH|SELL_ONLY|BUY_ONLY]
 * </pre>
 * All texts are in lang.yml ({@code market.cmd-*}).
 */
public final class TradePointCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("menu", "returns", "transfer", "blacklist", "discount", "mode", "help");
    private static final List<String> BLACKLIST_SUBS = List.of("add", "remove", "list");
    private static final List<String> DISCOUNT_SUBS = List.of("set", "remove");
    private static final List<String> MODE_SUBS = List.of("BOTH", "SELL_ONLY", "BUY_ONLY");

    private final LoveShops plugin;

    public TradePointCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender to, String key, String... kv) {
        plugin.getMarketMessages().send(to, key, kv);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "cmd-players-only");
            return true;
        }
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) {
            msg(player, "cmd-market-off");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            manager.byOwner(player.getUniqueId()).ifPresentOrElse(
                    point -> new StallOwnerGui(plugin, player, point).open(),
                    () -> {
                        msg(player, "cmd-no-point");
                        sendHelp(player);
                    });
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "returns" -> manager.claimReturns(player);
            case "transfer" -> handleTransfer(player, manager, args);
            case "blacklist" -> handleBlacklist(player, manager, args);
            case "discount" -> handleDiscount(player, manager, args);
            case "mode" -> handleMode(player, manager, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private TradePoint ownedPoint(Player player, TradePointManager manager) {
        var opt = manager.byOwner(player.getUniqueId());
        if (opt.isEmpty()) {
            msg(player, "cmd-no-point");
            return null;
        }
        return opt.get();
    }

    private void handleTransfer(Player player, TradePointManager manager, String[] args) {
        if (args.length < 2) {
            msg(player, "cmd-usage-transfer");
            return;
        }
        TradePoint point = ownedPoint(player, manager);
        if (point == null) return;
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(player, "cmd-player-offline", "player", args[1]);
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "transfer-self");
            return;
        }
        if (manager.transfer(point, target.getUniqueId())) {
            msg(player, "transfer-done", "player", target.getName());
            msg(target, "transfer-received", "player", player.getName());
        } else {
            msg(player, "transfer-failed");
        }
    }

    private void handleBlacklist(Player player, TradePointManager manager, String[] args) {
        TradePoint point = ownedPoint(player, manager);
        if (point == null) return;
        if (args.length < 2) {
            msg(player, "cmd-usage-blacklist");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                try {
                    List<BlacklistEntry> list = plugin.getMarketRepository().loadBlacklist(point.claimId());
                    msg(player, "cmd-blacklist-header", "count", String.valueOf(list.size()));
                    if (list.isEmpty()) msg(player, "cmd-blacklist-empty");
                    for (BlacklistEntry be : list) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
                        String name = op.getName() != null ? op.getName() : be.playerUuid().toString().substring(0, 8);
                        String reason = be.reason() != null && !be.reason().isBlank() ? be.reason()
                                : plugin.getMarketMessages().raw("gui-blacklist-no-reason");
                        msg(player, "cmd-blacklist-line", "player", name, "reason", reason);
                    }
                } catch (Exception e) {
                    msg(player, "db-error");
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    msg(player, "cmd-usage-blacklist-add");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    msg(player, "blacklist-self");
                    return;
                }
                if (!target.isOnline() && !target.hasPlayedBefore()) {
                    msg(player, "cmd-player-unknown", "player", args[2]);
                    return;
                }
                String reason = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null;
                try {
                    if (plugin.getMarketRepository().addBlacklist(point.claimId(), target.getUniqueId(), reason,
                            plugin.getMarketConfig().blacklistMaxEntries())) {
                        msg(player, "blacklist-added", "player", target.getName() != null ? target.getName() : args[2]);
                    } else {
                        msg(player, "blacklist-full");
                    }
                } catch (Exception e) {
                    msg(player, "db-error");
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    msg(player, "cmd-usage-blacklist-remove");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                try {
                    plugin.getMarketRepository().removeBlacklist(point.claimId(), target.getUniqueId());
                    msg(player, "blacklist-removed", "player", target.getName() != null ? target.getName() : args[2]);
                } catch (Exception e) {
                    msg(player, "db-error");
                }
            }
            default -> msg(player, "cmd-usage-blacklist");
        }
    }

    private void handleDiscount(Player player, TradePointManager manager, String[] args) {
        TradePoint point = ownedPoint(player, manager);
        if (point == null) return;
        if (args.length < 2) {
            msg(player, "cmd-usage-discount");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                int max = plugin.getMarketConfig().discountMaxPercent();
                if (args.length < 4) {
                    msg(player, "cmd-usage-discount-set", "max", String.valueOf(max));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                int percent;
                int days = 0;
                try {
                    percent = Integer.parseInt(args[3]);
                    if (args.length > 4) days = Math.max(0, Integer.parseInt(args[4]));
                } catch (NumberFormatException e) {
                    msg(player, "prompt-invalid");
                    return;
                }
                if (percent < 1 || percent > max) {
                    msg(player, "cmd-discount-range", "max", String.valueOf(max));
                    return;
                }
                Long expiresAt = days > 0 ? System.currentTimeMillis() + (days * 86_400_000L) : null;
                try {
                    plugin.getMarketRepository().setDiscount(point.claimId(), target.getUniqueId(), percent, expiresAt);
                    msg(player, "discount-given", "percent", String.valueOf(percent),
                            "player", target.getName() != null ? target.getName() : args[2]);
                } catch (Exception e) {
                    msg(player, "db-error");
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    msg(player, "cmd-usage-discount-remove");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                try {
                    plugin.getMarketRepository().removeDiscount(point.claimId(), target.getUniqueId());
                    msg(player, "discount-removed", "player", target.getName() != null ? target.getName() : args[2]);
                } catch (Exception e) {
                    msg(player, "db-error");
                }
            }
            default -> msg(player, "cmd-usage-discount");
        }
    }

    private void handleMode(Player player, TradePointManager manager, String[] args) {
        TradePoint point = ownedPoint(player, manager);
        if (point == null) return;
        if (args.length < 2) {
            msg(player, "cmd-mode-current", "mode", plugin.getMarketMessages().raw("gui-mode-" + point.tradingMode().name().toLowerCase(Locale.ROOT)));
            msg(player, "cmd-mode-list");
            return;
        }
        try {
            TradingMode mode = TradingMode.valueOf(args[1].toUpperCase(Locale.ROOT));
            point.tradingMode(mode);
            manager.save(point);
            manager.updateNpc(point);
            manager.refreshViewers(point.claimId());
            msg(player, "cmd-mode-set", "mode", plugin.getMarketMessages().raw("gui-mode-" + mode.name().toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            msg(player, "cmd-mode-unknown");
        }
    }

    private void sendHelp(Player player) {
        for (String line : plugin.getMarketMessages().lines("cmd-help")) {
            player.sendMessage(dev.lovelace.loveshops.utils.MessageUtils.parse(player, line));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBS, new ArrayList<>());
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "transfer" -> StringUtil.copyPartialMatches(args[1], online, new ArrayList<>());
                case "blacklist" -> StringUtil.copyPartialMatches(args[1], BLACKLIST_SUBS, new ArrayList<>());
                case "discount" -> StringUtil.copyPartialMatches(args[1], DISCOUNT_SUBS, new ArrayList<>());
                case "mode" -> StringUtil.copyPartialMatches(args[1], MODE_SUBS, new ArrayList<>());
                default -> List.of();
            };
        }
        if (args.length == 3 && (sub.equals("blacklist") || sub.equals("discount"))) {
            return StringUtil.copyPartialMatches(args[2], online, new ArrayList<>());
        }
        if (args.length == 4 && sub.equals("discount") && args[1].equalsIgnoreCase("set")) {
            return StringUtil.copyPartialMatches(args[3], List.of("5", "10", "15", "20", "25", "50"), new ArrayList<>());
        }
        return List.of();
    }
}
