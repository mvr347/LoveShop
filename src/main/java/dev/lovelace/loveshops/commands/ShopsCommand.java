package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ShopsCommand implements CommandExecutor, TabCompleter {

    private final LoveShops plugin;

    public ShopsCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmdLabel = label.toLowerCase();

        // Direct alias handling (/buyer, /seller, /auction, /auctioneer)
        if (cmdLabel.equals("buyer") || cmdLabel.equals("seller") || cmdLabel.equals("auction") || cmdLabel.equals("auctioneer")) {
            Player target = null;
            if (args.length >= 1 && (sender.hasPermission("loveshops.admin.open") || sender.hasPermission("loveshops.admin"))) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(MessageUtils.parse("<red>Игрок " + args[0] + " не найден или не в сети!</red>"));
                    return true;
                }
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
                return true;
            }

            switch (cmdLabel) {
                case "buyer" -> new BuyerGui(plugin, target).open();
                case "seller" -> new SellerGui(plugin, target).open();
                case "auction", "auctioneer" -> new AuctionGui(plugin, target).open();
            }

            if (sender != target) {
                sender.sendMessage(MessageUtils.parse("<green>Меню " + cmdLabel + " успешно открыто для " + target.getName() + "!</green>"));
            }
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "gui", "open", "openmenu" -> {
                if (args.length < 2) {
                    sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops open <buyer|seller|auctioneer> [игрок]</yellow>"));
                    return true;
                }
                String menuType = args[1].toLowerCase();
                Player target = null;

                if (args.length >= 3) {
                    if (!sender.hasPermission("loveshops.admin.open") && !sender.hasPermission("loveshops.admin")) {
                        sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав открывать меню другим игрокам!</red>"));
                        return true;
                    }
                    target = Bukkit.getPlayer(args[2]);
                    if (target == null || !target.isOnline()) {
                        sender.sendMessage(MessageUtils.parse("<red>Игрок " + args[2] + " не найден или не в сети!</red>"));
                        return true;
                    }
                } else {
                    if (sender instanceof Player player) {
                        target = player;
                    } else {
                        sender.sendMessage(MessageUtils.parse("<red>Консоль должна указывать игрока: /loveshops open <menu> <player></red>"));
                        return true;
                    }
                }

                switch (menuType) {
                    case "buyer" -> new BuyerGui(plugin, target).open();
                    case "seller" -> new SellerGui(plugin, target).open();
                    case "auctioneer", "auction" -> new AuctionGui(plugin, target).open();
                    default -> sender.sendMessage(MessageUtils.parse("<red>Неизвестное меню! Выберите: buyer, seller, auctioneer</red>"));
                }

                if (sender != target) {
                    sender.sendMessage(MessageUtils.parse("<green>Меню " + menuType + " успешно открыто для " + target.getName() + "!</green>"));
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("loveshops.admin.reload") && !sender.hasPermission("loveshops.admin")) {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                    return true;
                }
                plugin.reloadConfig();
                plugin.getLangManager().loadLang();
                sender.sendMessage(plugin.getLangManager().getMessage("commands.reload-success", "<green>Конфигурация перезагружена!</green>"));
            }
            case "npc" -> {
                if (!sender.hasPermission("loveshops.admin")) {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
                        return true;
                    }
                    if (!player.hasPermission("loveshops.admin.create") && !player.hasPermission("loveshops.admin")) {
                        player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                        return true;
                    }
                    if (args.length < 4) {
                        player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops npc create <buyer|seller|auctioneer> <Имя></yellow>"));
                        return true;
                    }
                    String type = args[2].toLowerCase();
                    if (!List.of("buyer", "seller", "auctioneer").contains(type)) {
                        player.sendMessage(MessageUtils.parse("<red>Неверный тип NPC! Выберите: buyer, seller, auctioneer</red>"));
                        return true;
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
                        return true;
                    }
                    if (!player.hasPermission("loveshops.admin.delete") && !player.hasPermission("loveshops.admin")) {
                        player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                        return true;
                    }
                    Optional<NpcData> nearNpc = plugin.getNpcManager().getNpcNear(player.getLocation(), 4.0);
                    if (nearNpc.isEmpty()) {
                        player.sendMessage(plugin.getLangManager().getMessage("commands.npc-not-looking", "<red>Рядом не найден NPC!</red>"));
                        return true;
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
                        return true;
                    }
                    sender.sendMessage(MessageUtils.parse("<gold>=== Список NPC LoveShops (" + npcs.size() + ") ===</gold>"));
                    for (var npc : npcs) {
                        String locStr = String.format("%s [%.1f, %.1f, %.1f]", npc.world(), npc.x(), npc.y(), npc.z());
                        sender.sendMessage(MessageUtils.parse("<yellow># " + npc.id() + "</yellow> | <green>" + npc.type() + "</green> | <white>" + npc.name() + "</white> | <gray>" + locStr + "</gray>"));
                    }
                } else {
                    sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops npc <create|delete|list> ...</yellow>"));
                }
            }
            case "buyer" -> {
                if (!sender.hasPermission("loveshops.admin.buyer") && !sender.hasPermission("loveshops.admin")) {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops buyer <игрок> <default|good|bad|aggressive> [сообщение]</yellow>"));
                    return true;
                }
                String targetName = args[1];
                String status = args[2].toLowerCase();
                if (!List.of("default", "good", "bad", "aggressive").contains(status)) {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.invalid-status", "<red>Неверный статус!</red>"));
                    return true;
                }
                String customMsg = args.length >= 4 ? String.join(" ", List.of(args).subList(3, args.length)) : null;

                OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
                plugin.getBuyerManager().setPlayerStatus(targetPlayer.getUniqueId(), status, sender.getName(), customMsg).thenRun(() -> {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.buyer-status-set", "<green>Статус установлен!</green>",
                        java.util.Map.of("player", targetName, "status", status)));
                });
            }
            case "seller", "event" -> {
                if (!sender.hasPermission("loveshops.admin.seller") && !sender.hasPermission("loveshops.admin")) {
                    sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops seller <start|stop|reset></yellow>"));
                    return true;
                }
                String action = args[1].toLowerCase();
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
                    default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshops seller <start|stop|reset></yellow>"));
                }
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MessageUtils.parse("<gradient:#FF5555:#FFAA00>=== LoveShops Команды ===</gradient>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshops open <buyer|seller|auctioneer> [игрок]</gold> - Открыть меню"));
        if (sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops open <buyer|seller|auctioneer> [игрок]</gold> - Открыть меню игроку (из игры/консоли)"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops npc create <type> <name></gold> - Создать NPC-торговца"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops npc delete</gold> - Удалить ближнего NPC"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops npc list</gold> - Просмотреть список всех NPC и их точные координаты"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops seller <start|stop|reset></gold> - Управление событием барахолки"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops buyer <player> <status> [msg]</gold> - Изменить статус игрока у скупщика"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshops reload</gold> - Перезагрузить конфиг"));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        String cmdLabel = label.toLowerCase();

        if (List.of("buyer", "seller", "auction", "auctioneer").contains(cmdLabel)) {
            if (args.length == 1 && (sender.hasPermission("loveshops.admin.open") || sender.hasPermission("loveshops.admin"))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
            }
            return List.of();
        }

        if (args.length == 1) {
            completions.addAll(List.of("gui", "open", "help"));
            if (sender.hasPermission("loveshops.admin")) {
                completions.addAll(List.of("npc", "seller", "event", "buyer", "reload"));
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("openmenu"))) {
            completions.addAll(List.of("buyer", "seller", "auctioneer"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("openmenu"))) {
            completions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            completions.addAll(List.of("create", "delete", "list"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")) {
            completions.addAll(List.of("buyer", "seller", "auctioneer"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("seller") || args[0].equalsIgnoreCase("event"))) {
            completions.addAll(List.of("start", "stop", "reset"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("buyer")) {
            completions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("buyer")) {
            completions.addAll(List.of("default", "good", "bad", "aggressive"));
        }
        return completions.stream().filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}
