package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

/**
 * {@code /tradepointadmin} (tpadmin, tpa): everything an admin does with trade points.
 * <pre>
 * npc create|remove|tp taxer|seller|stall [id]
 * create &lt;id&gt; [price]      starts the creation wizard (zone, trader, signs, teleport spot)
 * wizard set|back|skip|finish|cancel|status
 * delete &lt;id&gt; confirm
 * owner set &lt;player&gt; &lt;id&gt;  |  owner remove [player] &lt;id&gt;
 * lvl set|add|remove &lt;id&gt; &lt;n&gt;
 * price &lt;id&gt; &lt;amount&gt;  ·  list  ·  info &lt;id&gt;  ·  reconcile
 * </pre>
 * Permission {@code loveshops.admin.point}; texts in lang.yml ({@code market.tpa-*}).
 */
public final class TradePointAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("npc", "create", "wizard", "delete", "owner", "lvl", "price", "list", "info", "reconcile");
    private static final List<String> NPC_ACTIONS = List.of("create", "remove", "tp");
    private static final List<String> NPC_TYPES = List.of("taxer", "seller", "stall");
    private static final List<String> WIZARD_ACTIONS = List.of("set", "back", "skip", "finish", "cancel", "status");

    private final LoveShops plugin;

    public TradePointAdminCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender to, String key, String... kv) {
        plugin.getMarketMessages().send(to, key, kv);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveshops.admin.point")) {
            msg(sender, "tpa-no-permission");
            return true;
        }
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) {
            msg(sender, "cmd-market-off");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "npc" -> npc(sender, manager, args);
            case "create" -> create(sender, args);
            case "wizard" -> wizard(sender, args);
            case "delete" -> delete(sender, manager, args);
            case "owner" -> owner(sender, manager, args);
            case "lvl" -> level(sender, manager, args);
            case "price" -> price(sender, manager, args);
            case "list" -> list(sender, manager);
            case "info" -> info(sender, manager, args);
            case "reconcile" -> {
                manager.reconcileNpcs();
                msg(sender, "tpa-reconciled");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        Player viewer = sender instanceof Player p ? p : null;
        for (String line : plugin.getMarketMessages().lines("tpa-help")) {
            sender.sendMessage(MessageUtils.parse(viewer, line));
        }
    }

    private Optional<TradePoint> point(CommandSender sender, TradePointManager manager, String id) {
        Optional<TradePoint> found = manager.byName(id);
        if (found.isEmpty()) msg(sender, "tpa-point-not-found", "id", id);
        return found;
    }

    // ------------------------------------------------------------------ npc

    private void npc(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3 || !NPC_ACTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            msg(sender, "tpa-usage-npc");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String type = args[2].toLowerCase(Locale.ROOT);
        Player admin = sender instanceof Player p ? p : null;
        if (admin == null && !action.equals("remove")) {
            msg(sender, "cmd-players-only");
            return;
        }
        ClaimsLink claims = manager.claimsLink();
        var feudal = plugin.getFeudalService();

        switch (type) {
            case "taxer" -> {
                switch (action) {
                    case "create" -> msg(sender, claims.spawnTaxer(admin.getLocation()) ? "tpa-npc-created" : "tpa-npc-failed", "type", type);
                    case "remove" -> removed(sender, type, claims.removeTaxers());
                    default -> teleportTo(admin, claims.taxerLocation().orElse(null), type);
                }
            }
            case "seller" -> {
                if (feudal == null) {
                    msg(sender, "tpa-npc-failed", "type", type);
                    return;
                }
                switch (action) {
                    case "create" -> msg(sender, feudal.create(admin.getLocation()) ? "tpa-npc-created" : "tpa-npc-failed", "type", type);
                    case "remove" -> removed(sender, type, feudal.removeAll());
                    default -> teleportTo(admin, feudal.location().orElse(null), type);
                }
            }
            case "stall" -> {
                if (args.length < 4) {
                    msg(sender, "tpa-usage-npc");
                    return;
                }
                Optional<TradePoint> point = point(sender, manager, args[3]);
                if (point.isEmpty()) return;
                stall(sender, admin, manager, point.get(), action);
            }
            default -> msg(sender, "tpa-usage-npc");
        }
    }

    private void stall(CommandSender sender, Player admin, TradePointManager manager, TradePoint point, String action) {
        switch (action) {
            case "create" -> {
                if (!point.hasOwner()) {
                    msg(sender, "tpa-stall-no-tenant", "id", manager.nameOf(point));
                    return;
                }
                manager.reconcileNpcs();
                msg(sender, "tpa-npc-created", "type", "stall");
            }
            case "remove" -> {
                plugin.getStallNpcService().destroyAllForPoint(point.claimId());
                point.npcCitizensId(null);
                point.guardCitizensId(null);
                manager.save(point);
                msg(sender, "tpa-npc-removed", "type", "stall", "count", "1");
            }
            default -> teleportTo(admin, manager.getNpcOrPointLocation(point), "stall");
        }
    }

    private void removed(CommandSender sender, String type, int count) {
        msg(sender, count > 0 ? "tpa-npc-removed" : "tpa-npc-none", "type", type, "count", String.valueOf(count));
    }

    private void teleportTo(Player admin, Location target, String type) {
        if (target == null || target.getWorld() == null) {
            msg(admin, "tpa-npc-none", "type", type, "count", "0");
            return;
        }
        admin.teleportAsync(target).thenAccept(ok -> msg(admin, "tpa-teleported", "type", type));
    }

    // ------------------------------------------------------------------ create / wizard

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            msg(sender, "cmd-players-only");
            return;
        }
        if (args.length < 2) {
            msg(sender, "tpa-usage-create");
            return;
        }
        long price = plugin.getMarketConfig().defaultRentPrice();
        if (args.length > 2) {
            try {
                price = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                msg(sender, "prompt-invalid");
                return;
            }
            if (price < 0) {
                msg(sender, "prompt-invalid");
                return;
            }
        }
        plugin.getWizardService().start(admin, args[1], price);
    }

    private void wizard(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            msg(sender, "cmd-players-only");
            return;
        }
        plugin.getWizardService().action(admin, args.length > 1 ? args[1] : "status");
    }

    // ------------------------------------------------------------------ delete / owner / level / price

    private void delete(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 2) {
            msg(sender, "tpa-usage-delete");
            return;
        }
        Optional<TradePoint> point = point(sender, manager, args[1]);
        if (point.isEmpty()) return;
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            msg(sender, "tpa-delete-confirm", "id", manager.nameOf(point.get()));
            return;
        }
        String id = manager.nameOf(point.get());
        if (manager.claimsLink().delete(point.get().claimId())) {
            msg(sender, "tpa-deleted", "id", id);
        } else {
            msg(sender, "tpa-point-not-found", "id", id);
        }
    }

    private void owner(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3) {
            msg(sender, "tpa-usage-owner");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("set")) {
            if (args.length < 4) {
                msg(sender, "tpa-usage-owner");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                msg(sender, "cmd-player-unknown", "player", args[2]);
                return;
            }
            Optional<TradePoint> point = point(sender, manager, args[3]);
            if (point.isEmpty()) return;
            manager.adminAssign(point.get(), target.getUniqueId());
            msg(sender, "tpa-owner-set", "player", target.getName() != null ? target.getName() : args[2],
                    "id", manager.nameOf(point.get()));
        } else if (action.equals("remove")) {
            String id = args[args.length - 1];
            Optional<TradePoint> point = point(sender, manager, id);
            if (point.isEmpty()) return;
            if (!point.get().hasOwner()) {
                msg(sender, "tpa-owner-none", "id", manager.nameOf(point.get()));
                return;
            }
            if (args.length > 3) {
                String nick = args[2];
                if (point.get().ownerName() == null || !point.get().ownerName().equalsIgnoreCase(nick)) {
                    msg(sender, "tpa-owner-mismatch", "player", nick, "id", manager.nameOf(point.get()));
                    return;
                }
            }
            manager.adminRelease(point.get());
            msg(sender, "tpa-owner-removed", "id", manager.nameOf(point.get()));
        } else {
            msg(sender, "tpa-usage-owner");
        }
    }

    private void level(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 4) {
            msg(sender, "tpa-usage-lvl");
            return;
        }
        Optional<TradePoint> point = point(sender, manager, args[2]);
        if (point.isEmpty()) return;
        int n;
        try {
            n = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            msg(sender, "prompt-invalid");
            return;
        }
        int current = point.get().level();
        int target = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> n;
            case "add" -> current + n;
            case "remove" -> current - n;
            default -> Integer.MIN_VALUE;
        };
        if (target == Integer.MIN_VALUE) {
            msg(sender, "tpa-usage-lvl");
            return;
        }
        if (manager.adminSetLevel(point.get(), target)) {
            msg(sender, "tpa-lvl-set", "id", manager.nameOf(point.get()), "level", String.valueOf(point.get().level()));
        } else {
            msg(sender, "db-error");
        }
    }

    private void price(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3) {
            msg(sender, "tpa-usage-price");
            return;
        }
        Optional<TradePoint> point = point(sender, manager, args[1]);
        if (point.isEmpty()) return;
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            msg(sender, "prompt-invalid");
            return;
        }
        if (amount < 0 || !manager.claimsLink().setPrice(point.get().claimId(), amount)) {
            msg(sender, "prompt-invalid");
            return;
        }
        msg(sender, "tpa-price-set", "id", manager.nameOf(point.get()), "price", plugin.getMarketStyle().money(amount));
    }

    // ------------------------------------------------------------------ list / info

    private void list(CommandSender sender, TradePointManager manager) {
        List<TradePoint> all = manager.all().stream()
                .sorted(java.util.Comparator.comparing(manager::nameOf, String.CASE_INSENSITIVE_ORDER)).toList();
        msg(sender, "tpa-list-header", "count", String.valueOf(all.size()));
        for (TradePoint p : all) {
            msg(sender, "tpa-list-line", "id", manager.nameOf(p),
                    "owner", p.hasOwner() ? (p.ownerName() == null ? "?" : p.ownerName()) : plugin.getMarketMessages().raw("tpa-free"),
                    "level", String.valueOf(p.level()));
        }
    }

    private void info(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 2) {
            msg(sender, "tpa-usage-info");
            return;
        }
        Optional<TradePoint> found = point(sender, manager, args[1]);
        if (found.isEmpty()) return;
        TradePoint p = found.get();
        long price = manager.infoOf(p).map(ClaimsLink.PointInfo::price).orElse(0L);
        Player viewer = sender instanceof Player pl ? pl : null;
        for (String line : plugin.getMarketMessages().lines("tpa-info",
                "id", manager.nameOf(p),
                "owner", p.hasOwner() ? (p.ownerName() == null ? "?" : p.ownerName()) : plugin.getMarketMessages().raw("tpa-free"),
                "level", String.valueOf(p.level()),
                "open", plugin.getMarketMessages().raw(p.open() ? "gui-status-open" : "gui-status-closed"),
                "price", plugin.getMarketStyle().money(price),
                "till", plugin.getMarketStyle().money(p.tillCoins()),
                "mode", plugin.getMarketMessages().raw("gui-mode-" + p.tradingMode().name().toLowerCase(Locale.ROOT)))) {
            sender.sendMessage(MessageUtils.parse(viewer, line));
        }
    }

    // ------------------------------------------------------------------ tab completion

    private List<String> pointIds() {
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) return List.of();
        return manager.all().stream().map(manager::nameOf).toList();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveshops.admin.point")) return List.of();
        List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 1) return StringUtil.copyPartialMatches(args[0], SUBS, new ArrayList<>());
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "npc" -> switch (args.length) {
                case 2 -> StringUtil.copyPartialMatches(args[1], NPC_ACTIONS, new ArrayList<>());
                case 3 -> StringUtil.copyPartialMatches(args[2], NPC_TYPES, new ArrayList<>());
                case 4 -> args[2].equalsIgnoreCase("stall") ? StringUtil.copyPartialMatches(args[3], pointIds(), new ArrayList<>()) : List.of();
                default -> List.of();
            };
            case "wizard" -> args.length == 2 ? StringUtil.copyPartialMatches(args[1], WIZARD_ACTIONS, new ArrayList<>()) : List.of();
            case "delete", "info" -> switch (args.length) {
                case 2 -> StringUtil.copyPartialMatches(args[1], pointIds(), new ArrayList<>());
                case 3 -> sub.equals("delete") ? StringUtil.copyPartialMatches(args[2], List.of("confirm"), new ArrayList<>()) : List.of();
                default -> List.of();
            };
            case "price" -> args.length == 2 ? StringUtil.copyPartialMatches(args[1], pointIds(), new ArrayList<>()) : List.of();
            case "owner" -> switch (args.length) {
                case 2 -> StringUtil.copyPartialMatches(args[1], List.of("set", "remove"), new ArrayList<>());
                case 3 -> args[1].equalsIgnoreCase("set") ? StringUtil.copyPartialMatches(args[2], online, new ArrayList<>())
                        : StringUtil.copyPartialMatches(args[2], pointIds(), new ArrayList<>());
                case 4 -> StringUtil.copyPartialMatches(args[3], pointIds(), new ArrayList<>());
                default -> List.of();
            };
            case "lvl" -> switch (args.length) {
                case 2 -> StringUtil.copyPartialMatches(args[1], List.of("set", "add", "remove"), new ArrayList<>());
                case 3 -> StringUtil.copyPartialMatches(args[2], pointIds(), new ArrayList<>());
                default -> List.of();
            };
            default -> List.of();
        };
    }
}
