package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.AuctionGui;
import dev.lovelace.loveshops.gui.BuyerGui;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                    sender.sendMessage(MessageUtils.parse("<red>Игрок " + MessageUtils.escapeTags(args[0]) + " не найден или не в сети!</red>"));
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
                        sender.sendMessage(MessageUtils.parse("<red>Игрок " + MessageUtils.escapeTags(args[2]) + " не найден или не в сети!</red>"));
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
            // Админ-подкоманды (reload, npc, buyer-статус, seller/event) переехали под единую
            // /loveshopsadmin — здесь остаются только редиректы, чтобы команда не «молчала»
            // для тех, кто по привычке набирает /loveshops reload и т.п.
            case "reload" -> redirectToAdmin(sender, "/loveshopsadmin reload", "loveshops.admin.reload");
            case "npc" -> redirectToAdmin(sender, "/loveshopsadmin npc", "loveshops.admin");
            case "buyer" -> redirectToAdmin(sender, "/loveshopsadmin buyer", "loveshops.admin.buyer");
            case "seller", "event" -> redirectToAdmin(sender, "/loveshopsadmin seller", "loveshops.admin.seller");
            default -> sendHelp(sender);
        }

        return true;
    }

    /**
     * Показывает подсказку о переезде старой admin-подкоманды под {@code /loveshopsadmin}
     * тем, у кого были на неё права, и обычное "нет прав" — остальным, чтобы поведение
     * не отличалось от того, что было раньше.
     */
    private void redirectToAdmin(CommandSender sender, String newCommand, String specificPermission) {
        if (sender.hasPermission(specificPermission) || sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.admin-moved",
                "<yellow>Эта команда переехала: используйте <gold>{command}</gold>.</yellow>", Map.of("command", newCommand)));
        } else {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getLangManager().getMessage("commands.help-header", "<dark_gray>========== <gold>LoveShops Помощь</gold> ==========</dark_gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("commands.help-open", "<gold>/loveshops open <buyer|seller|auctioneer> [игрок]</gold> <gray>- Открыть меню магазина</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("commands.help-aliases", "<gold>/buyer, /seller, /auction</gold> <gray>- Быстрые алиасы для открытия меню</gray>"));
        if (sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.help-admin", "<gold>/loveshopsadmin</gold> <gray>- Административные команды LoveShops</gray>"));
        }
        sender.sendMessage(plugin.getLangManager().getMessage("commands.help-footer", "<dark_gray>=========================================</dark_gray>"));
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
        }
        return completions.stream().filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}
