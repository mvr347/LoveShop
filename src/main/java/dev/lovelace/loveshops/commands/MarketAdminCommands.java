package dev.lovelace.loveshops.commands;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.AuctionManager;
import dev.lovelace.loveshops.managers.PricesManager;
import dev.lovelace.loveshops.market.AdminParse;
import dev.lovelace.loveshops.market.MarketRepository;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.models.AuctionData;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrator commands of the player market and of price control, mounted under
 * {@code /loveshopsadmin}: {@code price get|list|reset|mult|bounds|history},
 * {@code auction ...} and {@code point ...}. Permissions are checked before any
 * database access; nothing here blocks the main thread beyond a short indexed query, and the
 * heavier lot changes run on the async scheduler and report back on the main thread.
 */
public final class MarketAdminCommands {

    private static final int PAGE = 10;
    private static final long RETENTION_MILLIS = 90L * 86_400_000L;

    public static final List<String> PRICE_SUBS = List.of("get", "list", "reset", "mult", "bounds", "history");
    private static final List<String> AUCTION_SUBS = List.of("list", "create", "price", "buyout", "extend", "end", "cancel", "step");
    private static final List<String> POINT_SUBS = List.of(
            "list", "close", "open", "seize", "restore", "robberies",
            "settrader", "setclosedsign", "clearclosedsign", "transfer", "info"
    );

    private final LoveShops plugin;
    private final MarketRepository repo;

    public MarketAdminCommands(LoveShops plugin) {
        this.plugin = plugin;
        this.repo = new MarketRepository(plugin);
    }

    // ------------------------------------------------------------------ plumbing

    private void msg(CommandSender to, String key, String... kv) {
        plugin.getMarketMessages().send(to, key, kv);
    }

    private static String esc(String text) {
        return text == null ? "" : MessageUtils.escapeTags(text);
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("loveshops.admin")) return true;
        sender.sendMessage(plugin.getLangManager().getMessage("commands.no-permission", "<red>У вас нет прав!</red>"));
        return false;
    }

    private static int pageArg(String[] args, int index) {
        if (args.length <= index) return 1;
        Long p = AdminParse.amount(args[index], 100_000);
        return p == null ? 1 : p.intValue();
    }

    private static String[] tail(String[] args, int from) {
        return from >= args.length ? new String[0] : Arrays.copyOfRange(args, from, args.length);
    }

    private static String joinFrom(String[] args, int from) {
        return String.join(" ", tail(args, from)).trim();
    }

    /** Finds a player by name without any network lookup: online first, then the server's own cache. */
    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached;
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) return "-";
        String n = Bukkit.getOfflinePlayer(uuid).getName();
        return n == null ? uuid.toString().substring(0, 8) : n;
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    /** Runs a task on the main thread (list results arrive from the async scheduler). */
    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(plugin, r);
    }

    // ------------------------------------------------------------------ audit

    /** Writes one line of the price journal off the main thread. */
    public void audit(CommandSender sender, String target, String item, String oldValue, String newValue, String kind) {
        UUID admin = uuidOf(sender);
        String adminName = sender.getName();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                repo.addPriceChange(admin, adminName, target, item, oldValue, newValue, kind);
            } catch (SQLException e) {
                plugin.getLogger().warning("Журнал цен: запись не сохранена: " + e.getMessage());
            }
        });
    }

    /** Drops journal lines older than 90 days; called once a day. */
    public void pruneAudit() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                int n = repo.prunePriceChanges(System.currentTimeMillis() - RETENTION_MILLIS);
                if (n > 0) plugin.getLogger().info("Журнал цен: удалено записей старше 90 дней: " + n);
            } catch (SQLException e) {
                plugin.getLogger().warning("Журнал цен: очистка не удалась: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ price

    /** Whether {@code word} (already canonical) is a new price sub-command rather than the old {@code price <target> <price>}. */
    public static boolean isPriceSub(String word) {
        return PRICE_SUBS.contains(AdminParse.canonical(word));
    }

    private boolean knownItem(String target, String item) {
        if (Material.matchMaterial(item) != null) return true;
        // Custom ids (Wanderer goods and the like) have no Material: accept one that already has a price.
        return "wanderer".equals(target) && plugin.getPricesManager().peekOverride(target, item).isPresent();
    }

    public void handlePrice(CommandSender sender, String[] args) {
        if (!allowed(sender, "loveshops.admin.price")) return;
        String sub = args.length > 1 ? AdminParse.canonical(args[1]) : "";
        switch (sub) {
            case "get" -> priceGet(sender, args);
            case "list" -> priceList(sender, args);
            case "reset" -> priceReset(sender, args);
            case "mult" -> priceMult(sender, args);
            case "bounds" -> priceBounds(sender, args);
            case "history" -> priceHistory(sender, args);
            default -> msg(sender, "admin-price-usage");
        }
    }

    private String targetOrError(CommandSender sender, String raw) {
        String target = PricesManager.normalizeTarget(raw);
        if (target == null) msg(sender, "admin-price-bad-target", "targets", String.join(", ", PricesManager.TARGETS));
        return target;
    }

    private void priceGet(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-price-get-usage"); return; }
        String target = targetOrError(sender, args[2]);
        if (target == null) return;
        String item = args[3];
        if (!knownItem(target, item)) { msg(sender, "admin-item-unknown", "item", esc(item)); return; }
        PricesManager prices = plugin.getPricesManager();
        Optional<Integer> override = prices.peekOverride(target, item);
        String source;
        String price;
        if (override.isPresent()) {
            price = String.valueOf(override.get());
            source = "prices.yml";
        } else if ("buyer".equals(target)) {
            price = String.valueOf(plugin.getConfig().getInt("buyer.base-price-config.default-price", 1));
            source = "по умолчанию";
        } else {
            price = "-";
            source = "нет переопределения";
        }
        msg(sender, "admin-price-get", "item", esc(item.toUpperCase(Locale.ROOT)), "target", target, "price", price, "source", source);
        PricesManager.Bounds b = prices.getBounds(item);
        msg(sender, "admin-price-get-extra", "mult", formatPercent(prices.getMultiplierPercent(target)),
                "min", b.min() > 0 ? String.valueOf(b.min()) : "-", "max", b.max() > 0 ? String.valueOf(b.max()) : "-");
    }

    private void priceList(CommandSender sender, String[] args) {
        if (args.length < 3) { msg(sender, "admin-price-list-usage"); return; }
        String target = targetOrError(sender, args[2]);
        if (target == null) return;
        var all = plugin.getPricesManager().listOverrides(target);
        int page = pageArg(args, 3);
        printPage(sender, "admin-price-list-header", all.size(), page, "target", target);
        for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
            var e = all.get(i);
            msg(sender, "admin-price-list-line", "item", esc(e.getKey()), "price", String.valueOf(e.getValue()));
        }
    }

    private void printPage(CommandSender sender, String headerKey, int total, int page, String... kv) {
        int pages = Math.max(1, (total + PAGE - 1) / PAGE);
        String[] all = Arrays.copyOf(kv, kv.length + 6);
        all[kv.length] = "page";
        all[kv.length + 1] = String.valueOf(Math.min(page, pages));
        all[kv.length + 2] = "pages";
        all[kv.length + 3] = String.valueOf(pages);
        all[kv.length + 4] = "total";
        all[kv.length + 5] = String.valueOf(total);
        msg(sender, headerKey, all);
        if (total == 0) msg(sender, "admin-empty");
    }

    private void priceReset(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-price-reset-usage"); return; }
        String target = targetOrError(sender, args[2]);
        if (target == null) return;
        String item = args[3];
        if (!knownItem(target, item)) { msg(sender, "admin-item-unknown", "item", esc(item)); return; }
        PricesManager prices = plugin.getPricesManager();
        Optional<Integer> old = prices.peekOverride(target, item);
        if (!prices.resetOverride(target, item)) {
            msg(sender, "admin-price-reset-none", "item", esc(item.toUpperCase(Locale.ROOT)), "target", target);
            return;
        }
        audit(sender, target, item.toUpperCase(Locale.ROOT), old.map(String::valueOf).orElse(null), null, "reset");
        msg(sender, "admin-price-reset", "item", esc(item.toUpperCase(Locale.ROOT)), "target", target);
    }

    private void priceMult(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-price-mult-usage"); return; }
        String target = targetOrError(sender, args[2]);
        if (target == null) return;
        // Only these two are priced through PriceCalculator, where the multiplier is applied.
        if (!target.equals("buyer") && !target.equals("seller")) {
            msg(sender, "admin-price-mult-unsupported", "target", target);
            return;
        }
        Double percent = "reset".equalsIgnoreCase(args[3]) || "сброс".equalsIgnoreCase(args[3]) ? Double.valueOf(0.0) : AdminParse.percent(args[3]);
        if (percent == null) { msg(sender, "admin-price-mult-usage"); return; }
        if (!AdminParse.multiplierInRange(percent)) {
            msg(sender, "admin-price-mult-range", "min", formatPercent(AdminParse.MULT_MIN), "max", formatPercent(AdminParse.MULT_MAX));
            return;
        }
        PricesManager prices = plugin.getPricesManager();
        double old = prices.getMultiplierPercent(target);
        prices.setMultiplierPercent(target, percent);
        audit(sender, target, "*", formatPercent(old), formatPercent(percent), "mult");
        msg(sender, "admin-price-mult", "target", target, "old", formatPercent(old), "new", formatPercent(percent));
    }

    private static String formatPercent(double v) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
        return (v > 0 ? "+" : "") + s + "%";
    }

    private void priceBounds(CommandSender sender, String[] args) {
        if (args.length < 3) { msg(sender, "admin-price-bounds-usage"); return; }
        PricesManager prices = plugin.getPricesManager();
        if (AdminParse.canonical(args[2]).equals("list")) {
            List<Map.Entry<String, PricesManager.Bounds>> all = new ArrayList<>(prices.listBounds().entrySet());
            int page = pageArg(args, 3);
            printPage(sender, "admin-price-bounds-header", all.size(), page);
            for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
                var e = all.get(i);
                msg(sender, "admin-price-bounds-line", "item", esc(e.getKey()),
                        "min", e.getValue().min() > 0 ? String.valueOf(e.getValue().min()) : "-",
                        "max", e.getValue().max() > 0 ? String.valueOf(e.getValue().max()) : "-");
            }
            return;
        }
        Material material = Material.matchMaterial(args[2]);
        if (material == null) { msg(sender, "admin-item-unknown", "item", esc(args[2])); return; }
        String key = material.name();
        PricesManager.Bounds old = prices.getBounds(key);
        if (args.length >= 4 && (args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("выкл"))) {
            prices.setBounds(key, 0, 0);
            audit(sender, "market", key, boundsText(old), null, "bounds");
            msg(sender, "admin-price-bounds-off", "item", key);
            return;
        }
        if (args.length < 5) { msg(sender, "admin-price-bounds-usage"); return; }
        long cap = plugin.getMarketConfig().priceMax();
        Long min = AdminParse.amountOrZero(args[3], cap);
        Long max = AdminParse.amountOrZero(args[4], cap);
        if (min == null || max == null) { msg(sender, "admin-price-bounds-range", "max", String.valueOf(cap)); return; }
        if (min == 0 && max == 0) { msg(sender, "admin-price-bounds-usage"); return; }
        if (min > 0 && max > 0 && min > max) { msg(sender, "admin-price-bounds-order"); return; }
        prices.setBounds(key, min, max);
        audit(sender, "market", key, boundsText(old), boundsText(new PricesManager.Bounds(min, max)), "bounds");
        msg(sender, "admin-price-bounds-set", "item", key, "min", min > 0 ? String.valueOf(min) : "-", "max", max > 0 ? String.valueOf(max) : "-");
    }

    private static String boundsText(PricesManager.Bounds b) {
        return b.isSet() ? b.min() + ".." + b.max() : null;
    }

    private void priceHistory(CommandSender sender, String[] args) {
        String item = null;
        int pageIndex = 2;
        if (args.length > 2 && AdminParse.amount(args[2], 100_000) == null) {
            item = args[2].toUpperCase(Locale.ROOT);
            pageIndex = 3;
        }
        int page = pageArg(args, pageIndex);
        final String filter = item;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                int total = repo.priceChangeCount(filter);
                List<MarketRepository.PriceChange> rows = repo.priceChanges(filter, (page - 1) * PAGE, PAGE);
                sync(() -> {
                    printPage(sender, "admin-price-history-header", total, page);
                    for (var c : rows) {
                        msg(sender, "admin-price-history-line", "when", ago(c.createdAt()), "admin", esc(c.adminName()),
                                "kind", esc(c.kind()), "target", esc(c.target()), "item", esc(c.item()),
                                "old", c.oldValue() == null ? "-" : esc(c.oldValue()), "new", c.newValue() == null ? "-" : esc(c.newValue()));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("Журнал цен не прочитан: " + e.getMessage());
                sync(() -> msg(sender, "admin-db-error"));
            }
        });
    }

    private static String ago(long millis) {
        return AdminParse.duration(Math.max(0, (System.currentTimeMillis() - millis) / 1000)) + " назад";
    }

    // ------------------------------------------------------------------ auction

    public void handleAuction(CommandSender sender, String[] args) {
        if (!allowed(sender, "loveshops.admin.auction")) return;
        String sub = args.length > 1 ? AdminParse.canonical(args[1]) : "";
        switch (sub) {
            case "list" -> auctionList(sender, args);
            case "create" -> auctionCreate(sender, args);
            case "price" -> auctionPrice(sender, args);
            case "buyout" -> auctionBuyout(sender, args);
            case "extend" -> auctionExtend(sender, args);
            case "end" -> auctionEnd(sender, args);
            case "cancel" -> auctionCancel(sender, args);
            case "step" -> auctionStep(sender, args);
            default -> msg(sender, "admin-auction-usage");
        }
    }

    private Integer auctionId(CommandSender sender, String raw) {
        Long id = AdminParse.amount(raw, Integer.MAX_VALUE);
        if (id == null) msg(sender, "admin-bad-id");
        return id == null ? null : id.intValue();
    }

    private void auctionList(CommandSender sender, String[] args) {
        int page = pageArg(args, 2);
        plugin.getAuctionManager().getActiveAuctions().thenAccept(all -> sync(() -> {
            printPage(sender, "admin-auction-list-header", all.size(), page);
            long now = System.currentTimeMillis() / 1000;
            for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
                AuctionData a = all.get(i);
                ItemStack item = ItemStackConverter.itemStackFromBase64(a.itemData());
                String name = item == null ? "?" : item.getType().name() + (item.getAmount() > 1 ? " x" + item.getAmount() : "");
                msg(sender, "admin-auction-list-line", "id", String.valueOf(a.id()), "item", esc(name),
                        "start", String.valueOf(a.startingPrice()), "bid", String.valueOf(a.currentHighestBid()),
                        "leader", a.highestBidderUuid() == null ? "-" : esc(nameOf(a.highestBidderUuid())),
                        "buyout", a.buyoutPrice() > 0 ? String.valueOf(a.buyoutPrice()) : "-",
                        "left", AdminParse.duration(a.endsAt() - now));
            }
        }));
    }

    private void auctionCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { msg(sender, "admin-player-only"); return; }
        if (args.length < 3) { msg(sender, "admin-auction-create-usage"); return; }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { msg(sender, "admin-need-item"); return; }
        long cap = Math.min(plugin.getMarketConfig().priceMax(), Integer.MAX_VALUE);
        Long start = AdminParse.amount(args[2], cap);
        if (start == null) { msg(sender, "admin-price-range", "max", String.valueOf(cap)); return; }
        Integer buyout = null;
        Integer hours = null;
        if (args.length > 3) {
            if (args[3].equalsIgnoreCase("off") || args[3].equals("0")) {
                buyout = 0;
            } else {
                Long b = AdminParse.amount(args[3], cap);
                if (b == null) { msg(sender, "admin-price-range", "max", String.valueOf(cap)); return; }
                buyout = b.intValue();
            }
        }
        if (args.length > 4) {
            Long h = AdminParse.amount(args[4], AdminParse.MAX_LOT_HOURS);
            if (h == null) { msg(sender, "admin-hours-range", "max", String.valueOf(AdminParse.MAX_LOT_HOURS)); return; }
            hours = h.intValue();
        }
        if (buyout != null && buyout > 0 && buyout <= start) { msg(sender, "admin-buyout-low"); return; }
        if (!withinBounds(sender, hand.getType(), start)) return;
        if (plugin.getForbiddenManager().isForbidden(hand)) { msg(sender, "listing-forbidden"); return; }

        ItemStack taken = hand.clone();
        player.getInventory().setItemInMainHand(null);
        final Integer fBuyout = buyout;
        final Integer fHours = hours;
        plugin.getAuctionManager().createAuction(taken, start.intValue(), fBuyout, fHours).whenComplete((id, err) -> sync(() -> {
            if (err != null || id == null || id <= 0) {
                giveBack(player, taken);
                msg(sender, "admin-db-error");
                return;
            }
            audit(sender, "auction", "auction#" + id, null, taken.getType().name() + " " + start, "create");
            msg(sender, "admin-auction-created", "id", String.valueOf(id), "item", taken.getType().name());
            dev.lovelace.loveshops.gui.GuiUpdater.broadcastAuctionGuiUpdate(plugin);
        }));
    }

    /** Auction start prices obey the same admin bounds as the players' markets. */
    private boolean withinBounds(CommandSender sender, Material material, long price) {
        PricesManager.Bounds b = plugin.getPricesManager().getBounds(material.name());
        if ((b.min() > 0 && price < b.min()) || (b.max() > 0 && price > b.max())) {
            msg(sender, "admin-price-outside-bounds", "item", material.name(),
                    "min", b.min() > 0 ? String.valueOf(b.min()) : "-", "max", b.max() > 0 ? String.valueOf(b.max()) : "-");
            return false;
        }
        return true;
    }

    private void giveBack(Player player, ItemStack item) {
        int left = dev.lovelace.loveshops.market.ItemTransfer.give(player, item, item.getAmount());
        if (left > 0) {
            ItemStack drop = item.clone();
            drop.setAmount(left);
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private void auctionPrice(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-auction-price-usage"); return; }
        Integer id = auctionId(sender, args[2]);
        if (id == null) return;
        long cap = Math.min(plugin.getMarketConfig().priceMax(), Integer.MAX_VALUE);
        Long price = AdminParse.amount(args[3], cap);
        if (price == null) { msg(sender, "admin-price-range", "max", String.valueOf(cap)); return; }
        AuctionManager auctions = plugin.getAuctionManager();
        auctions.getActiveAuctions().thenAccept(all -> sync(() -> {
            AuctionData lot = all.stream().filter(a -> a.id() == id).findFirst().orElse(null);
            if (lot == null) { msg(sender, "admin-auction-not-found", "id", String.valueOf(id)); return; }
            ItemStack item = ItemStackConverter.itemStackFromBase64(lot.itemData());
            if (item != null && !withinBounds(sender, item.getType(), price)) return;
            auctions.adminSetStartPrice(id, price.intValue()).thenAccept(r -> sync(() -> {
                report(sender, r, id);
                if (r == AuctionManager.AdminResult.OK) {
                    audit(sender, "auction", "auction#" + id, String.valueOf(lot.startingPrice()), String.valueOf(price), "auction");
                    msg(sender, "admin-auction-price", "id", String.valueOf(id), "price", String.valueOf(price));
                }
            }));
        }));
    }

    private void auctionBuyout(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-auction-buyout-usage"); return; }
        Integer id = auctionId(sender, args[2]);
        if (id == null) return;
        long cap = Math.min(plugin.getMarketConfig().priceMax(), Integer.MAX_VALUE);
        boolean off = args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("выкл");
        Long price = off ? Long.valueOf(0) : AdminParse.amount(args[3], cap);
        if (price == null) { msg(sender, "admin-price-range", "max", String.valueOf(cap)); return; }
        plugin.getAuctionManager().adminSetBuyout(id, price.intValue()).thenAccept(r -> sync(() -> {
            report(sender, r, id);
            if (r == AuctionManager.AdminResult.OK) {
                audit(sender, "auction", "auction#" + id, null, off ? "off" : String.valueOf(price), "auction");
                msg(sender, off ? "admin-auction-buyout-off" : "admin-auction-buyout", "id", String.valueOf(id), "price", String.valueOf(price));
            }
        }));
    }

    private void auctionExtend(CommandSender sender, String[] args) {
        if (args.length < 4) { msg(sender, "admin-auction-extend-usage"); return; }
        Integer id = auctionId(sender, args[2]);
        if (id == null) return;
        Long hours = AdminParse.amount(args[3], Integer.MAX_VALUE);
        if (hours == null) { msg(sender, "admin-hours-range", "max", String.valueOf(plugin.getConfig().getInt("auctioneer.max-extension-hours", 72))); return; }
        plugin.getAuctionManager().adminExtend(id, hours.intValue()).thenAccept(r -> sync(() -> {
            report(sender, r, id);
            if (r == AuctionManager.AdminResult.OK) {
                audit(sender, "auction", "auction#" + id, null, "+" + hours + "h", "auction");
                msg(sender, "admin-auction-extended", "id", String.valueOf(id), "hours", String.valueOf(hours));
            }
        }));
    }

    private void auctionEnd(CommandSender sender, String[] args) {
        if (args.length < 3) { msg(sender, "admin-auction-end-usage"); return; }
        Integer id = auctionId(sender, args[2]);
        if (id == null) return;
        plugin.getAuctionManager().adminEndNow(id).thenAccept(r -> sync(() -> {
            report(sender, r, id);
            if (r == AuctionManager.AdminResult.OK) {
                audit(sender, "auction", "auction#" + id, null, "ended", "auction");
                msg(sender, "admin-auction-ended", "id", String.valueOf(id));
            }
        }));
    }

    private void auctionCancel(CommandSender sender, String[] args) {
        if (args.length < 3) { msg(sender, "admin-auction-cancel-usage"); return; }
        Integer id = auctionId(sender, args[2]);
        if (id == null) return;
        String reason = joinFrom(args, 3);
        plugin.getAuctionManager().adminCancel(id).thenAccept(out -> sync(() -> {
            report(sender, out.result(), id);
            if (out.result() != AuctionManager.AdminResult.OK) return;
            audit(sender, "auction", "auction#" + id, out.item() == null ? null : out.item().getType().name(),
                    "cancelled" + (reason.isEmpty() ? "" : ": " + reason), "auction");
            msg(sender, "admin-auction-cancelled", "id", String.valueOf(id));
            // The lot's goods come back to whoever cancelled it; nobody was charged yet.
            ItemStack item = out.item();
            if (item != null) {
                if (sender instanceof Player p) {
                    giveBack(p, item);
                    msg(sender, "admin-auction-item-returned", "item", item.getType().name(), "amount", String.valueOf(item.getAmount()));
                } else {
                    plugin.getLogger().warning("Лот #" + id + " отменён из консоли, предмет " + item.getType() + " x" + item.getAmount()
                            + " не выдан: " + ItemStackConverter.itemStackToBase64(item));
                    msg(sender, "admin-auction-item-logged");
                }
            }
            UUID bidder = out.bidder();
            if (bidder != null) {
                var manager = plugin.getTradePointManager();
                String text = plugin.getMarketMessages().raw("notice-auction-cancelled", "id", String.valueOf(id),
                        "reason", reason.isEmpty() ? "-" : esc(reason));
                Player online = Bukkit.getPlayer(bidder);
                if (online != null) {
                    online.sendMessage(MessageUtils.parse(online, plugin.getLangManager().getRaw("prefix", "") + text));
                } else if (manager != null) {
                    manager.notice(bidder, text);
                }
            }
        }));
    }

    private void auctionStep(CommandSender sender, String[] args) {
        if (args.length < 3) { msg(sender, "admin-auction-step-usage"); return; }
        PricesManager prices = plugin.getPricesManager();
        String old = prices.getBidStep().map(s -> s.type().equals("percentage") ? s.value() + "%" : String.valueOf(s.value()))
                .orElse("config.yml");
        if (args[2].equalsIgnoreCase("reset") || args[2].equalsIgnoreCase("сброс")) {
            prices.clearBidStep();
            audit(sender, "auction", "bid-step", old, "config.yml", "auction");
            msg(sender, "admin-auction-step-reset");
            return;
        }
        AdminParse.Step step = AdminParse.step(args[2], (int) Math.min(plugin.getMarketConfig().priceMax(), Integer.MAX_VALUE));
        if (step == null) { msg(sender, "admin-auction-step-usage"); return; }
        prices.setBidStep(step.type(), step.value());
        String text = step.type().equals("percentage") ? step.value() + "%" : String.valueOf(step.value());
        audit(sender, "auction", "bid-step", old, text, "auction");
        msg(sender, "admin-auction-step", "step", text);
    }

    private void report(CommandSender sender, AuctionManager.AdminResult r, int id) {
        switch (r) {
            case OK -> { }
            case NOT_FOUND -> msg(sender, "admin-auction-not-found", "id", String.valueOf(id));
            case HAS_BIDS -> msg(sender, "admin-auction-has-bids", "id", String.valueOf(id));
            case BAD_VALUE -> msg(sender, "admin-auction-bad-value");
            case TOO_LONG -> msg(sender, "admin-hours-range", "max", String.valueOf(plugin.getConfig().getInt("auctioneer.max-extension-hours", 72)));
            case DB_ERROR -> msg(sender, "admin-db-error");
        }
    }

    // ------------------------------------------------------------------ trade points

    public void handlePoint(CommandSender sender, String[] args) {
        if (!allowed(sender, "loveshops.admin.point") && !allowed(sender, "loveshops.admin.market")) return;
        TradePointManager manager = plugin.getTradePointManager();
        if (manager == null) { msg(sender, "admin-market-off"); return; }
        String sub = args.length > 1 ? AdminParse.canonical(args[1]) : "";
        switch (sub) {
            case "list" -> pointList(sender, manager, args);
            case "close" -> pointClose(sender, manager, args);
            case "open" -> pointOpen(sender, manager, args);
            case "seize" -> pointSeize(sender, manager, args);
            case "restore" -> pointRestore(sender, manager, args);
            case "robberies" -> pointRobberies(sender, args);
            case "settrader" -> pointSetTrader(sender, manager, args);
            case "setclosedsign" -> pointSetClosedSign(sender, manager, args);
            case "clearclosedsign" -> pointClearClosedSign(sender, manager, args);
            case "transfer" -> pointTransfer(sender, manager, args);
            case "info" -> pointInfo(sender, manager, args);
            default -> msg(sender, "admin-point-usage");
        }
    }

    /** A point by its owner's name or by the claim id. */
    private TradePoint findPoint(CommandSender sender, TradePointManager manager, String raw) {
        try {
            var byId = manager.byClaim(UUID.fromString(raw));
            if (byId.isPresent()) return byId.get();
        } catch (IllegalArgumentException ignored) {
            // not a claim id: treat it as a name
        }
        for (TradePoint p : manager.all()) {
            if (p.hasOwner() && raw.equalsIgnoreCase(p.ownerName())) return p;
        }
        OfflinePlayer op = findPlayer(raw);
        if (op != null) {
            var byOwner = manager.byOwner(op.getUniqueId());
            if (byOwner.isPresent()) return byOwner.get();
        }
        msg(sender, "admin-point-not-found", "point", esc(raw));
        return null;
    }

    private void pointList(CommandSender sender, TradePointManager manager, String[] args) {
        List<TradePoint> all = new ArrayList<>(manager.all());
        all.removeIf(p -> !p.hasOwner());
        all.sort((a, b) -> String.valueOf(a.ownerName()).compareToIgnoreCase(String.valueOf(b.ownerName())));
        int page = pageArg(args, 2);
        printPage(sender, "admin-point-list-header", all.size(), page);
        for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
            TradePoint p = all.get(i);
            msg(sender, "admin-point-list-line", "owner", esc(p.ownerName()), "id", p.claimId().toString().substring(0, 8),
                    "state", p.open() ? "открыта" : "закрыта (" + (p.closeReason() == null ? "?" : p.closeReason().name()) + ")",
                    "level", String.valueOf(p.level()), "till", String.valueOf(p.tillCoins()));
        }
    }

    private void pointClose(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3) { msg(sender, "admin-point-close-usage"); return; }
        TradePoint p = findPoint(sender, manager, args[2]);
        if (p == null) return;
        String reason = joinFrom(args, 3);
        manager.adminClose(p, esc(reason));
        audit(sender, "point", esc(String.valueOf(p.ownerName())), null, "closed" + (reason.isEmpty() ? "" : ": " + reason), "point");
        msg(sender, "admin-point-closed", "owner", esc(p.ownerName()));
    }

    private void pointOpen(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3) { msg(sender, "admin-point-open-usage"); return; }
        TradePoint p = findPoint(sender, manager, args[2]);
        if (p == null) return;
        if (!manager.adminOpen(p)) { msg(sender, "admin-point-already-open", "owner", esc(p.ownerName())); return; }
        audit(sender, "point", esc(String.valueOf(p.ownerName())), null, "opened", "point");
        msg(sender, "admin-point-opened", "owner", esc(p.ownerName()));
    }

    private void pointSeize(CommandSender sender, TradePointManager manager, String[] args) {
        if (!(sender instanceof Player admin)) { msg(sender, "admin-player-only"); return; }
        if (args.length < 3) { msg(sender, "admin-point-seize-usage"); return; }
        TradePoint p = findPoint(sender, manager, args[2]);
        if (p == null) return;
        String reason = joinFrom(args, 3);
        TradePointManager.SeizeResult r = manager.adminSeize(p, admin.getUniqueId(), esc(reason));
        if (r == null) { msg(sender, "admin-point-seize-failed"); return; }
        audit(sender, "point", esc(String.valueOf(p.ownerName())), null,
                "seized " + r.items() + " items, " + r.coins() + " coins" + (reason.isEmpty() ? "" : ": " + reason), "point");
        msg(sender, "admin-point-seized", "owner", esc(p.ownerName()), "items", String.valueOf(r.items()),
                "coins", String.valueOf(r.coins()));
        manager.claimReturns(admin);
    }

    private void pointRestore(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 3) { msg(sender, "admin-point-restore-usage"); return; }
        Long id = AdminParse.amount(args[2], Long.MAX_VALUE);
        if (id == null) { msg(sender, "admin-bad-id"); return; }
        TradePointManager.RestoreResult r = manager.adminRestoreRobbery(id);
        switch (r.status()) {
            case NOT_FOUND -> msg(sender, "admin-robbery-not-found", "id", String.valueOf(id));
            case ALREADY_RESTORED -> msg(sender, "admin-robbery-already", "id", String.valueOf(id));
            case ERROR -> msg(sender, "admin-db-error");
            case OK -> {
                audit(sender, "point", "robbery#" + id, null, "restored " + r.stacks() + " stacks, " + r.coins() + " coins", "point");
                msg(sender, "admin-robbery-restored", "id", String.valueOf(id), "stacks", String.valueOf(r.stacks()),
                        "coins", String.valueOf(r.coins()));
            }
        }
    }

    private void pointRobberies(CommandSender sender, String[] args) {
        int page = pageArg(args, 2);
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<MarketRepository.RobberyLog> rows = repo.recentRobberies((page - 1) * PAGE, PAGE);
                sync(() -> {
                    msg(sender, "admin-robberies-header", "page", String.valueOf(page));
                    if (rows.isEmpty()) msg(sender, "admin-empty");
                    for (var l : rows) {
                        msg(sender, "admin-robberies-line", "id", String.valueOf(l.id()), "robber", esc(nameOf(l.robber())),
                                "owner", esc(nameOf(l.owner())), "coins", String.valueOf(l.coins()),
                                "stacks", String.valueOf(dev.lovelace.loveshops.market.RobberyLoot.parse(l.itemsJson()).size()),
                                "when", ago(l.createdAt()), "restored", l.restored() ? "да" : "нет");
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("Журнал ограблений не прочитан: " + e.getMessage());
                sync(() -> msg(sender, "admin-db-error"));
            }
        });
    }

    private TradePoint findPointOrAtLocation(CommandSender sender, TradePointManager manager, String[] args, int index) {
        if (args.length > index) {
            return findPoint(sender, manager, args[index]);
        }
        if (sender instanceof Player p) {
            var atLoc = manager.pointAt(p.getLocation());
            if (atLoc.isPresent()) return atLoc.get();
        }
        msg(sender, "admin-point-not-found", "point", "-");
        return null;
    }

    private void pointSetTrader(CommandSender sender, TradePointManager manager, String[] args) {
        if (!(sender instanceof Player p)) { msg(sender, "admin-player-only"); return; }
        TradePoint point = findPointOrAtLocation(p, manager, args, 2);
        if (point == null) return;
        if (point.npcCitizensId() != null) {
            try {
                if (net.citizensnpcs.api.CitizensAPI.hasImplementation()) {
                    var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(point.npcCitizensId());
                    if (npc != null) {
                        npc.teleport(p.getLocation(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось телепортировать NPC: " + t.getMessage());
            }
        }
        manager.updateNpc(point);
        p.sendMessage(MessageUtils.parse(p, "<green>Позиция торговца точки обновлена на вашу локацию.</green>"));
    }

    private void pointSetClosedSign(CommandSender sender, TradePointManager manager, String[] args) {
        if (!(sender instanceof Player p)) { msg(sender, "admin-player-only"); return; }
        org.bukkit.block.Block target = p.getTargetBlockExact(5);
        if (target == null || target.getType().isAir()) {
            p.sendMessage(MessageUtils.parse(p, "<red>Посмотрите на табличку или блок (до 5 блоков)!</red>"));
            return;
        }
        TradePoint point = (args.length >= 3) ? findPoint(sender, manager, args[2])
                : manager.pointAt(target.getLocation()).or(() -> manager.pointAt(p.getLocation())).orElse(null);
        if (point == null) {
            msg(sender, "admin-point-not-found", "point", "-");
            return;
        }
        point.closedSignLocation(target.getLocation());
        manager.save(point);
        if (!point.open()) {
            plugin.getStallNpcService().updateClosedSign(point);
        }
        p.sendMessage(MessageUtils.parse(p, "<green>Табличка закрытия установлена на (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ").</green>"));
    }

    private void pointClearClosedSign(CommandSender sender, TradePointManager manager, String[] args) {
        TradePoint point = findPointOrAtLocation(sender, manager, args, 2);
        if (point == null) return;
        point.closedSignLocation(null);
        manager.save(point);
        sender.sendMessage(MessageUtils.parse(sender, "<green>Табличка закрытия для точки очищена.</green>"));
    }

    private void pointTransfer(CommandSender sender, TradePointManager manager, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MessageUtils.parse(sender, "<yellow>Использование: /lsa point transfer <from_owner|claim> <to_player></yellow>"));
            return;
        }
        TradePoint point = findPoint(sender, manager, args[2]);
        if (point == null) return;
        OfflinePlayer to = findPlayer(args[3]);
        if (to == null || to.getUniqueId() == null) {
            sender.sendMessage(MessageUtils.parse(sender, "<red>Игрок " + esc(args[3]) + " не найден.</red>"));
            return;
        }
        boolean ok = manager.transfer(point, to.getUniqueId());
        if (ok) {
            sender.sendMessage(MessageUtils.parse(sender, "<green>Точка успешно передана игроку " + to.getName() + "!</green>"));
        } else {
            sender.sendMessage(MessageUtils.parse(sender, "<red>Не удалось передать точку.</red>"));
        }
    }

    private void pointInfo(CommandSender sender, TradePointManager manager, String[] args) {
        TradePoint p = findPointOrAtLocation(sender, manager, args, 2);
        if (p == null) return;
        sender.sendMessage(MessageUtils.parse(sender, "<gold>=== Информация о торговой точке ===</gold>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Claim ID:</gray> <white>" + p.claimId() + "</white>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Владелец:</gray> <white>" + (p.ownerName() != null ? p.ownerName() : "нет") + "</white> <gray>(" + (p.ownerUuid() != null ? p.ownerUuid() : "-") + ")</gray>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Уровень:</gray> <white>" + p.level() + "</white> <gray>(Полок витрины: <white>" + p.sellSlots() + "</white>, Ордеров: <white>" + p.buySlots() + "</white>, Склад: <white>" + manager.getStorageCapacity(p) + "</white> ст.)</gray>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Баланс кассы:</gray> " + plugin.getMarketStyle().money(p.tillCoins()) + " <gray>(" + p.tillCoins() + ")</gray>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Режим торговли:</gray> <white>" + p.tradingMode() + "</white>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Статус:</gray> " + (p.open() ? "<green>Открыта</green>" : "<red>Закрыта (" + (p.closeReason() != null ? p.closeReason().name() : "-") + ")</red>")));
        String guardInfo = p.guardState() == dev.lovelace.loveshops.market.model.GuardState.ACTIVE
                ? "<green>Активна</green> <gray>(до " + p.guardPaidUntil() + ")</gray>"
                : "<gray>" + p.guardState() + "</gray>";
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Стража:</gray> " + guardInfo));
        var signLoc = p.closedSignLocation();
        String signText = signLoc != null ? (signLoc.getWorld().getName() + " " + signLoc.getBlockX() + ", " + signLoc.getBlockY() + ", " + signLoc.getBlockZ()) : "не установлена";
        sender.sendMessage(MessageUtils.parse(sender, "<gray>Табличка закрытия:</gray> <white>" + signText + "</white>"));
        sender.sendMessage(MessageUtils.parse(sender, "<gold>====================================</gold>"));
    }

    // ------------------------------------------------------------------ tab completion

    private static List<String> match(String prefix, List<String> options) {
        return StringUtil.copyPartialMatches(prefix, options, new ArrayList<>());
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> materialNames(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && m.name().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(m.name().toLowerCase(Locale.ROOT));
                if (out.size() >= 60) break;
            }
        }
        return out;
    }

    /** Completions for {@code price get|list|reset|mult|bounds|history ...}; args include the leading "price". */
    public List<String> tabPrice(String[] args) {
        if (args.length == 2) return match(args[1], PRICE_SUBS);
        String sub = AdminParse.canonical(args[1]);
        if (args.length == 3) {
            return switch (sub) {
                case "get", "list", "reset", "mult" -> match(args[2], PricesManager.TARGETS);
                case "bounds" -> {
                    List<String> opts = new ArrayList<>(List.of("list"));
                    opts.addAll(materialNames(args[2]));
                    yield match(args[2], opts);
                }
                case "history" -> materialNames(args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4) {
            return switch (sub) {
                case "get", "reset" -> materialNames(args[3]);
                case "mult" -> match(args[3], List.of("+10", "-10", "+25", "-25", "reset"));
                case "bounds" -> match(args[3], List.of("off", "1", "10", "100", "1000"));
                default -> List.of();
            };
        }
        return List.of();
    }

    public List<String> tabAuction(String[] args) {
        if (args.length == 2) return match(args[1], AUCTION_SUBS);
        String sub = AdminParse.canonical(args[1]);
        if (args.length == 3 && List.of("price", "buyout", "extend", "end", "cancel").contains(sub)) {
            return match(args[2], plugin.getAuctionManager().activeAuctionIds(25).stream().map(String::valueOf).toList());
        }
        if (args.length == 3 && sub.equals("step")) return match(args[2], List.of("5%", "10%", "50", "100", "reset"));
        if (args.length == 4 && sub.equals("buyout")) return match(args[3], List.of("off"));
        if (args.length == 4 && sub.equals("extend")) return match(args[3], List.of("1", "6", "12", "24"));
        if (args.length == 4 && sub.equals("create")) return match(args[3], List.of("off"));
        return List.of();
    }

    public List<String> tabPoint(String[] args) {
        if (args.length == 2) return match(args[1], POINT_SUBS);
        String sub = AdminParse.canonical(args[1]);
        var manager = plugin.getTradePointManager();
        if (args.length == 3 && manager != null && List.of("close", "open", "seize", "settrader", "setclosedsign", "clearclosedsign", "transfer", "info").contains(sub)) {
            List<String> names = new ArrayList<>();
            for (TradePoint p : manager.all()) if (p.hasOwner() && p.ownerName() != null) names.add(p.ownerName());
            return match(args[2], names);
        }
        if (args.length == 4 && sub.equals("transfer")) {
            return match(args[3], onlineNames());
        }
        return List.of();
    }
}
