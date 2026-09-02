package com.sola.universalmarket.market;

import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.storage.StorageService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player purchase allowances for rare goods (spec section 26).
 *
 * Limits are PER PLAYER, never global - one player buying an Elytra must not
 * consume anyone else's allowance.
 *
 * State lives in the database keyed on (player_uuid, item_id) and is mirrored in
 * memory for fast checks. Because the source of truth is the database, relogging
 * or a restart cannot reset an allowance.
 */
public final class RareGoodsService {

    /** Result of an allowance check. */
    public record Allowance(boolean allowed, int remaining, long resetInMillis) {
        public static Allowance ok(int remaining) {
            return new Allowance(true, remaining, 0L);
        }
    }

    private record Usage(int bought, long windowStart) { }

    private final JavaPlugin plugin;
    private final StorageService storage;
    private final Map<String, Usage> cache = new ConcurrentHashMap<>();

    public RareGoodsService(JavaPlugin plugin, StorageService storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    private String cacheKey(UUID player, MarketItem item) {
        return player + "|" + item.id();
    }

    /**
     * The reset window in real milliseconds.
     *
     * Default is 7 MINECRAFT days, as the original design asked for. One
     * Minecraft day is 24000 ticks = 20 real minutes, so 7 in-game days is
     * 2h20m of real time. That is deliberately much shorter than 7 real days -
     * set rare-goods.reset-mode to REAL_HOURS in config.yml if you want the
     * longer, calendar-based behaviour instead.
     */
    public long resetWindowMillis() {
        String mode = plugin.getConfig().getString("rare-goods.reset-mode", "MINECRAFT_DAYS");
        if ("REAL_HOURS".equalsIgnoreCase(mode)) {
            return plugin.getConfig().getLong("rare-goods.reset-real-hours", 168) * 3_600_000L;
        }
        long days = plugin.getConfig().getLong("rare-goods.reset-minecraft-days", 7);
        return days * 24_000L * 50L; // 24000 ticks/day, 50ms/tick
    }

    /** Can this player buy `quantity` more of this rare item right now? */
    public Allowance checkAllowance(UUID player, MarketItem item, int quantity) {
        if (item == null || !item.rare() || item.purchaseLimit() <= 0) {
            return Allowance.ok(Integer.MAX_VALUE);
        }
        long window = item.purchaseResetTicks() > 0
                ? item.purchaseResetTicks() * 50L
                : resetWindowMillis();

        Usage usage = cache.get(cacheKey(player, item));
        long now = System.currentTimeMillis();

        if (usage == null || now - usage.windowStart() >= window) {
            // Window expired or never started: full allowance available.
            return quantity <= item.purchaseLimit()
                    ? Allowance.ok(item.purchaseLimit())
                    : new Allowance(false, item.purchaseLimit(), 0L);
        }

        int remaining = item.purchaseLimit() - usage.bought();
        if (quantity <= remaining) return Allowance.ok(remaining);

        long resetIn = window - (now - usage.windowStart());
        return new Allowance(false, Math.max(0, remaining), Math.max(0, resetIn));
    }

    /** Consume allowance after a confirmed purchase. */
    public void recordPurchase(UUID player, MarketItem item, int quantity) {
        if (item == null || !item.rare() || item.purchaseLimit() <= 0) return;

        long window = item.purchaseResetTicks() > 0
                ? item.purchaseResetTicks() * 50L
                : resetWindowMillis();
        long now = System.currentTimeMillis();

        String key = cacheKey(player, item);
        Usage existing = cache.get(key);

        Usage updated = (existing == null || now - existing.windowStart() >= window)
                ? new Usage(quantity, now)
                : new Usage(existing.bought() + quantity, existing.windowStart());

        cache.put(key, updated);
        storage.execute("""
                INSERT INTO rare_purchases (player_uuid, item_id, bought_units, window_start)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid, item_id) DO UPDATE SET
                    bought_units = ?, window_start = ?""",
                player.toString(), item.id(), updated.bought(), updated.windowStart(),
                updated.bought(), updated.windowStart());
    }

    /** Admin reset for one player (/um resetlimits). */
    /**
     * Wipe every player's rare-goods usage.
     *
     * Called when the shared cycle rolls over. Limits stay per player - one
     * person cannot eat everyone else's allowance - but the moment they all
     * refill is server-wide, which is what the countdown clock in the Rare
     * Goods menu is counting down to.
     */
    public void resetAll() {
        cache.clear();
        storage.execute("DELETE FROM rare_purchases");
        plugin.getLogger().info("Rare goods allowances reset for all players.");
    }

    public void resetPlayer(UUID player) {
        cache.keySet().removeIf(k -> k.startsWith(player + "|"));
        storage.execute("DELETE FROM rare_purchases WHERE player_uuid = ?", player.toString());
        storage.execute("DELETE FROM sell_limits WHERE player_uuid = ?", player.toString());
    }

    /** Warm the cache from disk at startup so limits survive restarts. */
    public void loadState() {
        storage.query("SELECT player_uuid, item_id, bought_units, window_start FROM rare_purchases",
                rs -> {
                    try {
                        while (rs.next()) {
                            cache.put(rs.getString("player_uuid") + "|" + rs.getString("item_id"),
                                    new Usage(rs.getInt("bought_units"), rs.getLong("window_start")));
                        }
                    } catch (Exception ignored) { }
                    return null;
                });
    }
}
