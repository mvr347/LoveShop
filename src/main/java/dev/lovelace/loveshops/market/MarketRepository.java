package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.utils.ItemStackConverter;
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
            + "guard_paid_until, rented_at, version";

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
        return p;
    }

    public void savePoint(TradePoint p) throws SQLException {
        try (Connection conn = connect()) {
            savePoint(conn, p);
        }
    }

    public void savePoint(Connection conn, TradePoint p) throws SQLException {
        p.version(p.version() + 1);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trade_points (" + POINT_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(claim_id) DO UPDATE SET owner_uuid=excluded.owner_uuid, owner_name=excluded.owner_name, "
                        + "npc_citizens_id=excluded.npc_citizens_id, guard_citizens_id=excluded.guard_citizens_id, "
                        + "level=excluded.level, sell_slots=excluded.sell_slots, buy_slots=excluded.buy_slots, "
                        + "is_open=excluded.is_open, close_reason=excluded.close_reason, till_coins=excluded.till_coins, "
                        + "revenue_total=excluded.revenue_total, sales_total=excluded.sales_total, "
                        + "guard_state=excluded.guard_state, guard_paid_until=excluded.guard_paid_until, "
                        + "rented_at=excluded.rented_at, version=excluded.version")) {
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
