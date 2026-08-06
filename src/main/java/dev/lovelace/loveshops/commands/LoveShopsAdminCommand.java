package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.NpcData;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Единая административная команда плагина: {@code /loveshopsadmin <subcommand>}.
 * <p>
 * Раньше admin-функции (reload, npc, статус скупщика, управление барахолкой) были зарыты
 * внутри {@code /loveshops ...} вперемешку с торговыми подкомандами игроков, что не
 * соответствует принятому в экосистеме Love* стилю единой родительской admin-команды
 * с подкомандами. Старые пути под {@code /loveshops} теперь только редиректят сюда —
 * см. {@link ShopsCommand}.
 */
public class LoveShopsAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "npc", "buyer", "seller", "help");
    private static final List<String> NPC_ACTIONS = List.of("create", "delete", "list");
    private static final List<String> NPC_TYPES = List.of("buyer", "seller", "auctioneer");
    private static final List<String> BUYER_STATUSES = List.of("default", "good", "bad", "aggressive");
    private static final List<String> SELLER_ACTIONS = List.of("start", "stop", "reset");

    private final LoveShops plugin;

    public LoveShopsAdminCommand(@NotNull LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAnyAdminAccess(sender)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "npc" -> handleNpc(sender, args);
            case "buyer" -> handleBuyer(sender, args);
            case "seller", "event" -> handleSeller(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean hasAnyAdminAccess(CommandSender sender) {
        return sender.hasPermission("loveshops.admin")
                || sender.hasPermission("loveshops.admin.reload")
                || sender.hasPermission("loveshops.admin.create")
                || sender.hasPermission("loveshops.admin.delete")
                || sender.hasPermission("loveshops.admin.buyer")
                || sender.hasPermission("loveshops.admin.seller");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("loveshops.admin.reload") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        plugin.reloadConfig();
        plugin.getLangManager().loadLang();
        sender.sendMessage(plugin.getLangManager().getMessage("commands.reload-success", "<green>Конфигурация перезагружена!</green>"));
    }

    private void handleNpc(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
                return;
            }
            if (!player.hasPermission("loveshops.admin.create") && !player.hasPermission("loveshops.admin")) {
                player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                return;
            }
            if (args.length < 4) {
                player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc create <buyer|seller|auctioneer> <Имя></yellow>"));
                return;
            }
            String type = args[2].toLowerCase(Locale.ROOT);
            if (!NPC_TYPES.contains(type)) {
                player.sendMessage(MessageUtils.parse("<red>Неверный тип NPC! Выберите: buyer, seller, auctioneer</red>"));
                return;
            }
            String name = String.join(" ", List.of(args).subList(3, args.length));
            Location loc = player.getLocation();

            plugin.getNpcManager().createNpc(type, name, loc, player.getName()).thenAccept(npc -> {
                player.sendMessage(plugin.getLangManager().getMessage("commands.npc-created", "<green>NPC создан!</green>",
                    java.util.Map.of("type", type, "name", name)));
            });
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("delete")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
                return;
            }
            if (!player.hasPermission("loveshops.admin.delete") && !player.hasPermission("loveshops.admin")) {
                player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                return;
            }
            Optional<NpcData> nearNpc = plugin.getNpcManager().getNpcNear(player.getLocation(), 4.0);
            if (nearNpc.isEmpty()) {
                player.sendMessage(plugin.getLangManager().getMessage("commands.npc-not-looking", "<red>Рядом не найден NPC!</red>"));
                return;
            }
            NpcData target = nearNpc.get();
            plugin.getNpcManager().deleteNpc(target.uuid()).thenAccept(success -> {
                if (success) {
                    player.sendMessage(plugin.getLangManager().getMessage("commands.npc-deleted", "<green>NPC удалён!</green>",
                        java.util.Map.of("name", target.name())));
                }
            });
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            var npcs = plugin.getNpcManager().getAllNpcs();
            if (npcs.isEmpty()) {
                sender.sendMessage(MessageUtils.parse("<yellow>В базе данных нет созданных NPC.</yellow>"));
                return;
            }
            sender.sendMessage(MessageUtils.parse("<gold>=== Список NPC LoveShops (" + npcs.size() + ") ===</gold>"));
            for (var npc : npcs) {
                String locStr = String.format("%s [%.1f, %.1f, %.1f]", npc.world(), npc.x(), npc.y(), npc.z());
                sender.sendMessage(MessageUtils.parse("<yellow># " + npc.id() + "</yellow> | <green>" + npc.type() + "</green> | <white>" + npc.name() + "</white> | <gray>" + locStr + "</gray>"));
            }
        } else {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc <create|delete|list> ...</yellow>"));
        }
    }

    private void handleBuyer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.buyer") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin buyer <игрок> <default|good|bad|aggressive> [сообщение]</yellow>"));
            return;
        }
        String targetName = args[1];
        String status = args[2].toLowerCase(Locale.ROOT);
        if (!BUYER_STATUSES.contains(status)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.invalid-status", "<red>Неверный статус!</red>"));
            return;
        }
        String customMsg = args.length >= 4 ? String.join(" ", List.of(args).subList(3, args.length)) : null;

        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        plugin.getBuyerManager().setPlayerStatus(targetPlayer.getUniqueId(), status, sender.getName(), customMsg).thenRun(() -> {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.buyer-status-set", "<green>Статус установлен!</green>",
                java.util.Map.of("player", targetName, "status", status)));
        });
    }

    private void handleSeller(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.seller") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin seller <start|stop|reset></yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                plugin.getSellerManager().forceStartSeller();
                sender.sendMessage(MessageUtils.parse("<green>Событие \"Барахолка\" запущенно принудительно!</green>"));
            }
            case "stop" -> {
                plugin.getSellerManager().forceStopSeller();
                sender.sendMessage(MessageUtils.parse("<red>Событие \"Барахолка\" остановлено принудительно!</red>"));
            }
            case "reset" -> {
                plugin.getSellerManager().resetSellerOverride();
                sender.sendMessage(MessageUtils.parse("<yellow>Принудительный режим сброшен. Используется автоматическое расписание.</yellow>"));
            }
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin seller <start|stop|reset></yellow>"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-header", "<dark_gray>========== <gold>LoveShops Admin</gold> ==========</dark_gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-reload", "<gold>/loveshopsadmin reload</gold> <gray>- Перезагрузить конфигурацию</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-npc", "<gold>/loveshopsadmin npc <create|delete|list></gold> <gray>- Управление NPC-торговцами</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-buyer", "<gold>/loveshopsadmin buyer <игрок> <статус> [сообщение]</gold> <gray>- Статус игрока у скупщика</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-seller", "<gold>/loveshopsadmin seller <start|stop|reset></gold> <gray>- Управление барахолкой</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-footer", "<dark_gray>=========================================</dark_gray>"));
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!hasAnyAdminAccess(sender)) return Collections.emptyList();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            return StringUtil.copyPartialMatches(args[1], NPC_ACTIONS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")) {
            return StringUtil.copyPartialMatches(args[2], NPC_TYPES, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buyer")) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buyer")) {
            return StringUtil.copyPartialMatches(args[2], BUYER_STATUSES, new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("seller") || args[0].equalsIgnoreCase("event"))) {
            return StringUtil.copyPartialMatches(args[1], SELLER_ACTIONS, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
