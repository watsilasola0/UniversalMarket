package com.sola.universalmarket.market;

import com.sola.universalmarket.catalog.MarketCatalog;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.storage.StorageService;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Prices, daily deals and high demand.
 *
 * THE ONE INVARIANT THAT MATTERS MOST:
 *
 *   buyback < buy price, always, under every combination of modifiers.
 *
 *   Deals push the buy price DOWN. High demand pushes the buyback UP. Applied
 *   naively, an item that is simultaneously discounted and in demand could end
 *   up paying more to sell than it costs to buy - an infinite money loop that
 *   one player could run all night. So the final buyback is hard-clamped to a
 *   fraction of the CURRENT (post-discount) buy price, not the base price.
 *   That clamp is the last thing applied and nothing can bypass it.
 */
public final class PricingService {

    /** Current cycle's discounts, keyed by item id. value = fraction off, e.g. 0.20 */
    private final Map<String, Double> dailyDeals = new ConcurrentHashMap<>();
    /** Current cycle's demand bonuses. value = fraction added, e.g. 0.35 */
    private final Map<String, Double> highDemand = new ConcurrentHashMap<>();
    /** Persisted dynamic buyback state, keyed by item id. */
    private final Map<String, BigDecimal> buybackState = new ConcurrentHashMap<>();
    /** Units sold to the server this cycle, drives the downward price drift. */
    private final Map<String, Integer> soldThisCycle = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final MarketCatalog catalog;
    private final StorageService storage;

    private volatile long cycleStartedAt = System.currentTimeMillis();

    public PricingService(JavaPlugin plugin, MarketCatalog catalog, StorageService storage) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.storage = storage;
    }

    // ==================================================================
    // Buy price
    // ==================================================================

    /** What the Universal Market charges right now, per single item. */
    public BigDecimal currentBuyPrice(MarketItem item) {
        if (item == null) return BigDecimal.ZERO;
        BigDecimal base = item.umBuyPrice();
        double discount = dealDiscount(item);
        if (discount <= 0) return round(base);

        BigDecimal multiplier = BigDecimal.valueOf(1.0d - discount);
        BigDecimal discounted = base.multiply(multiplier);
        // A deal must never make something free or negative.
        BigDecimal floor = base.multiply(BigDecimal.valueOf(0.25));
        if (discounted.compareTo(floor) < 0) discounted = floor;
        return round(discounted.max(BigDecimal.ONE));
    }

    /** Active discount fraction for an item, 0 when it is not on offer. */
    public double dealDiscount(MarketItem item) {
        Double d = dailyDeals.get(item.id());
        if (d == null) return 0d;
        return Math.min(d, item.maxDiscount());
    }

    // ==================================================================
    // Buyback price
    // ==================================================================

    /**
     * What the server pays per single item right now, before per-player
     * diminishing returns are applied by SellLimitService.
     */
    public BigDecimal currentBuyback(MarketItem item) {
        if (item == null) return BigDecimal.ZERO;

        BigDecimal base = buybackState.getOrDefault(item.id(), item.serverBuybackBase());

        // High demand bonus
        Double bonus = highDemand.get(item.id());
        if (bonus != null) {
            double capped = Math.min(bonus, item.maxDemandBonus());
            base = base.multiply(BigDecimal.valueOf(1.0d + capped));
        }

        // Configured floor and ceiling from market.yml always win.
        if (item.priceFloor().signum() > 0 && base.compareTo(item.priceFloor()) < 0) {
            base = item.priceFloor();
        }
        if (item.priceCeiling().signum() > 0 && base.compareTo(item.priceCeiling()) > 0) {
            base = item.priceCeiling();
        }

        // THE ARBITRAGE CLAMP. Applied last, against the CURRENT buy price, so a
        // deal plus a demand spike still cannot produce a profitable loop.
        double maxRatio = plugin.getConfig()
                .getDouble("dynamic-pricing.max-buyback-vs-buy-ratio", 0.60);
        BigDecimal ceiling = currentBuyPrice(item).multiply(BigDecimal.valueOf(maxRatio));
        if (base.compareTo(ceiling) > 0) base = ceiling;

        return round(base.max(BigDecimal.ZERO));
    }

    public double demandBonus(MarketItem item) {
        Double b = highDemand.get(item.id());
        return b == null ? 0d : Math.min(b, item.maxDemandBonus());
    }

    // ==================================================================
    // Market pressure
    // ==================================================================

    /** Called after a player buys from the market. Buying does not move prices. */
    public void onPlayerBought(MarketItem item, int quantity) {
        // Deliberately a no-op on price. Only SELLING to the server moves the
        // buyback, because that is where money enters the economy and therefore
        // where inflation control belongs.
    }

    /** Called after a player sells to the server: pushes the buyback down. */
    public void onPlayerSold(MarketItem item, int quantity) {
        if (item == null || !item.dynamicPricing()) return;
        if (!plugin.getConfig().getBoolean("dynamic-pricing.enabled", true)) return;

        soldThisCycle.merge(item.id(), quantity, Integer::sum);

        int allowance = Math.max(1, item.sellLimitTier1());
        double dropPer = plugin.getConfig().getDouble("dynamic-pricing.drop-per-allowance", 0.05);
        double drop = (quantity / (double) allowance) * dropPer;

        BigDecimal current = buybackState.getOrDefault(item.id(), item.serverBuybackBase());
        BigDecimal next = current.multiply(BigDecimal.valueOf(1.0d - drop));
        buybackState.put(item.id(), clampDrift(item, next));
        persist(item);
    }

    /** Slow recovery each cycle for items nobody is dumping. */
    public void applyRecovery() {
        double recovery = plugin.getConfig().getDouble("dynamic-pricing.recovery-per-cycle", 0.08);
        for (MarketItem item : catalog.all()) {
            if (!item.dynamicPricing()) continue;
            BigDecimal current = buybackState.get(item.id());
            if (current == null) continue;
            BigDecimal target = item.serverBuybackBase();
            if (current.compareTo(target) >= 0) continue;

            BigDecimal gap = target.subtract(current);
            BigDecimal next = current.add(gap.multiply(BigDecimal.valueOf(recovery)));
            buybackState.put(item.id(), clampDrift(item, next));
        }
        soldThisCycle.clear();
    }

    /** Keep dynamic movement inside the configured drift band. */
    private BigDecimal clampDrift(MarketItem item, BigDecimal candidate) {
        double maxDrift = plugin.getConfig().getDouble("dynamic-pricing.max-drift", 0.40);
        BigDecimal base = item.serverBuybackBase();
        BigDecimal low = base.multiply(BigDecimal.valueOf(1.0d - maxDrift));
        BigDecimal high = base.multiply(BigDecimal.valueOf(1.0d + maxDrift));
        if (candidate.compareTo(low) < 0) candidate = low;
        if (candidate.compareTo(high) > 0) candidate = high;
        // Never zero, never negative - a free rare good would be catastrophic.
        return candidate.max(BigDecimal.ONE);
    }

    // ==================================================================
    // Cycle rotation
    // ==================================================================

    /** Pick a fresh set of deals and demand items. */
    public void rollCycle() {
        dailyDeals.clear();
        highDemand.clear();

        var config = plugin.getConfig();
        int dealCount = config.getInt("market-cycle.daily-deals.count", 9);
        int demandCount = config.getInt("market-cycle.high-demand.count", 4);

        List<MarketItem> dealPool = new ArrayList<>();
        List<MarketItem> demandPool = new ArrayList<>();
        for (MarketItem item : catalog.all()) {
            if (item.dailyDealEligible() && item.dailyDealWeight() > 0) dealPool.add(item);
            if (item.highDemandEligible() && item.highDemandWeight() > 0) demandPool.add(item);
        }

        double dMin = config.getDouble("market-cycle.daily-deals.common-discount-min", 0.10);
        double dMax = config.getDouble("market-cycle.daily-deals.common-discount-max", 0.30);
        double rMin = config.getDouble("market-cycle.daily-deals.rare-discount-min", 0.05);
        double rMax = config.getDouble("market-cycle.daily-deals.rare-discount-max", 0.15);

        for (MarketItem item : weightedPick(dealPool, dealCount, true)) {
            boolean pricey = item.umBuyPrice().compareTo(BigDecimal.valueOf(5_000_000L)) >= 0;
            double discount = pricey ? random(rMin, rMax) : random(dMin, dMax);
            dailyDeals.put(item.id(), Math.min(discount, item.maxDiscount()));
        }

        double bMin = config.getDouble("market-cycle.high-demand.bonus-min", 0.20);
        double bMax = config.getDouble("market-cycle.high-demand.bonus-max", 0.45);
        for (MarketItem item : weightedPick(demandPool, demandCount, false)) {
            highDemand.put(item.id(), Math.min(random(bMin, bMax), item.maxDemandBonus()));
        }

        applyRecovery();
        cycleStartedAt = System.currentTimeMillis();
        saveCycleState();

        plugin.getLogger().info("Market cycle rolled: " + dailyDeals.size()
                + " deals, " + highDemand.size() + " high-demand items.");
    }

    /**
     * Weighted selection without replacement. Weight comes from market.yml, so
     * Elytra at weight 0.02 shows up roughly fifty times less often than stone
     * at weight 1.0 - which is what "should almost never appear" means in
     * practice rather than a special case in code.
     */
    private List<MarketItem> weightedPick(List<MarketItem> pool, int count, boolean deals) {
        List<MarketItem> chosen = new ArrayList<>();
        if (pool.isEmpty()) return chosen;
        List<MarketItem> remaining = new ArrayList<>(pool);

        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            double total = 0;
            for (MarketItem item : remaining) {
                total += deals ? item.dailyDealWeight() : item.highDemandWeight();
            }
            if (total <= 0) break;

            double roll = ThreadLocalRandom.current().nextDouble(total);
            double running = 0;
            MarketItem picked = remaining.get(remaining.size() - 1);
            for (MarketItem item : remaining) {
                running += deals ? item.dailyDealWeight() : item.highDemandWeight();
                if (running >= roll) { picked = item; break; }
            }
            chosen.add(picked);
            remaining.remove(picked);
        }
        return chosen;
    }

    private double random(double min, double max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public long cycleEndsInMillis() {
        long hours = plugin.getConfig().getLong("market-cycle.hours", 24);
        return (cycleStartedAt + hours * 3_600_000L) - System.currentTimeMillis();
    }

    public Map<String, Double> deals() { return new HashMap<>(dailyDeals); }
    public Map<String, Double> demand() { return new HashMap<>(highDemand); }

    // ==================================================================
    // Persistence
    // ==================================================================

    private void persist(MarketItem item) {
        BigDecimal value = buybackState.get(item.id());
        if (value == null) return;
        storage.execute("""
                INSERT INTO price_state (item_id, current_buyback, sold_this_cycle, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET
                    current_buyback = ?, sold_this_cycle = ?, updated_at = ?""",
                item.id(), value, soldThisCycle.getOrDefault(item.id(), 0),
                System.currentTimeMillis(),
                value, soldThisCycle.getOrDefault(item.id(), 0), System.currentTimeMillis());
    }

    private void saveCycleState() {
        StringBuilder dealBlob = new StringBuilder();
        dailyDeals.forEach((id, d) -> dealBlob.append(id).append('=').append(d).append(';'));
        StringBuilder demandBlob = new StringBuilder();
        highDemand.forEach((id, d) -> demandBlob.append(id).append('=').append(d).append(';'));

        writeState("daily_deals", dealBlob.toString());
        writeState("high_demand", demandBlob.toString());
        writeState("cycle_started", String.valueOf(cycleStartedAt));
    }

    private void writeState(String key, String value) {
        storage.execute("""
                INSERT INTO market_state (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = ?""",
                key, value, System.currentTimeMillis(), value, System.currentTimeMillis());
    }

    /** Restore deals, demand and dynamic prices after a restart (spec section 22). */
    public void loadState() {
        storage.query("SELECT item_id, current_buyback, sold_this_cycle FROM price_state",
                rs -> {
                    try {
                        while (rs.next()) {
                            buybackState.put(rs.getString("item_id"),
                                    new BigDecimal(rs.getString("current_buyback")));
                            soldThisCycle.put(rs.getString("item_id"),
                                    rs.getInt("sold_this_cycle"));
                        }
                    } catch (Exception ignored) { }
                    return null;
                });

        storage.query("SELECT key, value FROM market_state", rs -> {
            try {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    switch (key) {
                        case "daily_deals" -> parseBlob(value, dailyDeals);
                        case "high_demand" -> parseBlob(value, highDemand);
                        case "cycle_started" -> cycleStartedAt = Long.parseLong(value);
                        default -> { }
                    }
                }
            } catch (Exception ignored) { }
            return null;
        });
    }

    private void parseBlob(String blob, Map<String, Double> target) {
        if (blob == null || blob.isBlank()) return;
        for (String entry : blob.split(";")) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                target.put(entry.substring(0, eq), Double.parseDouble(entry.substring(eq + 1)));
            } catch (NumberFormatException ignored) { }
        }
    }

    private BigDecimal round(BigDecimal v) {
        return v.setScale(0, RoundingMode.DOWN);
    }
}
