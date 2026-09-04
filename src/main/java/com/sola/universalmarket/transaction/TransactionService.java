package com.sola.universalmarket.transaction;

import com.sola.universalmarket.storage.StorageService;
import com.sola.universalmarket.util.NumberFormatter;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Records what happened and answers "what did I do lately".
 *
 * Recording is fire-and-forget by design. A failed history write must never
 * roll back a completed purchase - the money and the item have already moved,
 * and undoing them because a log line failed would be far worse than a missing
 * log line. Failures are logged by StorageService and the game continues.
 *
 * Lifetime stats are updated in the SAME database transaction as the history
 * row, so the two can never drift apart.
 */
public final class TransactionService {

    public enum Type {
        UM_PURCHASE,      // bought from the Universal Market
        SERVER_SALE,      // sold to the server
        PAYMENT_SENT,
        PAYMENT_RECEIVED,
        PAYMENT_FEE,
        CONTRACT_REWARD,
        SHOP_REVENUE,     // earned through a QuickShop chest shop
        ADMIN_ADJUST
    }

    /** One row of history, as shown on the My Account screen. */
    public record Record(long id, UUID player, Type type, String itemId,
                         int quantity, BigDecimal amount, String counterparty,
                         long createdAt) {

        /** True when this line put money INTO the player's pocket. */
        public boolean isCredit() {
            return type == Type.SERVER_SALE
                    || type == Type.PAYMENT_RECEIVED
                    || type == Type.CONTRACT_REWARD
                    || type == Type.SHOP_REVENUE;
        }

        /** "- $70K" / "+ $9,000" as shown in the account list. */
        public String signedAmount() {
            return (isCredit() ? "+ " : "- ") + NumberFormatter.money(amount);
        }
    }

    /** Aggregate figures for the account page. */
    public record Stats(BigDecimal earned, BigDecimal spent, int purchases,
                        int sales, BigDecimal feesPaid, BigDecimal shopRevenue) {
        public static Stats empty() {
            return new Stats(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private final StorageService storage;

    public TransactionService(StorageService storage) {
        this.storage = storage;
    }

    // ==================================================================
    // Recording
    // ==================================================================

    public void recordPurchase(UUID player, String itemId, int quantity, BigDecimal amount) {
        record(player, Type.UM_PURCHASE, itemId, quantity, amount, null);
    }

    public void recordSale(UUID player, String itemId, int quantity, BigDecimal amount) {
        record(player, Type.SERVER_SALE, itemId, quantity, amount, null);
    }

    public void recordContractReward(UUID player, String contractId, BigDecimal amount) {
        record(player, Type.CONTRACT_REWARD, contractId, 1, amount, null);
    }

    public void recordShopRevenue(UUID owner, String itemId, int quantity, BigDecimal amount) {
        record(owner, Type.SHOP_REVENUE, itemId, quantity, amount, null);
    }

    /**
     * A player-to-player payment writes three rows in one database transaction:
     * the sender's debit, the fee, and the recipient's credit. All three land
     * together or none do, so history can never show a payment with no matching
     * receipt.
     */
    public void recordPayment(UUID sender, String senderName,
                              UUID recipient, String recipientName,
                              BigDecimal amount, BigDecimal fee) {
        long now = System.currentTimeMillis();
        storage.transaction(conn -> {
            insert(conn, sender, Type.PAYMENT_SENT, null, 1, amount, recipientName, now);
            if (fee.signum() > 0) {
                insert(conn, sender, Type.PAYMENT_FEE, null, 1, fee, recipientName, now);
            }
            insert(conn, recipient, Type.PAYMENT_RECEIVED, null, 1, amount, senderName, now);

            bumpStats(conn, sender, BigDecimal.ZERO, amount.add(fee), 0, 0, fee, BigDecimal.ZERO, now);
            bumpStats(conn, recipient, amount, BigDecimal.ZERO, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, now);
        });
    }

    private void record(UUID player, Type type, String itemId,
                        int quantity, BigDecimal amount, String counterparty) {
        long now = System.currentTimeMillis();
        BigDecimal safe = amount == null ? BigDecimal.ZERO : NumberFormatter.toWholeDollars(amount);

        boolean credit = type == Type.SERVER_SALE || type == Type.CONTRACT_REWARD
                || type == Type.SHOP_REVENUE || type == Type.PAYMENT_RECEIVED;

        storage.transaction(conn -> {
            insert(conn, player, type, itemId, quantity, safe, counterparty, now);
            bumpStats(conn, player,
                    credit ? safe : BigDecimal.ZERO,
                    credit ? BigDecimal.ZERO : safe,
                    type == Type.UM_PURCHASE ? 1 : 0,
                    type == Type.SERVER_SALE ? 1 : 0,
                    BigDecimal.ZERO,
                    type == Type.SHOP_REVENUE ? safe : BigDecimal.ZERO,
                    now);
        });
    }

    private void insert(java.sql.Connection conn, UUID player, Type type, String itemId,
                        int quantity, BigDecimal amount, String counterparty, long now)
            throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO transactions
                    (player_uuid, type, item_id, quantity, amount, counterparty, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, player.toString());
            ps.setString(2, type.name());
            ps.setString(3, itemId);
            ps.setInt(4, quantity);
            ps.setString(5, amount.toPlainString());
            ps.setString(6, counterparty);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    /**
     * Add to a player's lifetime totals, creating the row if absent.
     *
     * The addition happens in SQL against the stored TEXT value cast to a
     * numeric, then written back as text. Doing it in one statement avoids a
     * read-modify-write race between two queued operations.
     */
    private void bumpStats(java.sql.Connection conn, UUID player,
                           BigDecimal earned, BigDecimal spent,
                           int purchases, int sales,
                           BigDecimal fees, BigDecimal shopRevenue, long now)
            throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO lifetime_stats
                    (player_uuid, total_earned, total_spent, um_purchases,
                     server_sales, fees_paid, shop_revenue, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    total_earned = CAST(CAST(total_earned AS INTEGER) + ? AS TEXT),
                    total_spent  = CAST(CAST(total_spent  AS INTEGER) + ? AS TEXT),
                    um_purchases = um_purchases + ?,
                    server_sales = server_sales + ?,
                    fees_paid    = CAST(CAST(fees_paid    AS INTEGER) + ? AS TEXT),
                    shop_revenue = CAST(CAST(shop_revenue AS INTEGER) + ? AS TEXT),
                    updated_at   = ?""")) {
            ps.setString(1, player.toString());
            ps.setString(2, earned.toPlainString());
            ps.setString(3, spent.toPlainString());
            ps.setInt(4, purchases);
            ps.setInt(5, sales);
            ps.setString(6, fees.toPlainString());
            ps.setString(7, shopRevenue.toPlainString());
            ps.setLong(8, now);
            // update branch
            ps.setLong(9, earned.longValue());
            ps.setLong(10, spent.longValue());
            ps.setInt(11, purchases);
            ps.setInt(12, sales);
            ps.setLong(13, fees.longValue());
            ps.setLong(14, shopRevenue.longValue());
            ps.setLong(15, now);
            ps.executeUpdate();
        }
    }

    // ==================================================================
    // Reads
    // ==================================================================

    /** Most recent history for the My Account screen. */
    public CompletableFuture<List<Record>> recent(UUID player, int limit) {
        return storage.query("""
                SELECT id, type, item_id, quantity, amount, counterparty, created_at
                FROM transactions WHERE player_uuid = ?
                ORDER BY created_at DESC LIMIT ?""",
                rs -> {
                    List<Record> out = new ArrayList<>();
                    try {
                        while (rs.next()) {
                            out.add(new Record(
                                    rs.getLong("id"),
                                    player,
                                    parseType(rs.getString("type")),
                                    rs.getString("item_id"),
                                    rs.getInt("quantity"),
                                    new BigDecimal(rs.getString("amount")),
                                    rs.getString("counterparty"),
                                    rs.getLong("created_at")));
                        }
                    } catch (Exception ignored) { }
                    return out;
                },
                player.toString(), limit)
                .thenApply(list -> list == null ? new ArrayList<>() : list);
    }

    public CompletableFuture<Stats> stats(UUID player) {
        return storage.query("""
                SELECT total_earned, total_spent, um_purchases,
                       server_sales, fees_paid, shop_revenue
                FROM lifetime_stats WHERE player_uuid = ?""",
                rs -> {
                    try {
                        if (!rs.next()) return Stats.empty();
                        return new Stats(
                                new BigDecimal(rs.getString("total_earned")),
                                new BigDecimal(rs.getString("total_spent")),
                                rs.getInt("um_purchases"),
                                rs.getInt("server_sales"),
                                new BigDecimal(rs.getString("fees_paid")),
                                new BigDecimal(rs.getString("shop_revenue")));
                    } catch (Exception e) {
                        return Stats.empty();
                    }
                },
                player.toString())
                .thenApply(s -> s == null ? Stats.empty() : s);
    }

    /** Lifetime-earned leaderboard, used alongside the balance board. */
    public CompletableFuture<List<java.util.Map.Entry<UUID, BigDecimal>>> topEarners(int limit) {
        return storage.query("""
                SELECT player_uuid, total_earned FROM lifetime_stats
                ORDER BY CAST(total_earned AS INTEGER) DESC LIMIT ?""",
                rs -> {
                    List<java.util.Map.Entry<UUID, BigDecimal>> out = new ArrayList<>();
                    try {
                        while (rs.next()) {
                            out.add(java.util.Map.entry(
                                    UUID.fromString(rs.getString("player_uuid")),
                                    new BigDecimal(rs.getString("total_earned"))));
                        }
                    } catch (Exception ignored) { }
                    return out;
                }, limit)
                .thenApply(l -> l == null ? new ArrayList<>() : l);
    }

    private Type parseType(String raw) {
        try { return Type.valueOf(raw); }
        catch (Exception e) { return Type.ADMIN_ADJUST; }
    }

    /** Remember a player's name so leaderboards work while they are offline. */
    public void touchName(UUID player, String name) {
        storage.execute("""
                INSERT INTO lifetime_stats (player_uuid, player_name, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET player_name = ?""",
                player.toString(), name, System.currentTimeMillis(), name);
    }
}
