package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.BlacklistEntry;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.DiscountEntry;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.StorageItem;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * JDBC access to the market tables. Every method is synchronous and opens its own short-lived
 * connection (WAL, busy_timeout) like the rest of LoveShops. Multi-statement changes that must
 * not be half-applied take a {@link Connection} and are wrapped by {@link #inTransaction}.
 */
public final class MarketRepository {

    /** An entry waiting for its player in {@code pending_returns}: an item, or coins when {@code itemData} is null. */
    public record ReturnEntry(long id, UUID player, String itemData, long amount, String reason, long createdAt) {
        public boolean isCoins() { return itemData == null; }
    }

    @FunctionalInterface
    public interface TxBody<T> {
        T run(Connection conn) throws SQLException;
    }

    private final LoveShops plugin;

    public MarketRepository(LoveShops plugin) {
        this.plugin = plugin;
    }

    private Connection connect() throws SQLException {
        return plugin.getDatabaseManager().getConnection();
    }

    /**
     * Runs {@code body} in one immediate transaction: another writer cannot slip in between the
     * checks and the updates, and any exception rolls everything back.
     */
    public <T> T inTransaction(TxBody<T> body) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getImmediateConnection()) {
            // With transaction_mode=IMMEDIATE the driver issues BEGIN IMMEDIATE here.
            conn.setAutoCommit(false);
            try {
                T result = body.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ trade points

    private static final String POINT_COLS = "claim_id, owner_uuid, owner_name, npc_citizens_id, guard_citizens_id, level, "
            + "sell_slots, buy_slots, is_open, close_reason, till_coins, revenue_total, sales_total, guard_state, "
            + "guard_paid_until, rented_at, version, trading_mode, closed_sign_world, closed_sign_x, closed_sign_y, closed_sign_z, tp_loc, id_sign_loc";

    public List<TradePoint> loadPoints() throws SQLException {
        List<TradePoint> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT " + POINT_COLS + " FROM trade_points");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapPoint(rs));
        }
        return out;
    }

    private static TradePoint mapPoint(ResultSet rs) throws SQLException {
        TradePoint p = new TradePoint(UUID.fromString(rs.getString("claim_id")));
        String owner = rs.getString("owner_uuid");
        p.ownerUuid(owner == null ? null : UUID.fromString(owner));
        p.ownerName(rs.getString("owner_name"));
        int npc = rs.getInt("npc_citizens_id");
        p.npcCitizensId(rs.wasNull() ? null : npc);
        int guard = rs.getInt("guard_citizens_id");
        p.guardCitizensId(rs.wasNull() ? null : guard);
        p.level(rs.getInt("level"));
        p.sellSlots(rs.getInt("sell_slots"));
        p.buySlots(rs.getInt("buy_slots"));
        p.open(rs.getInt("is_open") != 0);
        p.closeReason(CloseReason.parse(rs.getString("close_reason")));
        p.tillCoins(rs.getLong("till_coins"));
        p.revenueTotal(rs.getLong("revenue_total"));
        p.salesTotal(rs.getLong("sales_total"));
        p.guardState(GuardState.parse(rs.getString("guard_state")));
        p.guardPaidUntil(rs.getLong("guard_paid_until"));
        p.rentedAt(rs.getLong("rented_at"));
        p.version(rs.getLong("version"));
        p.tradingMode(TradingMode.parse(rs.getString("trading_mode")));
        String cWorld = rs.getString("closed_sign_world");
        if (cWorld != null && !cWorld.isBlank()) {
            org.bukkit.World w = Bukkit.getWorld(cWorld);
            if (w != null) {
                p.closedSignLocation(new org.bukkit.Location(w, rs.getInt("closed_sign_x"), rs.getInt("closed_sign_y"), rs.getInt("closed_sign_z")));
            }
        }
        p.teleportLocation(LocationCodec.decode(rs.getString("tp_loc")));
        p.idSignLocation(LocationCodec.decode(rs.getString("id_sign_loc")));
        return p;
    }

    public TradePoint loadPoint(UUID claimId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT " + POINT_COLS + " FROM trade_points WHERE claim_id = ?")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPoint(rs) : null;
            }
        }
    }

    public void savePoint(TradePoint p) throws SQLException {
        try (Connection conn = connect()) {
            savePoint(conn, p);
        }
    }

    public void savePoint(Connection conn, TradePoint p) throws SQLException {
        p.version(p.version() + 1);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trade_points (" + POINT_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(claim_id) DO UPDATE SET owner_uuid=excluded.owner_uuid, owner_name=excluded.owner_name, "
                        + "npc_citizens_id=excluded.npc_citizens_id, guard_citizens_id=excluded.guard_citizens_id, "
                        + "level=excluded.level, sell_slots=excluded.sell_slots, buy_slots=excluded.buy_slots, "
                        + "is_open=excluded.is_open, close_reason=excluded.close_reason, till_coins=excluded.till_coins, "
                        + "revenue_total=excluded.revenue_total, sales_total=excluded.sales_total, "
                        + "guard_state=excluded.guard_state, guard_paid_until=excluded.guard_paid_until, "
                        + "rented_at=excluded.rented_at, version=excluded.version, trading_mode=excluded.trading_mode, "
                        + "closed_sign_world=excluded.closed_sign_world, closed_sign_x=excluded.closed_sign_x, "
                        + "closed_sign_y=excluded.closed_sign_y, closed_sign_z=excluded.closed_sign_z, "
                        + "tp_loc=excluded.tp_loc, id_sign_loc=excluded.id_sign_loc")) {
            ps.setString(1, p.claimId().toString());
            ps.setString(2, p.ownerUuid() == null ? null : p.ownerUuid().toString());
            ps.setString(3, p.ownerName());
            if (p.npcCitizensId() == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, p.npcCitizensId());
            if (p.guardCitizensId() == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, p.guardCitizensId());
            ps.setInt(6, p.level());
            ps.setInt(7, p.sellSlots());
            ps.setInt(8, p.buySlots());
            ps.setInt(9, p.open() ? 1 : 0);
            ps.setString(10, p.closeReason() == null ? null : p.closeReason().name());
            ps.setLong(11, p.tillCoins());
            ps.setLong(12, p.revenueTotal());
            ps.setLong(13, p.salesTotal());
            ps.setString(14, p.guardState().name());
            ps.setLong(15, p.guardPaidUntil());
            ps.setLong(16, p.rentedAt());
            ps.setLong(17, p.version());
            ps.setString(18, p.tradingMode().name());
            if (p.closedSignLocation() != null && p.closedSignLocation().getWorld() != null) {
                ps.setString(19, p.closedSignLocation().getWorld().getName());
                ps.setInt(20, p.closedSignLocation().getBlockX());
                ps.setInt(21, p.closedSignLocation().getBlockY());
                ps.setInt(22, p.closedSignLocation().getBlockZ());
            } else {
                ps.setNull(19, java.sql.Types.VARCHAR);
                ps.setNull(20, java.sql.Types.INTEGER);
                ps.setNull(21, java.sql.Types.INTEGER);
                ps.setNull(22, java.sql.Types.INTEGER);
            }
            ps.setString(23, LocationCodec.encode(p.teleportLocation()));
            ps.setString(24, LocationCodec.encode(p.idSignLocation()));
            ps.executeUpdate();
        }
    }

    public void deletePoint(UUID claimId) throws SQLException {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stall_listings WHERE point_id = ?")) {
                ps.setString(1, claimId.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trade_points WHERE claim_id = ?")) {
                ps.setString(1, claimId.toString());
                ps.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ listings

    private static final String LISTING_COLS = "id, point_id, type, slot_index, item_data, item_hash, unit_price, stock, max_amount, active";

    public int countListings(UUID pointId, ListingType type) {
        if (pointId == null || type == null) return 0;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM stall_listings WHERE point_id = ? AND type = ? AND active = 1")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Не удалось посчитать лоты точки " + pointId + ": " + e.getMessage());
            return 0;
        }
    }

    public List<StallListing> listings(UUID pointId) throws SQLException {
        List<StallListing> out = new ArrayList<>();
        try (Connection conn = connect()) {
            out.addAll(listings(conn, pointId));
        }
        return out;
    }

    public List<StallListing> listings(Connection conn, UUID pointId) throws SQLException {
        List<StallListing> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LISTING_COLS + " FROM stall_listings WHERE point_id = ? AND active = 1 ORDER BY type, slot_index")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StallListing l = mapListing(rs);
                    if (l != null) out.add(l);
                }
            }
        }
        return out;
    }

    public StallListing listing(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + LISTING_COLS + " FROM stall_listings WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapListing(rs) : null;
            }
        }
    }

    private StallListing mapListing(ResultSet rs) throws SQLException {
        ItemStack template = ItemStackConverter.itemStackFromBase64(rs.getString("item_data"));
        if (template == null) {
            plugin.getLogger().warning("Лот #" + rs.getLong("id") + " с повреждённым предметом пропущен.");
            return null;
        }
        // wasNull() refers to the LAST column read, so it must be asked right after max_amount.
        int max = rs.getInt("max_amount");
        boolean noCap = rs.wasNull();
        return new StallListing(rs.getLong("id"), UUID.fromString(rs.getString("point_id")),
                ListingType.valueOf(rs.getString("type")), rs.getInt("slot_index"), template,
                rs.getString("item_hash"), rs.getLong("unit_price"), rs.getInt("stock"),
                noCap ? 0 : max, rs.getInt("active") != 0);
    }

    /** @return the new listing id, or -1 if the slot was taken meanwhile */
    public long insertListing(Connection conn, UUID pointId, ListingType type, int slot, ItemStack template,
                              long unitPrice, int stock, int maxAmount) throws SQLException {
        ItemStack one = template.clone();
        one.setAmount(1);
        String data = ItemStackConverter.itemStackToBase64(one);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO stall_listings (point_id, type, slot_index, item_data, item_hash, unit_price, stock, max_amount, active) "
                        + "VALUES (?,?,?,?,?,?,?,?,1) ON CONFLICT(point_id, type, slot_index) DO NOTHING",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, pointId.toString());
            ps.setString(2, type.name());
            ps.setInt(3, slot);
            ps.setString(4, data);
            ps.setString(5, hashOf(data));
            ps.setLong(6, unitPrice);
            ps.setInt(7, stock);
            if (maxAmount > 0) ps.setInt(8, maxAmount); else ps.setNull(8, java.sql.Types.INTEGER);
            if (ps.executeUpdate() == 0) return -1L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public void updateListingStock(Connection conn, long id, int stock) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE stall_listings SET stock = ? WHERE id = ?")) {
            ps.setInt(1, stock);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Relative stock change (never below zero): safe against a concurrent absolute update. */
    public void addListingStock(Connection conn, long id, int delta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE stall_listings SET stock = MAX(0, stock + ?) WHERE id = ?")) {
            ps.setInt(1, delta);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void updateListingPrice(Connection conn, long id, long unitPrice) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE stall_listings SET unit_price = ? WHERE id = ?")) {
            ps.setLong(1, unitPrice);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void deleteListing(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stall_listings WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void deleteListings(Connection conn, UUID pointId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stall_listings WHERE point_id = ?")) {
            ps.setString(1, pointId.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ pending returns

    /** Puts {@code count} of {@code template} (any amount on the stack) into the player's returns. */
    public void addReturnItem(Connection conn, UUID player, ItemStack template, long count, String reason) throws SQLException {
        if (count <= 0) return;
        ItemStack one = template.clone();
        one.setAmount(1);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_returns (player_uuid, item_data, amount, reason, created_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, ItemStackConverter.itemStackToBase64(one));
            ps.setLong(3, count);
            ps.setString(4, reason);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void addReturnCoins(Connection conn, UUID player, long coins, String reason) throws SQLException {
        if (coins <= 0) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_returns (player_uuid, item_data, amount, reason, created_at) VALUES (?,NULL,?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, coins);
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public List<ReturnEntry> returnsFor(UUID player) throws SQLException {
        List<ReturnEntry> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, player_uuid, item_data, amount, reason, created_at FROM pending_returns WHERE player_uuid = ? ORDER BY id")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReturnEntry(rs.getLong("id"), UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("item_data"), rs.getLong("amount"), rs.getString("reason"), rs.getLong("created_at")));
                }
            }
        }
        return out;
    }

    public int countReturns(UUID player) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM pending_returns WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void deleteReturn(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pending_returns WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void updateReturnAmount(Connection conn, long id, long amount, String itemData) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE pending_returns SET amount = ?, item_data = COALESCE(?, item_data) WHERE id = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, itemData);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ owner notices

    public void addNotice(UUID player, String message) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO owner_notices (player_uuid, message, created_at) VALUES (?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, message);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Reads and deletes the player's notices in one transaction, so none is shown twice or lost. */
    public List<String> takeNotices(UUID player) throws SQLException {
        return inTransaction(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT message FROM owner_notices WHERE player_uuid = ? ORDER BY id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            if (!out.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM owner_notices WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ trader progress

    public int traderLevel(UUID player) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT level FROM player_trader_progress WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(1, rs.getInt(1)) : 1;
            }
        }
    }

    public void setTraderLevel(UUID player, int level) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO player_trader_progress (player_uuid, level) VALUES (?,?) "
                             + "ON CONFLICT(player_uuid) DO UPDATE SET level = excluded.level")) {
            ps.setString(1, player.toString());
            ps.setInt(2, Math.max(1, level));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ point counters

    /** A point's live money state as the database holds it. */
    public record PointState(UUID owner, boolean open, long till) {}

    public PointState pointState(Connection conn, UUID pointId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuid, is_open, till_coins FROM trade_points WHERE claim_id = ?")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String owner = rs.getString(1);
                return new PointState(owner == null ? null : UUID.fromString(owner), rs.getInt(2) != 0, rs.getLong(3));
            }
        }
    }

    /**
     * Adds to the till and the statistics atomically (a relative update, so the in-memory copy can
     * never overwrite a concurrent change).
     *
     * @return {till, revenueTotal, salesTotal} after the change
     */
    public long[] addTill(Connection conn, UUID pointId, long tillDelta, long revenueDelta, long salesDelta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE trade_points SET till_coins = MAX(0, till_coins + ?), revenue_total = revenue_total + ?, "
                        + "sales_total = sales_total + ?, version = version + 1 WHERE claim_id = ? "
                        + "RETURNING till_coins, revenue_total, sales_total")) {
            ps.setLong(1, tillDelta);
            ps.setLong(2, revenueDelta);
            ps.setLong(3, salesDelta);
            ps.setString(4, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)} : null;
            }
        }
    }

    public void setPointLevel(Connection conn, UUID pointId, int level, int sellSlots, int buySlots) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE trade_points SET level = ?, sell_slots = ?, buy_slots = ?, version = version + 1 WHERE claim_id = ?")) {
            ps.setInt(1, level);
            ps.setInt(2, sellSlots);
            ps.setInt(3, buySlots);
            ps.setString(4, pointId.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ pending trades (journal)

    public record PendingTrade(long id, String kind, UUID player, UUID pointId, long listingId, int amount,
                               long total, long tax, long sellerGets, String state, long createdAt) {}

    public long insertPending(Connection conn, String kind, UUID player, UUID pointId, long listingId,
                              int amount, long total, long tax, long sellerGets) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_trades (kind, buyer_uuid, point_id, listing_id, amount, total, tax, seller_gets, state, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,'RESERVED',?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, player.toString());
            ps.setString(3, pointId == null ? null : pointId.toString());
            ps.setLong(4, listingId);
            ps.setInt(5, amount);
            ps.setLong(6, total);
            ps.setLong(7, tax);
            ps.setLong(8, sellerGets);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public void setPendingState(Connection conn, long id, String state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE pending_trades SET state = ? WHERE id = ?")) {
            ps.setString(1, state);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Unfinished journal entries older than {@code olderThanMillis}: what a crash or a DB failure left behind. */
    public List<PendingTrade> unfinishedPending(long olderThanMillis) throws SQLException {
        List<PendingTrade> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, kind, buyer_uuid, point_id, listing_id, amount, total, tax, seller_gets, state, created_at "
                             + "FROM pending_trades WHERE state IN ('RESERVED','CHARGED') AND created_at < ? ORDER BY id")) {
            ps.setLong(1, System.currentTimeMillis() - olderThanMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pointRaw = rs.getString(4);
                    out.add(new PendingTrade(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                            pointRaw == null ? null : UUID.fromString(pointRaw), rs.getLong(5), rs.getInt(6), rs.getLong(7),
                            rs.getLong(8), rs.getLong(9), rs.getString(10), rs.getLong(11)));
                }
            }
        }
        return out;
    }

    /** Old finished journal rows are only history; keep the table from growing without bound. */
    public int purgeFinishedPending(long olderThanMillis) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM pending_trades WHERE state NOT IN ('RESERVED','CHARGED') AND created_at < ?")) {
            ps.setLong(1, System.currentTimeMillis() - olderThanMillis);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ transactions log

    public void insertTransaction(Connection conn, String kind, UUID pointId, UUID seller, UUID buyer,
                                  String itemHash, int amount, long total, long tax) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO market_transactions (kind, point_id, seller_uuid, buyer_uuid, item_hash, amount, price, tax, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, pointId == null ? null : pointId.toString());
            ps.setString(3, seller == null ? null : seller.toString());
            ps.setString(4, buyer == null ? null : buyer.toString());
            ps.setString(5, itemHash);
            ps.setInt(6, amount);
            ps.setLong(7, total);
            ps.setLong(8, tax);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Coins the player has moved with this stall (as buyer or as seller): the base for rating it. */
    public long tradeVolume(UUID player, UUID pointId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(price), 0) FROM market_transactions "
                             + "WHERE point_id = ? AND (buyer_uuid = ? OR seller_uuid = ?)")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, player.toString());
            ps.setString(3, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public void addTraderProgress(Connection conn, UUID player, long soldDelta, long boughtDelta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_trader_progress (player_uuid, total_sold, total_bought) VALUES (?,?,?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET total_sold = total_sold + excluded.total_sold, "
                        + "total_bought = total_bought + excluded.total_bought")) {
            ps.setString(1, player.toString());
            ps.setLong(2, soldDelta);
            ps.setLong(3, boughtDelta);
            ps.executeUpdate();
        }
    }

    public void setTraderLevel(Connection conn, UUID player, int level) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_trader_progress (player_uuid, level) VALUES (?,?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET level = excluded.level")) {
            ps.setString(1, player.toString());
            ps.setInt(2, Math.max(1, level));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ ratings

    /** Weighted average stars and the number of visible ratings. */
    public record RatingSummary(double average, int count) {}

    public RatingSummary ratingSummary(UUID pointId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(stars * weight), 0), COALESCE(SUM(weight), 0), COUNT(*) "
                             + "FROM stall_ratings WHERE point_id = ? AND hidden = 0")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(3) == 0 || rs.getDouble(2) <= 0) return new RatingSummary(0.0, 0);
                return new RatingSummary(rs.getDouble(1) / rs.getDouble(2), rs.getInt(3));
            }
        }
    }

    /** @return the time of the rater's existing rating of this stall, or -1 */
    public long ratingTime(Connection conn, UUID pointId, UUID rater) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT created_at FROM stall_ratings WHERE point_id = ? AND rater_uuid = ?")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, rater.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    public void upsertRating(Connection conn, UUID pointId, UUID rater, int stars, String comment,
                             long tradeAmount, double weight) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO stall_ratings (point_id, rater_uuid, stars, comment, trade_amount, weight, hidden, created_at) "
                        + "VALUES (?,?,?,?,?,?,0,?) ON CONFLICT(point_id, rater_uuid) DO UPDATE SET stars = excluded.stars, "
                        + "comment = excluded.comment, trade_amount = excluded.trade_amount, weight = excluded.weight, "
                        + "created_at = excluded.created_at")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, rater.toString());
            ps.setInt(3, stars);
            ps.setString(4, comment);
            ps.setLong(5, tradeAmount);
            ps.setDouble(6, weight);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ robbery state

    public record RobberyState(UUID player, UUID pointId, long robberyUntil, long hostileUntil, int harassment,
                               long harassmentSince, long bannedUntil, String dayKey, int clicksToday,
                               double lastChance, long lastClickAt, boolean dayLocked) {
        public static RobberyState empty(UUID player, UUID pointId) {
            return new RobberyState(player, pointId, 0, 0, 0, 0, 0, "", 0, 0.0, 0, false);
        }
    }

    public RobberyState robberyState(Connection conn, UUID player, UUID pointId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT robbery_until, hostile_until, harassment, harassment_since, banned_until, day_key, clicks_today, "
                        + "last_chance, last_click_at, day_locked FROM robbery_state WHERE player_uuid = ? AND point_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return RobberyState.empty(player, pointId);
                return new RobberyState(player, pointId, rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6), rs.getInt(7), rs.getDouble(8), rs.getLong(9), rs.getInt(10) != 0);
            }
        }
    }

    public void saveRobberyState(Connection conn, RobberyState s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO robbery_state (player_uuid, point_id, robbery_until, hostile_until, harassment, harassment_since, "
                        + "banned_until, day_key, clicks_today, last_chance, last_click_at, day_locked) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(player_uuid, point_id) DO UPDATE SET robbery_until=excluded.robbery_until, "
                        + "hostile_until=excluded.hostile_until, harassment=excluded.harassment, harassment_since=excluded.harassment_since, "
                        + "banned_until=excluded.banned_until, day_key=excluded.day_key, clicks_today=excluded.clicks_today, "
                        + "last_chance=excluded.last_chance, last_click_at=excluded.last_click_at, day_locked=excluded.day_locked")) {
            ps.setString(1, s.player().toString());
            ps.setString(2, s.pointId().toString());
            ps.setLong(3, s.robberyUntil());
            ps.setLong(4, s.hostileUntil());
            ps.setInt(5, s.harassment());
            ps.setLong(6, s.harassmentSince());
            ps.setLong(7, s.bannedUntil());
            ps.setString(8, s.dayKey());
            ps.setInt(9, s.clicksToday());
            ps.setDouble(10, s.lastChance());
            ps.setLong(11, s.lastClickAt());
            ps.setInt(12, s.dayLocked() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** Current chances of the OTHER robbers who clicked this stall lately today and are not locked out. */
    public List<Double> coAttackChances(Connection conn, UUID pointId, UUID except, String dayKey, long sinceMillis) throws SQLException {
        List<Double> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_chance FROM robbery_state WHERE point_id = ? AND player_uuid <> ? AND day_key = ? "
                        + "AND day_locked = 0 AND last_click_at >= ? AND last_chance > 0")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, except.toString());
            ps.setString(3, dayKey);
            ps.setLong(4, sinceMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getDouble(1));
            }
        }
        return out;
    }

    /** Successful robberies since {@code sinceMillis}; a null player/point means "any". */
    public int robberiesSince(Connection conn, UUID robber, UUID pointId, long sinceMillis) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM robbery_log WHERE created_at >= ?");
        if (robber != null) sql.append(" AND robber_uuid = ?");
        if (pointId != null) sql.append(" AND point_id = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, sinceMillis);
            if (robber != null) ps.setString(i++, robber.toString());
            if (pointId != null) ps.setString(i, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public long insertRobberyLog(Connection conn, UUID robber, UUID pointId, UUID owner, long coins, String itemsJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO robbery_log (robber_uuid, point_id, owner_uuid, coins, items_json, restored, created_at) VALUES (?,?,?,?,?,0,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, robber.toString());
            ps.setString(2, pointId.toString());
            ps.setString(3, owner.toString());
            ps.setLong(4, coins);
            ps.setString(5, itemsJson);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public void closePoint(Connection conn, UUID pointId, CloseReason reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE trade_points SET is_open = 0, close_reason = ?, version = version + 1 WHERE claim_id = ?")) {
            ps.setString(1, reason.name());
            ps.setString(2, pointId.toString());
            ps.executeUpdate();
        }
    }

    public void addNotice(Connection conn, UUID player, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO owner_notices (player_uuid, message, created_at) VALUES (?,?,?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, message);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Robbery state rows nobody will read again: all their timers ran out and their day is over. */
    public int pruneRobberyState(long olderThanMillis, String currentDayKey) throws SQLException {
        long limit = System.currentTimeMillis() - olderThanMillis;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM robbery_state WHERE robbery_until < ? AND hostile_until < ? AND banned_until < ? "
                             + "AND last_click_at < ? AND day_key <> ?")) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setLong(4, limit);
            ps.setString(5, currentDayKey);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ guard

    public void setGuard(Connection conn, UUID pointId, String state, long paidUntil) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE trade_points SET guard_state = ?, guard_paid_until = ?, version = version + 1 WHERE claim_id = ?")) {
            ps.setString(1, state);
            ps.setLong(2, paidUntil);
            ps.setString(3, pointId.toString());
            ps.executeUpdate();
        }
    }



    // ------------------------------------------------------------------ admin: price audit

    public record PriceChange(long id, String adminName, String target, String item, String oldValue, String newValue,
                              String kind, long createdAt) {}

    public void addPriceChange(UUID admin, String adminName, String target, String item, String oldValue, String newValue,
                               String kind) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO price_changes (admin_uuid, admin_name, target, item, old_value, new_value, kind, created_at) "
                             + "VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, admin == null ? null : admin.toString());
            ps.setString(2, adminName);
            ps.setString(3, target);
            ps.setString(4, item);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.setString(7, kind);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Newest first; {@code item} null = all items. */
    public List<PriceChange> priceChanges(String item, int offset, int limit) throws SQLException {
        List<PriceChange> out = new ArrayList<>();
        String sql = "SELECT id, admin_name, target, item, old_value, new_value, kind, created_at FROM price_changes"
                + (item == null ? "" : " WHERE item = ?") + " ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (item != null) ps.setString(i++, item);
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PriceChange(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                }
            }
        }
        return out;
    }

    public int priceChangeCount(String item) throws SQLException {
        String sql = "SELECT COUNT(*) FROM price_changes" + (item == null ? "" : " WHERE item = ?");
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (item != null) ps.setString(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int prunePriceChanges(long olderThanMillis) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM price_changes WHERE created_at < ?")) {
            ps.setLong(1, olderThanMillis);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ admin: robberies

    public record RobberyLog(long id, UUID robber, UUID pointId, UUID owner, long coins, String itemsJson,
                             boolean restored, long createdAt) {}

    private static RobberyLog mapRobbery(ResultSet rs) throws SQLException {
        return new RobberyLog(rs.getLong("id"), UUID.fromString(rs.getString("robber_uuid")),
                UUID.fromString(rs.getString("point_id")), UUID.fromString(rs.getString("owner_uuid")),
                rs.getLong("coins"), rs.getString("items_json"), rs.getInt("restored") != 0, rs.getLong("created_at"));
    }

    public RobberyLog robberyLog(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, robber_uuid, point_id, owner_uuid, coins, items_json, restored, created_at FROM robbery_log WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRobbery(rs) : null;
            }
        }
    }

    public List<RobberyLog> recentRobberies(int offset, int limit) throws SQLException {
        List<RobberyLog> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, robber_uuid, point_id, owner_uuid, coins, items_json, restored, created_at FROM robbery_log "
                             + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRobbery(rs));
            }
        }
        return out;
    }

    /** Marks a robbery as restored; false when it was already (so the goods are never returned twice). */
    public boolean markRobberyRestored(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE robbery_log SET restored = 1 WHERE id = ? AND restored = 0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Retention for tables that only ever grow: finished trades' history, robbery records (needed
     * only while an administrator may still restore one) and bans that already ran out.
     *
     * @return rows removed, in that order
     */
    public int[] pruneHistory(long now, long transactionsMaxAgeMillis, long robberiesMaxAgeMillis) throws SQLException {
        int[] out = new int[2];
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM market_transactions WHERE created_at < ?")) {
                ps.setLong(1, now - transactionsMaxAgeMillis);
                out[0] = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM robbery_log WHERE created_at < ?")) {
                ps.setLong(1, now - robberiesMaxAgeMillis);
                out[1] = ps.executeUpdate();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ blacklist

    public List<BlacklistEntry> loadBlacklist(UUID pointId) throws SQLException {
        List<BlacklistEntry> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT point_id, player_uuid, reason, created_at FROM point_blacklist WHERE point_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BlacklistEntry(
                            UUID.fromString(rs.getString("point_id")),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("reason"),
                            rs.getLong("created_at")
                    ));
                }
            }
        }
        return out;
    }

    /**
     * Adds (or re-reasons) a blacklist entry unless the point already holds {@code maxEntries} others.
     * The limit lives here, not in the callers, so the GUI and the command cannot disagree about it.
     *
     * @return {@code false} when the list is full
     */
    public boolean addBlacklist(UUID pointId, UUID playerUuid, String reason, int maxEntries) throws SQLException {
        String clean = reason == null || reason.isBlank() ? null
                : (reason.length() > 64 ? reason.substring(0, 64) : reason);
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO point_blacklist (point_id, player_uuid, reason, created_at) "
                            + "SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM point_blacklist WHERE point_id = ? AND player_uuid = ?) "
                            + "OR (SELECT COUNT(*) FROM point_blacklist WHERE point_id = ?) < ? "
                            + "ON CONFLICT(point_id, player_uuid) DO UPDATE SET reason = excluded.reason")) {
                ps.setString(1, pointId.toString());
                ps.setString(2, playerUuid.toString());
                ps.setString(3, clean);
                ps.setLong(4, System.currentTimeMillis());
                ps.setString(5, pointId.toString());
                ps.setString(6, playerUuid.toString());
                ps.setString(7, pointId.toString());
                ps.setInt(8, maxEntries);
                return ps.executeUpdate() > 0;
            }
        }
    }

    public void removeBlacklist(UUID pointId, UUID playerUuid) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM point_blacklist WHERE point_id = ? AND player_uuid = ?")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean isBlacklisted(UUID pointId, UUID playerUuid) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM point_blacklist WHERE point_id = ? AND player_uuid = ?")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ discounts

    public List<DiscountEntry> loadDiscounts(UUID pointId) throws SQLException {
        List<DiscountEntry> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT point_id, beneficiary_uuid, percent, expires_at FROM point_discounts WHERE point_id = ?")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DiscountEntry(
                            UUID.fromString(rs.getString("point_id")),
                            UUID.fromString(rs.getString("beneficiary_uuid")),
                            rs.getInt("percent"),
                            rs.getLong("expires_at")
                    ));
                }
            }
        }
        return out;
    }

    public void setDiscount(UUID pointId, UUID beneficiaryUuid, int percent, long expiresAt) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO point_discounts (point_id, beneficiary_uuid, percent, expires_at) VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT(point_id, beneficiary_uuid) DO UPDATE SET percent = excluded.percent, expires_at = excluded.expires_at")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, beneficiaryUuid.toString());
            ps.setInt(3, percent);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        }
    }

    public void removeDiscount(UUID pointId, UUID beneficiaryUuid) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM point_discounts WHERE point_id = ? AND beneficiary_uuid = ?")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, beneficiaryUuid.toString());
            ps.executeUpdate();
        }
    }

    public DiscountEntry getDiscount(UUID pointId, UUID beneficiaryUuid) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT point_id, beneficiary_uuid, percent, expires_at FROM point_discounts WHERE point_id = ? AND beneficiary_uuid = ?")) {
            ps.setString(1, pointId.toString());
            ps.setString(2, beneficiaryUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DiscountEntry d = new DiscountEntry(
                            UUID.fromString(rs.getString("point_id")),
                            UUID.fromString(rs.getString("beneficiary_uuid")),
                            rs.getInt("percent"),
                            rs.getLong("expires_at")
                    );
                    return d.isExpired() ? null : d;
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ storage

    public List<StorageItem> loadStorage(UUID pointId) throws SQLException {
        List<StorageItem> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT slot_index, item_data, amount FROM stall_storage WHERE point_id = ? ORDER BY slot_index ASC")) {
            ps.setString(1, pointId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemStack is = ItemStackConverter.fromBase64(rs.getString("item_data"));
                    if (is != null) {
                        is.setAmount(rs.getInt("amount"));
                        out.add(new StorageItem(rs.getInt("slot_index"), is));
                    }
                }
            }
        }
        return out;
    }

    public void saveStorageItem(UUID pointId, int slot, ItemStack item) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO stall_storage (point_id, slot_index, item_data, amount) VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT(point_id, slot_index) DO UPDATE SET item_data = excluded.item_data, amount = excluded.amount")) {
            ps.setString(1, pointId.toString());
            ps.setInt(2, slot);
            ps.setString(3, ItemStackConverter.toBase64(item));
            ps.setInt(4, item.getAmount());
            ps.executeUpdate();
        }
    }

    public void removeStorageItem(UUID pointId, int slot) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM stall_storage WHERE point_id = ? AND slot_index = ?")) {
            ps.setString(1, pointId.toString());
            ps.setInt(2, slot);
            ps.executeUpdate();
        }
    }

    public void clearStorage(UUID pointId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM stall_storage WHERE point_id = ?")) {
            ps.setString(1, pointId.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ util

    /** Short stable hash of a serialized item, for logs and duplicate detection. */
    public static String hashOf(String itemData) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(itemData.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(itemData.hashCode());
        }
    }
}
