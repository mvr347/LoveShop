package dev.lovelace.loveshops.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.regex.Pattern;

public class DatabaseManager {

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$", Pattern.CASE_INSENSITIVE);
    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "database.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA foreign_keys=ON;");
                stmt.execute("PRAGMA busy_timeout=5000;");
                createTables(conn);
            }

            plugin.getLogger().info("✓ База данных SQLite успешно подключена.");
        } catch (SQLException e) {
            plugin.getLogger().severe("✗ Ошибка подключения к базе данных: " + e.getMessage());
        }
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS shops_npcs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x DOUBLE NOT NULL,
                    y DOUBLE NOT NULL,
                    z DOUBLE NOT NULL,
                    yaw FLOAT,
                    pitch FLOAT,
                    name TEXT NOT NULL,
                    display_name TEXT,
                    skin_owner TEXT,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_inventory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    npc_id INTEGER,
                    player_uuid TEXT NOT NULL,
                    item_data TEXT NOT NULL,
                    base_price INTEGER NOT NULL,
                    quantity INTEGER DEFAULT 1,
                    received_at INTEGER DEFAULT (strftime('%s', 'now')),
                    sold_at INTEGER,
                    FOREIGN KEY (npc_id) REFERENCES shops_npcs(id) ON DELETE SET NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_prices_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    submit_count INTEGER DEFAULT 1,
                    last_submitted_at INTEGER DEFAULT (strftime('%s', 'now')),
                    price_penalty_percent REAL DEFAULT 0,
                    UNIQUE(player_uuid, item_type)
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_reputation_overrides (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL,
                    set_by TEXT NOT NULL,
                    set_at INTEGER DEFAULT (strftime('%s', 'now')),
                    custom_message TEXT,
                    reason TEXT
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    auctioneer_npc_id INTEGER,
                    item_data TEXT NOT NULL,
                    starting_price INTEGER NOT NULL,
                    current_highest_bid INTEGER DEFAULT 0,
                    highest_bidder_uuid TEXT,
                    starts_at INTEGER NOT NULL,
                    ends_at INTEGER NOT NULL,
                    status TEXT DEFAULT 'active',
                    winner_uuid TEXT,
                    completed_at INTEGER,
                    FOREIGN KEY (auctioneer_npc_id) REFERENCES shops_npcs(id) ON DELETE SET NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auction_bids (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    auction_id INTEGER NOT NULL,
                    bidder_uuid TEXT NOT NULL,
                    bid_amount INTEGER NOT NULL,
                    placed_at INTEGER DEFAULT (strftime('%s', 'now')),
                    FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reserved_currency (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    reserved_amount INTEGER DEFAULT 0,
                    last_bid_auction_id INTEGER
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS seller_price_state (
                    item_type TEXT PRIMARY KEY,
                    demand_count INTEGER DEFAULT 0,
                    noise_percent REAL DEFAULT 0,
                    cycle_id INTEGER DEFAULT 0
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wanderer_deals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT UNIQUE NOT NULL,
                    status TEXT NOT NULL,
                    ordered_at INTEGER NOT NULL,
                    ready_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    items_json TEXT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wanderer_schedule_state (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    last_arrival_date TEXT
                );
            """);

            // 11. banker_fee_overrides — персональная комиссия банкира (%, 0–100)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS banker_fee_overrides (
                    player_uuid TEXT PRIMARY KEY NOT NULL,
                    fee_percent INTEGER NOT NULL,
                    set_by TEXT NOT NULL,
                    set_at INTEGER DEFAULT (strftime('%s', 'now'))
                );
            """);

            createMarketTables(stmt);

            addColumnIfMissing(stmt, "buyer_inventory", "item_type", "TEXT");
            addColumnIfMissing(stmt, "buyer_inventory", "channel", "TEXT DEFAULT 'seller'");
            addColumnIfMissing(stmt, "buyer_inventory", "auctioned_at", "INTEGER");
            addColumnIfMissing(stmt, "auctions", "buyout_price", "INTEGER");
            addColumnIfMissing(stmt, "wanderer_deals", "requested_category", "TEXT");
            addColumnIfMissing(stmt, "auctions", "delivered_at", "INTEGER");
            addColumnIfMissing(stmt, "shops_npcs", "citizens_id", "INTEGER");
        }
    }

    /**
     * Рынок игроков (торговые точки). Все суммы — целые единицы валюты LoveEconomy.
     * Каждая таблица создаётся через IF NOT EXISTS, индексы отдельно: повторный запуск безопасен.
     */
    private void createMarketTables(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS trade_points (
                claim_id TEXT PRIMARY KEY,
                owner_uuid TEXT,
                owner_name TEXT,
                npc_citizens_id INTEGER,
                guard_citizens_id INTEGER,
                level INTEGER NOT NULL DEFAULT 1,
                sell_slots INTEGER NOT NULL DEFAULT 5,
                buy_slots INTEGER NOT NULL DEFAULT 5,
                is_open INTEGER NOT NULL DEFAULT 0,
                close_reason TEXT,
                till_coins INTEGER NOT NULL DEFAULT 0,
                revenue_total INTEGER NOT NULL DEFAULT 0,
                sales_total INTEGER NOT NULL DEFAULT 0,
                guard_state TEXT NOT NULL DEFAULT 'NONE',
                guard_paid_until INTEGER NOT NULL DEFAULT 0,
                rented_at INTEGER,
                version INTEGER NOT NULL DEFAULT 0
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_points_owner ON trade_points(owner_uuid)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stall_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                point_id TEXT NOT NULL,
                type TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                item_data TEXT NOT NULL,
                item_hash TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                stock INTEGER NOT NULL DEFAULT 0,
                max_amount INTEGER,
                active INTEGER NOT NULL DEFAULT 1,
                UNIQUE(point_id, type, slot_index)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stall_ratings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                point_id TEXT NOT NULL,
                rater_uuid TEXT NOT NULL,
                stars INTEGER NOT NULL CHECK(stars BETWEEN 1 AND 5),
                comment TEXT,
                trade_amount INTEGER NOT NULL,
                weight REAL NOT NULL DEFAULT 1.0,
                hidden INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                UNIQUE(point_id, rater_uuid)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS market_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                point_id TEXT,
                seller_uuid TEXT,
                buyer_uuid TEXT,
                item_hash TEXT,
                amount INTEGER,
                price INTEGER,
                tax INTEGER,
                created_at INTEGER NOT NULL
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_point ON market_transactions(point_id, created_at)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS pending_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                buyer_uuid TEXT NOT NULL,
                point_id TEXT,
                listing_id INTEGER,
                amount INTEGER NOT NULL,
                total INTEGER NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS pending_returns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                item_data TEXT,
                amount INTEGER NOT NULL,
                reason TEXT,
                created_at INTEGER NOT NULL
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_returns_player ON pending_returns(player_uuid)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS owner_notices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_notices_player ON owner_notices(player_uuid)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS player_trader_progress (
                player_uuid TEXT PRIMARY KEY,
                level INTEGER NOT NULL DEFAULT 1,
                total_sold INTEGER NOT NULL DEFAULT 0,
                total_bought INTEGER NOT NULL DEFAULT 0
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS robbery_state (
                player_uuid TEXT NOT NULL,
                point_id TEXT NOT NULL,
                robbery_until INTEGER NOT NULL DEFAULT 0,
                hostile_until INTEGER NOT NULL DEFAULT 0,
                harassment INTEGER NOT NULL DEFAULT 0,
                harassment_since INTEGER NOT NULL DEFAULT 0,
                banned_until INTEGER NOT NULL DEFAULT 0,
                day_key TEXT NOT NULL DEFAULT '',
                clicks_today INTEGER NOT NULL DEFAULT 0,
                last_chance REAL NOT NULL DEFAULT 0,
                last_click_at INTEGER NOT NULL DEFAULT 0,
                day_locked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, point_id)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS robbery_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                robber_uuid TEXT NOT NULL,
                point_id TEXT NOT NULL,
                owner_uuid TEXT NOT NULL,
                coins INTEGER NOT NULL,
                items_json TEXT,
                restored INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS flea_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid TEXT NOT NULL,
                item_data TEXT NOT NULL,
                item_hash TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                amount_left INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_flea_seller ON flea_listings(seller_uuid)");
    }

    private void addColumnIfMissing(Statement stmt, String table, String column, String definition) {
        if (!VALID_IDENTIFIER.matcher(table).matches() || !VALID_IDENTIFIER.matcher(column).matches()) {
            plugin.getLogger().warning("Invalid table or column name: " + table + ", " + column);
            return;
        }
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            // Column already exists from a previous run - safe to ignore.
        }
    }

    public Connection getConnection() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000");
    }

    /**
     * A connection whose transactions start with BEGIN IMMEDIATE: the write lock is taken up front,
     * so a check-then-update sequence cannot read stale data another writer changes meanwhile
     * (the market moves money and items, a lost update there is a dupe).
     */
    public Connection getImmediateConnection() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=5000&transaction_mode=IMMEDIATE");
    }

    public synchronized void close() {
        plugin.getLogger().info("База данных закрыта.");
    }

}
