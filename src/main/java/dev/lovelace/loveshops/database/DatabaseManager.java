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
                purgeLegacyAuctionAndFlea(conn);
                purgeOrphanRows(conn);
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
            createCaravanAndCommissionTables(stmt);

            addColumnIfMissing(stmt, "buyer_inventory", "item_type", "TEXT");
            addColumnIfMissing(stmt, "wanderer_deals", "requested_category", "TEXT");
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
                version INTEGER NOT NULL DEFAULT 0,
                trading_mode TEXT NOT NULL DEFAULT 'BOTH',
                closed_sign_world TEXT,
                closed_sign_x INTEGER,
                closed_sign_y INTEGER,
                closed_sign_z INTEGER,
                tp_loc TEXT,
                id_sign_loc TEXT
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
        // For the retention sweep: without it the hourly DELETE would scan the whole table.
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_time ON market_transactions(created_at)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS pending_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL DEFAULT 'BUY',
                buyer_uuid TEXT NOT NULL,
                point_id TEXT,
                listing_id INTEGER,
                amount INTEGER NOT NULL,
                total INTEGER NOT NULL,
                tax INTEGER NOT NULL DEFAULT 0,
                seller_gets INTEGER NOT NULL DEFAULT 0,
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
        // pending_trades came without these columns in the first market build.
        addColumnIfMissing(stmt, "pending_trades", "kind", "TEXT NOT NULL DEFAULT 'BUY'");
        addColumnIfMissing(stmt, "pending_trades", "tax", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "pending_trades", "seller_gets", "INTEGER NOT NULL DEFAULT 0");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_state ON pending_trades(state, created_at)");

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
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_robbery_log_time ON robbery_log(created_at)");

        // Administrator price changes: who changed what, for how long history is kept see PriceAudit.
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS price_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                admin_uuid TEXT,
                admin_name TEXT NOT NULL,
                target TEXT NOT NULL,
                item TEXT NOT NULL,
                old_value TEXT,
                new_value TEXT,
                kind TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
        """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_price_changes_time ON price_changes(created_at)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS daily_buyer_usage (
                player_uuid TEXT NOT NULL,
                day_key TEXT NOT NULL,
                items INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, day_key)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS point_blacklist (
                point_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                reason TEXT,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (point_id, player_uuid)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS point_discounts (
                point_id TEXT NOT NULL,
                beneficiary_uuid TEXT NOT NULL,
                percent INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (point_id, beneficiary_uuid)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stall_storage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                point_id TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                item_data TEXT NOT NULL,
                amount INTEGER NOT NULL,
                UNIQUE (point_id, slot_index)
            );
        """);

        addColumnIfMissing(stmt, "trade_points", "trading_mode", "TEXT NOT NULL DEFAULT 'BOTH'");
        addColumnIfMissing(stmt, "trade_points", "closed_sign_world", "TEXT");
        addColumnIfMissing(stmt, "trade_points", "closed_sign_x", "INTEGER");
        addColumnIfMissing(stmt, "trade_points", "closed_sign_y", "INTEGER");
        addColumnIfMissing(stmt, "trade_points", "closed_sign_z", "INTEGER");
        addColumnIfMissing(stmt, "trade_points", "tp_loc", "TEXT");
        addColumnIfMissing(stmt, "trade_points", "id_sign_loc", "TEXT");
    }

    private void createCaravanAndCommissionTables(Statement stmt) throws SQLException {
        // --- Daily Caravaner ---
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS daily_caravan_visits (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                spawned_at      INTEGER NOT NULL,
                despawn_at      INTEGER NOT NULL,
                status          TEXT DEFAULT 'ACTIVE',
                created_at      INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS daily_caravan_crates (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                visit_id           INTEGER NOT NULL REFERENCES daily_caravan_visits(id) ON DELETE CASCADE,
                crate_key          TEXT NOT NULL,
                display_name       TEXT NOT NULL,
                current_amount     INTEGER DEFAULT 0,
                max_amount         INTEGER NOT NULL,
                price_per_unit     INTEGER NOT NULL,
                is_urgent          INTEGER DEFAULT 0,
                urgent_expires_at  INTEGER DEFAULT 0,
                closed             INTEGER DEFAULT 0
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS daily_caravan_submissions (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                crate_id        INTEGER NOT NULL REFERENCES daily_caravan_crates(id) ON DELETE CASCADE,
                player_uuid     TEXT NOT NULL,
                amount          INTEGER NOT NULL,
                paid            INTEGER NOT NULL,
                submitted_at    INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        // --- Commission Agent ---
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS commission_lots (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid     TEXT NOT NULL,
                item_data       TEXT NOT NULL,
                price           INTEGER NOT NULL,
                fee_percent     INTEGER NOT NULL DEFAULT 10,
                status          TEXT DEFAULT 'ACTIVE',
                created_at      INTEGER DEFAULT (strftime('%s','now')),
                sold_at         INTEGER,
                buyer_uuid      TEXT,
                is_hot          INTEGER DEFAULT 0
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS commission_sales_history (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                lot_id          INTEGER,
                seller_uuid     TEXT NOT NULL,
                buyer_uuid      TEXT NOT NULL,
                price           INTEGER NOT NULL,
                fee             INTEGER NOT NULL,
                sold_at         INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS commission_pending_payouts (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid     TEXT NOT NULL,
                amount          INTEGER NOT NULL,
                created_at      INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        // --- Lost Caravans ---
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS lost_caravan_sessions (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                scheduled_at      INTEGER NOT NULL,
                opened_at         INTEGER,
                closed_at         INTEGER,
                status            TEXT NOT NULL DEFAULT 'SCHEDULED',
                mode              TEXT,
                participant_count INTEGER DEFAULT 0,
                secret_crate      INTEGER DEFAULT 0,
                created_at        INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS lost_caravan_participants (
                session_id      INTEGER NOT NULL REFERENCES lost_caravan_sessions(id) ON DELETE CASCADE,
                player_uuid     TEXT NOT NULL,
                entry_fee_paid  INTEGER NOT NULL DEFAULT 1,
                refunded        INTEGER DEFAULT 0,
                won_crates      INTEGER DEFAULT 0,
                registered_at   INTEGER DEFAULT (strftime('%s','now')),
                PRIMARY KEY (session_id, player_uuid)
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS lost_caravan_lots (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id      INTEGER NOT NULL REFERENCES lost_caravan_sessions(id) ON DELETE CASCADE,
                lot_index       INTEGER NOT NULL,
                is_secret       INTEGER DEFAULT 0,
                crate_item_data TEXT NOT NULL,
                starting_price  INTEGER NOT NULL,
                current_bid     INTEGER DEFAULT 0,
                highest_bidder  TEXT,
                status          TEXT DEFAULT 'PENDING',
                started_at      INTEGER,
                ended_at        INTEGER
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS lost_caravan_bids (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                lot_id          INTEGER NOT NULL REFERENCES lost_caravan_lots(id) ON DELETE CASCADE,
                bidder_uuid     TEXT NOT NULL,
                amount          INTEGER NOT NULL,
                placed_at       INTEGER DEFAULT (strftime('%s','now'))
            );
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS caravan_cooldowns (
                player_uuid       TEXT PRIMARY KEY,
                last_participated INTEGER NOT NULL
            );
        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_daily_crates_visit ON daily_caravan_crates(visit_id, closed);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_commission_active ON commission_lots(status, is_hot);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_commission_seller ON commission_lots(seller_uuid, status);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_lost_lots_session ON lost_caravan_lots(session_id, status);");
        // Hot lookups: "is this player registered" on every click, the per-visit submission limit,
        // pending payouts on join, the sale history of a lot.
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_lost_part_session_player ON lost_caravan_participants(session_id, player_uuid);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_lost_bids_lot ON lost_caravan_bids(lot_id);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_daily_subs_crate_player ON daily_caravan_submissions(crate_id, player_uuid);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_commission_payouts_seller ON commission_pending_payouts(seller_uuid);");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_commission_history_lot ON commission_sales_history(lot_id);");
    }

    /**
     * Foreign keys were never enforced (the pragma is per connection and every query opens its own), so
     * deleted parents left children behind. They are removed once here, before the flag is switched on for
     * every connection.
     */
    private void purgeOrphanRows(Connection conn) {
        String[] statements = {
                "DELETE FROM daily_caravan_crates WHERE visit_id NOT IN (SELECT id FROM daily_caravan_visits)",
                "DELETE FROM daily_caravan_submissions WHERE crate_id NOT IN (SELECT id FROM daily_caravan_crates)",
                "DELETE FROM lost_caravan_participants WHERE session_id NOT IN (SELECT id FROM lost_caravan_sessions)",
                "DELETE FROM lost_caravan_lots WHERE session_id NOT IN (SELECT id FROM lost_caravan_sessions)",
                "DELETE FROM lost_caravan_bids WHERE lot_id NOT IN (SELECT id FROM lost_caravan_lots)"
        };
        try (Statement stmt = conn.createStatement()) {
            int removed = 0;
            for (String sql : statements) removed += stmt.executeUpdate(sql);
            if (removed > 0) plugin.getLogger().info("БД: удалено осиротевших строк караванов: " + removed);
        } catch (SQLException e) {
            plugin.getLogger().warning("БД: очистка осиротевших строк не удалась: " + e.getMessage());
        }
    }

    /**
     * Daily maintenance: finished caravan visits and sessions, sold or cancelled commission lots and their
     * history older than {@code retentionDays}. Children go with their parents through the cascades.
     *
     * @return rows removed from the parent tables and the history
     */
    public int pruneOldData(int retentionDays) {
        long cutoff = System.currentTimeMillis() / 1000 - Math.max(7, retentionDays) * 86_400L;
        String[] statements = {
                "DELETE FROM lost_caravan_sessions WHERE status = 'CLOSED' AND closed_at > 0 AND closed_at < ?",
                "DELETE FROM daily_caravan_visits WHERE status <> 'ACTIVE' AND despawn_at < ?",
                "DELETE FROM commission_lots WHERE status IN ('SOLD', 'CANCELLED') AND COALESCE(sold_at, created_at) < ?",
                "DELETE FROM commission_sales_history WHERE sold_at < ?"
        };
        int removed = 0;
        try (Connection conn = getConnection()) {
            for (String sql : statements) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, cutoff);
                    removed += ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("БД: плановая очистка истории не удалась: " + e.getMessage());
        }
        return removed;
    }

    private void purgeLegacyAuctionAndFlea(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS auction_bids;");
            stmt.execute("DROP TABLE IF EXISTS auctions;");
            stmt.execute("DROP TABLE IF EXISTS flea_market;");
            stmt.execute("DELETE FROM shops_npcs WHERE LOWER(type) IN ('seller', 'flea', 'auctioneer');");
        } catch (SQLException e) {
            plugin.getLogger().warning("Предупреждение при очистке устаревших таблиц аукциона/барахольщика: " + e.getMessage());
        }
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
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
    }

    /**
     * A connection whose transactions start with BEGIN IMMEDIATE: the write lock is taken up front,
     * so a check-then-update sequence cannot read stale data another writer changes meanwhile
     * (the market moves money and items, a lost update there is a dupe).
     */
    public Connection getImmediateConnection() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&transaction_mode=IMMEDIATE");
    }

    public synchronized void close() {
        plugin.getLogger().info("База данных закрыта.");
    }

}
