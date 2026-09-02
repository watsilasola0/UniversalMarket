package com.sola.universalmarket.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * All persistence for UniversalMarket.
 *
 * THREADING MODEL - this is the important part:
 *
 *   Every database call runs on ONE dedicated background thread. Not a pool.
 *   SQLite serialises writes anyway, so a pool buys nothing, and a single
 *   executor gives us something valuable instead: every write happens in the
 *   order it was submitted. A purchase recorded before a sell can never land
 *   after it, which matters for lifetime stats and sell-limit accounting.
 *
 *   The main server thread NEVER touches the database. Callers get a
 *   CompletableFuture and, if they need to act on the result in Bukkit land,
 *   hop back with Bukkit.getScheduler().runTask(). Any Bukkit API call from
 *   the DB thread is a bug.
 *
 * MONEY IS STORED AS TEXT:
 *
 *   Every currency column is TEXT holding a plain BigDecimal string, not REAL
 *   and not INTEGER. This economy is meant to reach $500T+, and a double loses
 *   whole dollars above 2^53. Text round-trips exactly at any magnitude.
 */
public final class StorageService {

    private final JavaPlugin plugin;
    private final Logger log;
    private final File databaseFile;

    private Connection connection;
    private ExecutorService executor;
    private volatile boolean ready = false;

    public StorageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        String fileName = plugin.getConfig().getString("storage.file", "universalmarket.db");
        this.databaseFile = new File(plugin.getDataFolder(), fileName);
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    public boolean initialise() {
        try {
            // Paper fetches this via the libraries: block in plugin.yml.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.severe("SQLite driver missing. Paper downloads it from the libraries: "
                    + "block in plugin.yml, which needs internet access on first start.");
            return false;
        }

        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.severe("Could not create the plugin data folder.");
            return false;
        }

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "UniversalMarket-DB");
            t.setDaemon(false); // must finish its queue before the JVM exits
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);

        try {
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + databaseFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                // WAL lets reads proceed while a write is in flight, and survives
                // an ungraceful shutdown far better than the default journal.
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            createSchema();
            this.ready = true;
            log.info("Database ready (" + databaseFile.getName() + ")");
            return true;
        } catch (SQLException e) {
            log.severe("Could not open the database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Drain the write queue and close cleanly. Called from onDisable, so it must
     * block - if we return early the server can exit mid-write.
     */
    public void shutdown() {
        ready = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                    log.warning("Database queue did not drain in 15s; forcing shutdown. "
                            + "Some very recent writes may be lost.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        if (connection != null) {
            try {
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                connection.close();
                log.info("Database closed cleanly.");
            } catch (SQLException e) {
                log.warning("Error closing the database: " + e.getMessage());
            }
        }
    }

    public boolean isReady() {
        return ready;
    }

    // ==================================================================
    // Schema
    // ==================================================================

    private void createSchema() throws SQLException {
        String[] statements = {
            // ---- transaction history (spec section 35) ----
            """
            CREATE TABLE IF NOT EXISTS transactions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT    NOT NULL,
                type        TEXT    NOT NULL,
                item_id     TEXT,
                quantity    INTEGER NOT NULL DEFAULT 0,
                amount      TEXT    NOT NULL,
                counterparty TEXT,
                created_at  INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_tx_player ON transactions(player_uuid, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at)",

            // ---- lifetime stats (spec section 41) ----
            """
            CREATE TABLE IF NOT EXISTS lifetime_stats (
                player_uuid    TEXT PRIMARY KEY,
                player_name    TEXT,
                total_earned   TEXT NOT NULL DEFAULT '0',
                total_spent    TEXT NOT NULL DEFAULT '0',
                um_purchases   INTEGER NOT NULL DEFAULT 0,
                server_sales   INTEGER NOT NULL DEFAULT 0,
                fees_paid      TEXT NOT NULL DEFAULT '0',
                shop_revenue   TEXT NOT NULL DEFAULT '0',
                updated_at     INTEGER NOT NULL DEFAULT 0
            )""",

            // ---- per-player sell allowances (spec section 23) ----
            // Keyed per player AND per item, so relogging cannot reset anything.
            """
            CREATE TABLE IF NOT EXISTS sell_limits (
                player_uuid  TEXT    NOT NULL,
                item_id      TEXT    NOT NULL,
                sold_units   INTEGER NOT NULL DEFAULT 0,
                cycle_start  INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, item_id)
            )""",

            // ---- rare goods allowances (spec section 26) ----
            """
            CREATE TABLE IF NOT EXISTS rare_purchases (
                player_uuid   TEXT    NOT NULL,
                item_id       TEXT    NOT NULL,
                bought_units  INTEGER NOT NULL DEFAULT 0,
                window_start  INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, item_id)
            )""",

            // ---- dynamic price state (spec section 22) ----
            """
            CREATE TABLE IF NOT EXISTS price_state (
                item_id          TEXT PRIMARY KEY,
                current_buyback  TEXT    NOT NULL,
                sold_this_cycle  INTEGER NOT NULL DEFAULT 0,
                updated_at       INTEGER NOT NULL
            )""",

            // ---- daily deals / high demand / cycle bookkeeping ----
            """
            CREATE TABLE IF NOT EXISTS market_state (
                key        TEXT PRIMARY KEY,
                value      TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""",

            // ---- contracts (spec section 38) ----
            """
            CREATE TABLE IF NOT EXISTS contract_progress (
                player_uuid  TEXT    NOT NULL,
                contract_id  TEXT    NOT NULL,
                delivered    INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER,
                PRIMARY KEY (player_uuid, contract_id)
            )""",

            // ---- offline shop sale notifications (spec section 33) ----
            // delivered flag is what stops double counting on relog.
            """
            CREATE TABLE IF NOT EXISTS shop_notifications (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid  TEXT    NOT NULL,
                item_id     TEXT,
                quantity    INTEGER NOT NULL DEFAULT 0,
                amount      TEXT    NOT NULL,
                created_at  INTEGER NOT NULL,
                delivered   INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_notif_owner ON shop_notifications(owner_uuid, delivered)",

            // ---- favourites / recents (spec section 42) ----
            """
            CREATE TABLE IF NOT EXISTS favourites (
                player_uuid TEXT NOT NULL,
                item_id     TEXT NOT NULL,
                added_at    INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, item_id)
            )"""
        };

        try (Statement st = connection.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    // ==================================================================
    // Async primitives
    // ==================================================================

    /** Run a write off-thread. Never returns a value. */
    public CompletableFuture<Void> execute(String sql, Object... params) {
        return submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("SQL failed: " + trim(sql) + " -> " + e.getMessage());
            }
            return null;
        });
    }

    /** Run a query off-thread and map the result set. */
    public <T> CompletableFuture<T> query(String sql, Function<ResultSet, T> mapper, Object... params) {
        return submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapper.apply(rs);
                }
            } catch (SQLException e) {
                log.warning("Query failed: " + trim(sql) + " -> " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Run several statements as one transaction. Use this whenever two writes
     * must both land or neither should - for example decrementing a rare-goods
     * allowance and recording the purchase that consumed it.
     */
    public CompletableFuture<Boolean> transaction(SqlBatch batch) {
        return submit(conn -> {
            boolean previousAutoCommit = true;
            try {
                previousAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                batch.run(conn);
                conn.commit();
                return true;
            } catch (Exception e) {
                try { conn.rollback(); }
                catch (SQLException rollbackError) {
                    log.severe("Rollback failed: " + rollbackError.getMessage());
                }
                log.warning("Transaction rolled back: " + e.getMessage());
                return false;
            } finally {
                try { conn.setAutoCommit(previousAutoCommit); }
                catch (SQLException ignored) { }
            }
        });
    }

    private <T> CompletableFuture<T> submit(Function<Connection, T> work) {
        if (!ready || executor == null || executor.isShutdown()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.submit(() -> {
                try {
                    future.complete(work.apply(connection));
                } catch (Throwable t) {
                    log.warning("Database task threw: " + t);
                    future.complete(null);
                }
            });
        } catch (Exception e) {
            // Executor rejected the task, almost certainly during shutdown.
            future.complete(null);
        }
        return future;
    }

    private void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
            else if (p instanceof Integer v) ps.setInt(i + 1, v);
            else if (p instanceof Long v) ps.setLong(i + 1, v);
            else if (p instanceof Boolean v) ps.setInt(i + 1, v ? 1 : 0);
            else if (p instanceof java.math.BigDecimal v) ps.setString(i + 1, v.toPlainString());
            else ps.setString(i + 1, p.toString());
        }
    }

    private String trim(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 90 ? flat.substring(0, 90) + "..." : flat;
    }

    // ==================================================================
    // Maintenance
    // ==================================================================

    /** Enforce the retention limits from config so the file cannot grow forever. */
    public void pruneOldData() {
        int days = plugin.getConfig().getInt("storage.transaction-retention-days", 90);
        int perPlayer = plugin.getConfig().getInt("storage.max-transactions-per-player", 500);

        if (days > 0) {
            long cutoff = System.currentTimeMillis() - (days * 86_400_000L);
            execute("DELETE FROM transactions WHERE created_at < ?", cutoff);
        }
        if (perPlayer > 0) {
            // Keep only the newest N rows per player.
            execute("""
                DELETE FROM transactions
                WHERE id NOT IN (
                    SELECT id FROM transactions t2
                    WHERE t2.player_uuid = transactions.player_uuid
                    ORDER BY t2.created_at DESC
                    LIMIT ?
                )""", perPlayer);
        }
        execute("DELETE FROM shop_notifications WHERE delivered = 1 AND created_at < ?",
                System.currentTimeMillis() - 604_800_000L);
    }

    /** A batch of statements sharing one transaction. */
    @FunctionalInterface
    public interface SqlBatch {
        void run(Connection connection) throws SQLException;
    }
}
