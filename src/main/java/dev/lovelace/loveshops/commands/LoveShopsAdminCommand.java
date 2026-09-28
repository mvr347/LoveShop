package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.integration.CitizensIntegration;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Единая административная команда плагина: {@code /loveshopsadmin <subcommand>} (короткий
 * алиас {@code /lspa}, старый {@code /lssa} тоже работает).
 */
public class LoveShopsAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "npc", "status", "item", "price", "event", "banker", "help");
    private static final List<String> PRICE_TARGETS = List.of("buyer", "seller", "war_merchant", "wanderer", "auctioneer", "all");
    private static final List<String> BANKER_ACTIONS = List.of("fee");
    private static final List<String> NPC_ACTIONS = List.of("create", "bind", "delete", "list");
    private static final List<String> NPC_TYPES = List.of("buyer", "seller", "auctioneer", "warmerchant", "wanderer", "banker");
    private static final List<String> BUYER_STATUSES = List.of("default", "good", "bad", "aggressive");
    private static final List<String> ITEM_ACTIONS = List.of("allow", "deny", "rarity", "price");
    private static final List<String> EVENT_TYPES = List.of("flea", "wanderer");
    private static final List<String> FLEA_ACTIONS = List.of("start", "stop", "reset");
    private static final List<String> WANDERER_ACTIONS = List.of("start", "stop", "reset", "status");
    private static final List<String> RARITY_TIERS = List.of("common", "uncommon", "rare", "epic");

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
            case "status" -> handleStatus(sender, args);
            case "item" -> handleItem(sender, args);
            case "price" -> handlePriceCommand(sender, args);
            case "event" -> handleEvent(sender, args);
            case "banker" -> handleBanker(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean hasAnyAdminAccess(CommandSender sender) {
        return sender.hasPermission("loveshops.admin")
                || sender.hasPermission("loveshops.admin.reload")
                || sender.hasPermission("loveshops.admin.create")
                || sender.hasPermission("loveshops.admin.delete")
                || sender.hasPermission("loveshops.admin.status")
                || sender.hasPermission("loveshops.admin.seller")
                || sender.hasPermission("loveshops.admin.wanderer")
                || sender.hasPermission("loveshops.admin.price")
                || sender.hasPermission("loveshops.admin.rarity")
                || sender.hasPermission("loveshops.admin.forbidden");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("loveshops.admin.reload") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        plugin.reloadConfig();
        plugin.getLangManager().loadLang();
        plugin.getPricesManager().load();
        plugin.getForbiddenManager().load();
        sender.sendMessage(plugin.getLangManager().getMessage("commands.reload-success", "<green>Конфигурация перезагружена!</green>"));
    }

    private void handleNpc(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc <create|bind|delete|list> ...</yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> handleNpcCreate(sender, args);
            case "bind" -> handleNpcBind(sender, args);
            case "delete" -> handleNpcDelete(sender, args);
            case "list" -> handleNpcList(sender);
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc <create|bind|delete|list> ...</yellow>"));
        }
    }

    private void handleNpcCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
            return;
        }
        if (!player.hasPermission("loveshops.admin.create") && !player.hasPermission("loveshops.admin")) {
            player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc create <buyer|seller|auctioneer|warmerchant|wanderer|banker> [имя]</yellow>"));
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        if (!NPC_TYPES.contains(type)) {
            player.sendMessage(MessageUtils.parse("<red>Неверный тип NPC! Выберите: " + String.join(", ", NPC_TYPES) + "</red>"));
            return;
        }

        CitizensIntegration citizens = plugin.getCitizensIntegration();
        if (!citizens.isAvailable()) {
            player.sendMessage(MessageUtils.parse("<red>Citizens не установлен! Для создания NPC требуется Citizens.</red>"));
            return;
        }

        String finalName = args.length > 3 ? String.join(" ", List.of(args).subList(3, args.length)) : defaultNpcName(type);
        String skinOwner = defaultSkinForType(type, player.getName());

        plugin.getNpcManager().createNpc(type, finalName, player.getLocation(), skinOwner).thenAccept(npc -> {
            boolean activeNow = plugin.getNpcManager().isNpcAllowedToSpawn(npc);
            String extra = activeNow
                    ? " и заспавнен на вашей позиции!"
                    : " (скрыт, пока ивент не активен).";
            player.sendMessage(MessageUtils.parse("<green>NPC <gold>" + finalName + "</gold> (<yellow>" + type + "</yellow>) создан" + extra + "</green>"));
        }).exceptionally(ex -> {
            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            player.sendMessage(MessageUtils.parse("<red>Не удалось создать NPC: " + msg + "</red>"));
            return null;
        });
    }

    private void handleNpcBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
            return;
        }
        if (!player.hasPermission("loveshops.admin.create") && !player.hasPermission("loveshops.admin")) {
            player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc bind <buyer|seller|auctioneer|warmerchant|wanderer|banker> [имя]</yellow>"));
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        if (!NPC_TYPES.contains(type)) {
            player.sendMessage(MessageUtils.parse("<red>Неверный тип NPC! Выберите: " + String.join(", ", NPC_TYPES) + "</red>"));
            return;
        }
        String name = args.length > 3 ? String.join(" ", List.of(args).subList(3, args.length)) : null;

        CitizensIntegration citizens = plugin.getCitizensIntegration();
        if (!citizens.isAvailable()) {
            player.sendMessage(MessageUtils.parse("<red>Citizens не установлен! Для привязки требуется Citizens.</red>"));
            return;
        }

        double distance = plugin.getConfig().getDouble("npc.bind-distance", 6.0);
        CitizensIntegration.NpcRef ref = citizens.lookedAtNpc(player, distance);
        if (ref == null) {
            player.sendMessage(MessageUtils.parse("<red>Посмотрите на нужный Citizens NPC и повторите команду!</red>"));
            return;
        }

        plugin.getNpcManager().bindNpc(type, ref.id(), name, player.getName()).thenAccept(npc -> {
            boolean activeNow = plugin.getNpcManager().isNpcAllowedToSpawn(npc);
            String extra = activeNow ? "" : " <gray>(скрыт, пока ивент не активен)</gray>";
            player.sendMessage(plugin.getLangManager().getMessage("commands.npc-created",
                "<green>NPC #" + ref.id() + " («" + ref.name() + "») привязан как <gold>{type}</gold>!" + extra + "</green>",
                java.util.Map.of("type", type, "name", npc.name())));
        }).exceptionally(ex -> {
            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            player.sendMessage(MessageUtils.parse("<red>Не удалось привязать NPC: " + msg + "</red>"));
            return null;
        });
    }

    private String defaultNpcName(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "buyer" -> "Скупщик";
            case "seller" -> "Барахольщик";
            case "auctioneer" -> "Аукционист";
            case "banker" -> "Банкир";
            case "wanderer" -> "Странник";
            case "warmerchant" -> "Военный торговец";
            default -> "Торговец";
        };
    }

    private String defaultSkinForType(String type, String fallbackSkin) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "wanderer" -> plugin.getConfig().getString("wanderer.skin-owner", "Wanderer");
            default -> plugin.getConfig().getString(type + ".skin-owner",
                    plugin.getConfig().getString("npc.skins." + type, fallbackSkin));
        };
    }

    private void handleNpcDelete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
            return;
        }
        if (!player.hasPermission("loveshops.admin.delete") && !player.hasPermission("loveshops.admin")) {
            player.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }

        NpcData target = null;
        if (args.length >= 3) {
            try {
                int id = Integer.parseInt(args[2]);
                target = plugin.getNpcManager().getNpcById(id);
                if (target == null) {
                    player.sendMessage(MessageUtils.parse("<red>NPC с id " + id + " не найден.</red>"));
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc delete [id]</yellow>"));
                return;
            }
        }

        if (target == null) {
            target = resolveLookedAtNpc(player).orElse(null);
        }
        if (target == null) {
            player.sendMessage(plugin.getLangManager().getMessage("commands.npc-not-looking", "<red>Рядом не найден NPC!</red>"));
            return;
        }

        final NpcData toDelete = target;
        plugin.getNpcManager().deleteNpc(toDelete.uuid()).thenAccept(success -> {
            if (success) {
                player.sendMessage(plugin.getLangManager().getMessage("commands.npc-deleted", "<green>NPC удалён!</green>",
                    java.util.Map.of("name", toDelete.name())));
            }
        });
    }

    private Optional<NpcData> resolveLookedAtNpc(Player player) {
        CitizensIntegration citizens = plugin.getCitizensIntegration();
        if (citizens.isAvailable()) {
            double distance = plugin.getConfig().getDouble("npc.bind-distance", 6.0);
            CitizensIntegration.NpcRef ref = citizens.lookedAtNpc(player, distance);
            if (ref != null) {
                Optional<NpcData> byCitizensId = plugin.getNpcManager().getNpcByCitizensId(ref.id());
                if (byCitizensId.isPresent()) {
                    return byCitizensId;
                }
            }
        }
        return plugin.getNpcManager().getNpcNear(player.getLocation(), 4.0);
    }

    private void handleNpcList(CommandSender sender) {
        var npcs = plugin.getNpcManager().getAllNpcs();
        if (npcs.isEmpty()) {
            sender.sendMessage(MessageUtils.parse("<yellow>В базе данных нет созданных NPC.</yellow>"));
            return;
        }
        sender.sendMessage(MessageUtils.parse("<gold>=== Список NPC LoveShops (" + npcs.size() + ") ===</gold>"));
        for (var npc : npcs) {
            String locStr = String.format("%s [%.1f, %.1f, %.1f]", npc.world(), npc.x(), npc.y(), npc.z());
            String bindStr = npc.citizensId() != null ? " <gray>(Citizens #" + npc.citizensId() + ")</gray>" : " <gray>(свой Villager)</gray>";
            sender.sendMessage(MessageUtils.parse("<yellow># " + npc.id() + "</yellow> | <green>" + npc.type() + "</green> | <white>" + npc.name() + "</white> | <gray>" + locStr + "</gray>" + bindStr));
        }
    }

    private void handleStatus(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.status") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin status <игрок> <default|good|bad|aggressive> [сообщение]</yellow>"));
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
        plugin.getBuyerManager().setPlayerStatus(targetPlayer.getUniqueId(), status, sender.getName(), customMsg).thenRun(() ->
            sender.sendMessage(plugin.getLangManager().getMessage("commands.buyer-status-set", "<green>Статус установлен!</green>",
                java.util.Map.of("player", targetName, "status", status))));
    }

    private void handleBanker(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin") && !sender.hasPermission("loveshops.admin.status")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("fee")) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование:</yellow>"));
            sender.sendMessage(MessageUtils.parse("<gray>/loveshopsadmin banker fee <игрок></gray> <dark_gray>— показать комиссию</dark_gray>"));
            sender.sendMessage(MessageUtils.parse("<gray>/loveshopsadmin banker fee <игрок> <0-100></gray> <dark_gray>— задать личную комиссию</dark_gray>"));
            sender.sendMessage(MessageUtils.parse("<gray>/loveshopsadmin banker fee <игрок> reset</gray> <dark_gray>— сброс к базовой ("
                    + plugin.getBankerManager().getBaseFeePercent() + "%)</dark_gray>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Укажите игрока: /loveshopsadmin banker fee <игрок> [процент|reset]</yellow>"));
            return;
        }
        String targetName = args[2];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getUniqueId() == null) {
            sender.sendMessage(MessageUtils.parse("<red>Игрок не найден.</red>"));
            return;
        }
        UUID uuid = target.getUniqueId();
        String displayName = target.getName() != null ? target.getName() : targetName;

        if (args.length == 3) {
            int fee = plugin.getBankerManager().getFeePercent(uuid);
            boolean personal = plugin.getBankerManager().hasOverride(uuid);
            int base = plugin.getBankerManager().getBaseFeePercent();
            if (personal) {
                sender.sendMessage(MessageUtils.parse("<green>Комиссия <yellow>" + displayName + "</yellow>: <gold>"
                        + fee + "%</gold> <gray>(личная, база " + base + "%)</gray></green>"));
            } else {
                sender.sendMessage(MessageUtils.parse("<green>Комиссия <yellow>" + displayName + "</yellow>: <gold>"
                        + fee + "%</gold> <gray>(базовая)</gray></green>"));
            }
            return;
        }

        String value = args[3].toLowerCase(Locale.ROOT);
        if (value.equals("reset") || value.equals("clear") || value.equals("default")) {
            plugin.getBankerManager().clearFeePercent(uuid).thenRun(() ->
                sender.sendMessage(MessageUtils.parse("<green>Личная комиссия <yellow>" + displayName
                        + "</yellow> сброшена. Теперь базовая: <gold>"
                        + plugin.getBankerManager().getBaseFeePercent() + "%</gold>.</green>")));
            return;
        }

        int percent;
        try {
            percent = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            sender.sendMessage(MessageUtils.parse("<red>Укажите число 0–100 или reset.</red>"));
            return;
        }
        if (percent < 0 || percent > 100) {
            sender.sendMessage(MessageUtils.parse("<red>Комиссия должна быть от 0 до 100.</red>"));
            return;
        }
        plugin.getBankerManager().setFeePercent(uuid, percent, sender.getName()).thenRun(() ->
            sender.sendMessage(MessageUtils.parse("<green>Комиссия <yellow>" + displayName
                    + "</yellow> установлена: <gold>" + percent + "%</gold>.</green>")));
    }

    private void handleItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item <allow|deny|rarity|price> ...</yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "allow" -> handleItemAllow(sender);
            case "deny", "forbidden" -> handleItemDeny(sender);
            case "rarity" -> handleItemRarity(sender, args);
            case "price" -> handleItemPrice(sender, args);
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item <allow|deny|rarity|price> ...</yellow>"));
        }
    }

    @Nullable
    private ItemStack requireHeldItem(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission) && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return null;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.only-players", "<red>Только для игроков.</red>"));
            return null;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sender.sendMessage(MessageUtils.parse("<red>Держите предмет в руке!</red>"));
            return null;
        }
        return hand;
    }

    private void handleItemPrice(CommandSender sender, String[] args) {
        ItemStack hand = requireHeldItem(sender, "loveshops.admin.price");
        if (hand == null) return;
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item price [buyer|seller|war_merchant|wanderer|auctioneer|all] <цена></yellow>"));
            return;
        }

        String target = "buyer";
        int price;

        if (args.length >= 4) {
            target = args[2].toLowerCase(Locale.ROOT);
            try {
                price = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MessageUtils.parse("<red>Цена должна быть целым числом!</red>"));
                return;
            }
        } else {
            try {
                price = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MessageUtils.parse("<red>Цена должна быть целым числом! /loveshopsadmin item price [торговец|all] <цена></red>"));
                return;
            }
        }

        if (price < 0) {
            sender.sendMessage(MessageUtils.parse("<red>Цена не может быть отрицательной!</red>"));
            return;
        }

        String material = hand.getType().name();
        List<String> affected = plugin.getPricesManager().setNpcPrice(target, material, price);
        sender.sendMessage(MessageUtils.parse("<green>Цена предмета <gold>" + material + "</gold> установлена: <gold>" + price + "</gold> для: <aqua>" + String.join(", ", affected) + "</aqua></green>"));
    }

    private void handlePriceCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.price") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование команды настройки цен:</yellow>"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <buyer|seller|war_merchant|wanderer|auctioneer|all> <цена></gold> <gray>(предмет в руке)</gray>"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <buyer|seller|war_merchant|wanderer|auctioneer|all> <материал/id> <цена></gold>"));
            return;
        }

        // Если указали просто число: /loveshopsadmin price <цена> (в руке предмет, по умолчанию скупщик)
        if (args.length == 2) {
            try {
                int price = Integer.parseInt(args[1]);
                ItemStack hand = (sender instanceof Player p) ? p.getInventory().getItemInMainHand() : null;
                if (hand == null || hand.getType().isAir()) {
                    sender.sendMessage(MessageUtils.parse("<red>Возьмите предмет в руку или укажите: /loveshopsadmin price <торговец> <материал> <цена></red>"));
                    return;
                }
                String material = hand.getType().name();
                List<String> affected = plugin.getPricesManager().setNpcPrice("buyer", material, price);
                sender.sendMessage(MessageUtils.parse("<green>Цена <gold>" + material + "</gold> установлена: <gold>" + price + "</gold> для: <aqua>" + String.join(", ", affected) + "</aqua></green>"));
                return;
            } catch (NumberFormatException ignored) {}
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        String itemKey;
        int price;

        if (args.length == 3) {
            // /loveshopsadmin price <target> <цена> (предмет в руке)
            try {
                price = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MessageUtils.parse("<red>Цена должна быть числом!</red>"));
                return;
            }

            ItemStack hand = (sender instanceof Player p) ? p.getInventory().getItemInMainHand() : null;
            if (hand == null || hand.getType().isAir()) {
                sender.sendMessage(MessageUtils.parse("<red>Возьмите предмет в руку или укажите: /loveshopsadmin price " + target + " <материал/id> <цена></red>"));
                return;
            }
            itemKey = hand.getType().name();
        } else {
            // /loveshopsadmin price <target> <материал/id> <цена>
            itemKey = args[2];
            try {
                price = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MessageUtils.parse("<red>Цена должна быть числом!</red>"));
                return;
            }
        }

        if (price < 0) {
            sender.sendMessage(MessageUtils.parse("<red>Цена не может быть отрицательной!</red>"));
            return;
        }

        List<String> affected = plugin.getPricesManager().setNpcPrice(target, itemKey, price);
        if (affected.isEmpty()) {
            sender.sendMessage(MessageUtils.parse("<red>Неизвестный торговец '" + target + "'. Доступные: buyer, seller, war_merchant, wanderer, auctioneer, all</red>"));
            return;
        }

        sender.sendMessage(MessageUtils.parse("<green>Цена <gold>" + itemKey.toUpperCase(Locale.ROOT) + "</gold> установлена: <gold>" + price + "</gold> для: <aqua>" + String.join(", ", affected) + "</aqua></green>"));
    }

    private void handleItemRarity(CommandSender sender, String[] args) {
        ItemStack hand = requireHeldItem(sender, "loveshops.admin.rarity");
        if (hand == null) return;
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item rarity <common|uncommon|rare|epic></yellow>"));
            return;
        }
        ItemRarity tier;
        try {
            tier = ItemRarity.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MessageUtils.parse("<red>Неизвестная редкость! Доступные: common, uncommon, rare, epic</red>"));
            return;
        }

        ItemMeta meta = hand.getItemMeta();
        meta.setRarity(tier);
        hand.setItemMeta(meta);

        String material = hand.getType().name();
        boolean meetsThreshold = plugin.getPriceCalculator().rarityMeetsThreshold(tier);
        plugin.getPricesManager().setRareFlag(material, meetsThreshold);

        sender.sendMessage(MessageUtils.parse("<green>Редкость " + material + " установлена: <gold>" + tier.name().toLowerCase(Locale.ROOT) + "</gold>"
                + (meetsThreshold ? " <gray>(теперь уходит на аукцион)</gray>" : " <gray>(теперь НЕ уходит на аукцион)</gray>") + "</green>"));
    }

    private void handleItemDeny(CommandSender sender) {
        ItemStack hand = requireHeldItem(sender, "loveshops.admin.forbidden");
        if (hand == null) return;
        Material material = hand.getType();
        boolean added = plugin.getForbiddenManager().forbid(material);
        sender.sendMessage(MessageUtils.parse(added
                ? "<green>Предмет <gold>" + material + "</gold> запрещён к продаже!</green>"
                : "<yellow>Этот предмет уже запрещён к продаже.</yellow>"));
    }

    private void handleItemAllow(CommandSender sender) {
        ItemStack hand = requireHeldItem(sender, "loveshops.admin.forbidden");
        if (hand == null) return;
        Material material = hand.getType();
        boolean removed = plugin.getForbiddenManager().allow(material);
        sender.sendMessage(MessageUtils.parse(removed
                ? "<green>Предмет <gold>" + material + "</gold> снова разрешён к продаже!</green>"
                : "<yellow>Этот предмет не был запрещён.</yellow>"));
    }

    private void handleEvent(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event <flea|wanderer> ...</yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "flea" -> handleEventFlea(sender, args);
            case "wanderer" -> handleEventWanderer(sender, args);
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event <flea|wanderer> ...</yellow>"));
        }
    }

    private void handleEventFlea(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.seller") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event flea <start|stop|reset></yellow>"));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
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
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event flea <start|stop|reset></yellow>"));
        }
    }

    private void handleEventWanderer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.wanderer") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event wanderer <start|stop|reset|status></yellow>"));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                plugin.getWandererManager().forceStartWanderer();
                sender.sendMessage(MessageUtils.parse("<green>Странник запущен принудительно!</green>"));
            }
            case "stop" -> {
                plugin.getWandererManager().forceStopWanderer();
                sender.sendMessage(MessageUtils.parse("<red>Странник остановлен принудительно!</red>"));
            }
            case "reset" -> {
                plugin.getWandererManager().resetWandererOverride();
                sender.sendMessage(MessageUtils.parse("<yellow>Принудительный режим Странника сброшен. Используется расписание.</yellow>"));
            }
            case "status" -> {
                boolean active = plugin.getWandererManager().isWandererActive();
                Boolean override = plugin.getWandererManager().getForceActiveOverride();
                String statusStr = active ? "<green>АКТИВЕН</green>" : "<red>НЕ АКТИВЕН</red>";
                String overrideStr = override != null ? (override ? " (принудительно включён)" : " (принудительно выключен)") : " (по расписанию)";
                sender.sendMessage(MessageUtils.parse("<gold>Статус Странника: " + statusStr + "<gray>" + overrideStr + "</gray></gold>"));
            }
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event wanderer <start|stop|reset|status></yellow>"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-header", "<dark_gray>========== <gold>LoveShops Admin</gold> ==========</dark_gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-reload", "<gold>/loveshopsadmin reload</gold> <gray>- Перезагрузить конфигурацию</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-npc", "<gold>/loveshopsadmin npc <create|bind|delete|list></gold> <gray>- Управление NPC (create создаёт на вашей позиции, bind привязывает)</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-status", "<gold>/loveshopsadmin status <игрок> <статус> [сообщение]</gold> <gray>- Статус игрока у скупщика</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-item", "<gold>/loveshopsadmin item <allow|deny|rarity|price></gold> <gray>- Правила по предмету в руке</gray>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <торговец|all> <цена> [материал/id]</gold> <gray>- Настройка цены конкретному торговцу или всем</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-event-flea", "<gold>/loveshopsadmin event flea <start|stop|reset></gold> <gray>- Управление барахолкой</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-event-wanderer", "<gold>/loveshopsadmin event wanderer <start|stop|reset|status></gold> <gray>- Управление Странником</gray>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin banker fee <игрок> [0-100|reset]</gold> <gray>- Комиссия банкира игроку</gray>"));
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
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("bind"))) {
            return StringUtil.copyPartialMatches(args[2], NPC_TYPES, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("status")) {
            return StringUtil.copyPartialMatches(args[2], BUYER_STATUSES, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            return StringUtil.copyPartialMatches(args[1], ITEM_ACTIONS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("rarity")) {
            return StringUtil.copyPartialMatches(args[2], RARITY_TIERS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
            return StringUtil.copyPartialMatches(args[1], PRICE_TARGETS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("price")) {
            List<String> mats = new ArrayList<>(List.of("1", "5", "10", "25", "50", "100", "200", "500"));
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir()) mats.add(m.name().toLowerCase(Locale.ROOT));
            }
            return StringUtil.copyPartialMatches(args[2], mats, new ArrayList<>());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("price")) {
            return StringUtil.copyPartialMatches(args[3], List.of("1", "5", "10", "25", "50", "100", "200", "500"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            return StringUtil.copyPartialMatches(args[1], EVENT_TYPES, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("flea")) {
            return StringUtil.copyPartialMatches(args[2], FLEA_ACTIONS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("wanderer")) {
            return StringUtil.copyPartialMatches(args[2], WANDERER_ACTIONS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("banker")) {
            return StringUtil.copyPartialMatches(args[1], BANKER_ACTIONS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("banker") && args[1].equalsIgnoreCase("fee")) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return StringUtil.copyPartialMatches(args[2], names, new ArrayList<>());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("banker") && args[1].equalsIgnoreCase("fee")) {
            List<String> opts = new ArrayList<>(List.of("0", "5", "10", "15", "20", "25", "50", "100", "reset"));
            return StringUtil.copyPartialMatches(args[3], opts, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
