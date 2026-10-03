package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.integration.CitizensIntegration;
import dev.lovelace.loveshops.managers.PricesManager;
import dev.lovelace.loveshops.market.AdminParse;
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

    private static final List<String> SUBCOMMANDS = List.of("reload", "npc", "item", "price", "event", "banker", "auction", "point", "flea", "help");
    private static final List<String> FLEA_ACTIONS = List.of("create", "remove", "tp", "reload", "info");
    private static final List<String> PRICE_TARGETS = List.of("buyer", "seller", "war_merchant", "wanderer", "auctioneer", "all");
    // Extra spellings PricesManager#setNpcPrice already understands.
    private static final List<String> PRICE_TARGET_ALIASES = List.of("common", "war");
    private static final List<String> BANKER_ACTIONS = List.of("fee");
    private static final List<String> NPC_ACTIONS = List.of("create", "bind", "delete", "list");
    private static final List<String> NPC_TYPES = List.of("warmerchant", "wanderer", "banker", "caravaner", "commissioner", "lostcaravan");
    private static final List<String> ITEM_ACTIONS = List.of("allow", "deny", "price");
    private static final List<String> EVENT_TYPES = List.of("wanderer");
    private static final List<String> WANDERER_ACTIONS = List.of("start", "stop", "reset", "status");

    private final LoveShops plugin;
    private final MarketAdminCommands market;

    public LoveShopsAdminCommand(@NotNull LoveShops plugin) {
        this.plugin = plugin;
        this.market = new MarketAdminCommands(plugin);
    }

    /** Journal retention: called once a day by the plugin. */
    public void pruneAudit() {
        market.pruneAudit();
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

        switch (AdminParse.canonical(args[0])) {
            case "reload" -> handleReload(sender);
            case "npc" -> handleNpc(sender, args);
            case "item" -> handleItem(sender, args);
            case "price" -> handlePriceCommand(sender, args);
            case "event" -> handleEvent(sender, args);
            case "banker" -> handleBanker(sender, args);
            case "point" -> market.handlePoint(sender, args);
            case "flea" -> handleFlea(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean hasAnyAdminAccess(CommandSender sender) {
        return sender.hasPermission("loveshops.admin")
                || sender.hasPermission("loveshops.admin.reload")
                || sender.hasPermission("loveshops.admin.create")
                || sender.hasPermission("loveshops.admin.delete")
                || sender.hasPermission("loveshops.admin.wanderer")
                || sender.hasPermission("loveshops.admin.price")
                || sender.hasPermission("loveshops.admin.forbidden")
                || sender.hasPermission("loveshops.admin.market");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("loveshops.admin.reload") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        plugin.reloadConfig();
        plugin.getLangManager().loadLang();
        plugin.getMarketMessages().reload();
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
            player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc create <" + String.join("|", NPC_TYPES) + "> [имя]</yellow>"));
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
            player.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin npc bind <" + String.join("|", NPC_TYPES) + "> [имя]</yellow>"));
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
            case "banker" -> "Банкир";
            case "wanderer" -> "Странник";
            case "warmerchant" -> "Военный торговец";
            case "caravaner" -> "Караванщик";
            case "commissioner" -> "Комиссионер";
            case "lostcaravan" -> "Потерянный караван";
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
    private void handleBanker(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin")) {
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
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item <allow|deny|price> ...</yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "allow" -> handleItemAllow(sender);
            case "deny", "forbidden" -> handleItemDeny(sender);
            case "price" -> handleItemPrice(sender, args);
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin item <allow|deny|price> ...</yellow>"));
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
        // args: item price [target] <цена>  — предмет всегда берётся из руки
        applyPrice(sender, java.util.Arrays.copyOfRange(args, 2, args.length), hand.getType().name(),
                "/loveshopsadmin item price [buyer|war_merchant|wanderer|all] <цена>");
    }

    private void handlePriceCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.price") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        // get / list / reset / mult / bounds / history are the newer sub-commands; everything else is
        // the original "price [торговец] <цена> [предмет]" and keeps working unchanged.
        if (args.length > 1 && MarketAdminCommands.isPriceSub(args[1])) {
            market.handlePrice(sender, args);
            return;
        }
        String heldKey = null;
        if (sender instanceof Player p) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) heldKey = hand.getType().name();
        }
        applyPrice(sender, java.util.Arrays.copyOfRange(args, 1, args.length), heldKey,
                "/loveshopsadmin price <buyer|war_merchant|wanderer|all> <цена> [материал/id]");
    }

    /**
     * Shared parser for {@code price} and {@code item price}. Accepts, after the command word:
     * {@code <цена>}, {@code <торговец> <цена>}, {@code <торговец> <цена> <предмет>} and
     * {@code <торговец> <предмет> <цена>}. Whichever of the two trailing tokens is an integer is the
     * price, the other one is the item key; without an item key the held item is used.
     */
    private void applyPrice(CommandSender sender, String[] rest, @Nullable String heldKey, String usage) {
        if (rest.length == 0 || rest.length > 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование:</yellow>"));
            sender.sendMessage(MessageUtils.parse("<gold>" + MessageUtils.escapeTags(usage) + "</gold> <gray>(предмет в руке)</gray>"));
            sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <торговец|all> <цена> <материал/id></gold>"));
            return;
        }

        String target = "buyer";
        String[] tail = rest;
        // Leading non-numeric token is the merchant name; a lone number keeps the default (buyer).
        if (parseIntOrNull(rest[0]) == null) {
            target = rest[0].toLowerCase(Locale.ROOT);
            tail = java.util.Arrays.copyOfRange(rest, 1, rest.length);
            if (!PRICE_TARGETS.contains(target.replace('-', '_')) && !PRICE_TARGET_ALIASES.contains(target)) {
                sender.sendMessage(MessageUtils.parse("<red>Неизвестный торговец '" + MessageUtils.escapeTags(target)
                        + "'. Доступные: buyer, war_merchant, wanderer, all</red>"));
                return;
            }
        }
        if (tail.length == 0 || tail.length > 2) {
            sender.sendMessage(MessageUtils.parse("<red>Укажите цену: <gold>" + MessageUtils.escapeTags(usage) + "</gold></red>"));
            return;
        }

        Integer price = null;
        String itemKey = null;
        for (String token : tail) {
            Integer n = parseIntOrNull(token);
            if (n != null && price == null) {
                price = n;
            } else if (itemKey == null) {
                itemKey = token;
            } else {
                sender.sendMessage(MessageUtils.parse("<red>Цена должна быть целым числом!</red>"));
                return;
            }
        }
        if (price == null) {
            sender.sendMessage(MessageUtils.parse("<red>Цена должна быть целым числом! <gold>" + MessageUtils.escapeTags(usage) + "</gold></red>"));
            return;
        }
        if (price < 0) {
            sender.sendMessage(MessageUtils.parse("<red>Цена не может быть отрицательной!</red>"));
            return;
        }
        if (price > plugin.getMarketConfig().priceMax()) {
            plugin.getMarketMessages().send(sender, "admin-price-range", "max", String.valueOf(plugin.getMarketConfig().priceMax()));
            return;
        }
        if (itemKey == null) itemKey = heldKey;
        if (itemKey == null) {
            sender.sendMessage(MessageUtils.parse("<red>Возьмите предмет в руку или укажите материал/id: <gold>/loveshopsadmin price "
                    + MessageUtils.escapeTags(target) + " <цена> <материал/id></gold></red>"));
            return;
        }

        // The journal keeps the previous price when one merchant is addressed ("all" has no single old value).
        String normalized = PricesManager.normalizeTarget(target);
        String oldPrice = normalized == null ? null
                : plugin.getPricesManager().peekOverride(normalized, itemKey).map(String::valueOf).orElse(null);
        List<String> affected = plugin.getPricesManager().setNpcPrice(target, itemKey, price);
        if (affected.isEmpty()) {
            sender.sendMessage(MessageUtils.parse("<red>Неизвестный торговец '" + MessageUtils.escapeTags(target) + "'.</red>"));
            return;
        }
        market.audit(sender, normalized == null ? target.toLowerCase(Locale.ROOT) : normalized,
                itemKey.toUpperCase(Locale.ROOT), oldPrice, String.valueOf(price), "set");
        sender.sendMessage(MessageUtils.parse("<green>Цена <gold>" + MessageUtils.escapeTags(itemKey.toUpperCase(Locale.ROOT))
                + "</gold> установлена: <gold>" + price + "</gold> для: <aqua>" + String.join(", ", affected) + "</aqua></green>"));
    }

    @Nullable
    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
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
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event wanderer ...</yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "wanderer" -> handleEventWanderer(sender, args);
            default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin event wanderer ...</yellow>"));
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

    /**
     * {@code /lsa flea <create|remove|tp|reload|info>}: the flea trader is a stub, so every action
     * only reports the state. Real creation/removal is a TODO in {@code FleaTraderService}.
     */
    private void handleFlea(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin")) {
            plugin.getMarketMessages().send(sender, "cmd-flea-no-permission");
            return;
        }
        var flea = plugin.getFleaTraderService();
        String sub = args.length < 2 ? "" : AdminParse.canonical(args[1]);
        if (!FLEA_ACTIONS.contains(sub)) {
            plugin.getMarketMessages().send(sender, "cmd-flea-usage");
            return;
        }
        if (flea == null) {
            plugin.getMarketMessages().send(sender, "cmd-flea-market-off");
            return;
        }
        switch (sub) {
            case "info" -> plugin.getMarketMessages().send(sender, flea.isEnabled() ? "cmd-flea-info-on" : "cmd-flea-info-off",
                    "tax", String.valueOf(plugin.getMarketConfig().fleaTaxPercent()),
                    "items", String.valueOf(plugin.getMarketConfig().fleaItemsPerDay()));
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getMarketMessages().send(sender, flea.isEnabled() ? "cmd-flea-reloaded-on" : "cmd-flea-reloaded-off");
            }
            default -> {
                if (!flea.isEnabled()) {
                    plugin.getMarketMessages().send(sender, "cmd-flea-disabled");
                    return;
                }
                // Enabled in config, but the trader itself is not written yet: say so instead of pretending.
                plugin.getMarketMessages().send(sender, "cmd-flea-not-implemented");
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-header", "<dark_gray>========== <gold>LoveShops Admin</gold> ==========</dark_gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-reload", "<gold>/loveshopsadmin reload</gold> <gray>- Перезагрузить конфигурацию</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-npc", "<gold>/loveshopsadmin npc <create|bind|delete|list></gold> <gray>- Управление NPC (create создаёт на вашей позиции, bind привязывает)</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-item", "<gold>/loveshopsadmin item <allow|deny|price></gold> <gray>- Правила по предмету в руке</gray>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <торговец|all> <цена> [материал/id]</gold> <gray>- Настройка цены конкретному торговцу или всем</gray>"));
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-event-wanderer", "<gold>/loveshopsadmin event wanderer <start|stop|reset|status></gold> <gray>- Управление Странником</gray>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin banker fee <игрок> [0-100|reset]</gold> <gray>- Комиссия банкира игроку</gray>"));
        sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin price <get|list|reset|mult|bounds|history> ...</gold> <gray>- Просмотр, сброс, множитель, границы и журнал цен</gray>"));
        if (sender.hasPermission("loveshops.admin.market") || sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin point <list|close|open|seize|restore|robberies> ...</gold> <gray>- Торговые точки и ограбления</gray>"));
        }
        if (sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(MessageUtils.parse("<gold>/loveshopsadmin flea <create|remove|tp|reload|info></gold> <gray>- Барахольщик (заготовка, по умолчанию выключен)</gray>"));
        }
        sender.sendMessage(plugin.getLangManager().getMessage("admin.help-footer", "<dark_gray>=========================================</dark_gray>"));
    }

    private void handleCaravan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loveshops.admin.caravan") && !sender.hasPermission("loveshops.admin")) {
            sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin caravan <daily|lost> <start|stop|status></yellow>"));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        String action = args[2].toLowerCase(Locale.ROOT);

        if (sub.equals("daily")) {
            dev.lovelace.loveshops.managers.DailyCaravanManager manager = plugin.getDailyCaravanManager();
            if (manager == null) {
                sender.sendMessage(MessageUtils.parse("<red>DailyCaravanManager недоступен.</red>"));
                return;
            }
            switch (action) {
                case "start" -> {
                    boolean ok = manager.startVisit(true);
                    sender.sendMessage(MessageUtils.parse(ok ? "<green>Визит Караванщика успешно начат!</green>"
                            : "<yellow>Караванщик уже активен в городе.</yellow>"));
                }
                case "stop" -> {
                    if (manager.isCaravanerActive()) {
                        manager.endVisit("STOPPED");
                        sender.sendMessage(MessageUtils.parse("<green>Визит Караванщика принудительно завершён.</green>"));
                    } else {
                        sender.sendMessage(MessageUtils.parse("<yellow>Караванщик сейчас не активен.</yellow>"));
                    }
                }
                case "status" -> {
                    boolean act = manager.isCaravanerActive();
                    long remaining = Math.max(0, manager.getVisitDespawnAt() - (System.currentTimeMillis() / 1000));
                    sender.sendMessage(MessageUtils.parse("<gold>Караванщик: " + (act ? "<green>АКТИВЕН</green>" : "<red>НЕ АКТИВЕН</red>")
                            + (act ? " <gray>(осталось: " + (remaining / 60) + " мин, открытых ящиков: " + manager.getActiveCrates().size() + ")</gray>" : "") + "</gold>"));
                }
                default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin caravan daily <start|stop|status></yellow>"));
            }
        } else if (sub.equals("lost")) {
            dev.lovelace.loveshops.managers.LostCaravanManager manager = plugin.getLostCaravanManager();
            if (manager == null) {
                sender.sendMessage(MessageUtils.parse("<red>LostCaravanManager недоступен.</red>"));
                return;
            }
            switch (action) {
                case "start" -> {
                    if (manager.isEventActive()) {
                        sender.sendMessage(MessageUtils.parse("<yellow>Событие Потерянный Караван уже активно!</yellow>"));
                    } else {
                        manager.announceSession(System.currentTimeMillis() / 1000);
                        sender.sendMessage(MessageUtils.parse("<green>Событие Потерянный Караван успешно начато (фаза регистрации)!</green>"));
                    }
                }
                case "stop" -> {
                    if (manager.isEventActive()) {
                        manager.closeSession();
                        sender.sendMessage(MessageUtils.parse("<green>Событие Потерянный Караван принудительно остановлено.</green>"));
                    } else {
                        sender.sendMessage(MessageUtils.parse("<yellow>Потерянный Караван сейчас не активен.</yellow>"));
                    }
                }
                case "status" -> {
                    boolean act = manager.isEventActive();
                    var session = manager.getCurrentSession();
                    sender.sendMessage(MessageUtils.parse("<gold>Потерянный Караван: " + (act ? "<green>АКТИВЕН</green>" : "<red>НЕ АКТИВЕН</red>")
                            + (session != null ? " <gray>(статус: " + session.status() + ", режим: " + session.mode() + ", участников: " + session.participantCount() + ")</gray>" : "") + "</gold>"));
                }
                default -> sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin caravan lost <start|stop|status></yellow>"));
            }
        } else {
            sender.sendMessage(MessageUtils.parse("<yellow>Использование: /loveshopsadmin caravan <daily|lost> <start|stop|status></yellow>"));
        }
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!hasAnyAdminAccess(sender)) return Collections.emptyList();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        String first = AdminParse.canonical(args[0]);
        if (first.equals("point") && (sender.hasPermission("loveshops.admin.market") || sender.hasPermission("loveshops.admin"))) {
            return market.tabPoint(args);
        }
        if (first.equals("flea") && args.length == 2 && sender.hasPermission("loveshops.admin")) {
            return StringUtil.copyPartialMatches(args[1], FLEA_ACTIONS, new ArrayList<>());
        }
        if (first.equals("price") && args.length >= 2 && MarketAdminCommands.isPriceSub(args[1])
                && (sender.hasPermission("loveshops.admin.price") || sender.hasPermission("loveshops.admin"))) {
            return market.tabPrice(args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            return StringUtil.copyPartialMatches(args[1], NPC_ACTIONS, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("bind"))) {
            return StringUtil.copyPartialMatches(args[2], NPC_TYPES, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            return StringUtil.copyPartialMatches(args[1], ITEM_ACTIONS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
            List<String> opts = new ArrayList<>(PRICE_TARGETS);
            opts.addAll(MarketAdminCommands.PRICE_SUBS);
            return StringUtil.copyPartialMatches(args[1], opts, new ArrayList<>());
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
        if (args.length == 2 && args[0].equalsIgnoreCase("caravan")) {
            return StringUtil.copyPartialMatches(args[1], List.of("daily", "lost"), new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("caravan")) {
            return StringUtil.copyPartialMatches(args[2], List.of("start", "stop", "status"), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
