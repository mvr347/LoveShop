package dev.lovelace.loveshops.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;

public class DatabaseManager {

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
            
            // Connect to SQLite DB
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            
            // Enable WAL mode and Foreign Keys
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA foreign_keys=ON;");
            }

            createTables();
            plugin.getLogger().info("✓ База данных SQLite успешно подключена.");
        } catch (SQLException e) {
            plugin.getLogger().severe("✗ Ошибка подключения к базе данных: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 1. shops_npcs
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

            // 2. buyer_inventory
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

            // 3. buyer_prices_history
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

            // 4. buyer_reputation_overrides
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

            // 5. auctions
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

            // 6. auction_bids
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

            // 7. reserved_currency
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reserved_currency (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    reserved_amount INTEGER DEFAULT 0,
                    last_bid_auction_id INTEGER
                );
            """);
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            File dbFile = new File(plugin.getDataFolder(), "database.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        return connection;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("База данных закрыта.");
            }
        } catch (SQLException ignored) {}
    }
}
