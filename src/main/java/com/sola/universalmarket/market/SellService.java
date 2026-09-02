package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selling to the server, with the inflation controls from spec section 23.
 *
 * DIMINISHING RETURNS
 *
 *   Each item has four tiers per cycle. Sell within your tier-1 allowance and
 *   you get the full buyback; past that the rate steps down, and past tier 3 it
 *   bottoms out at a floor rate. An iron farm still earns, but the tenth
 *   thousand ingot is worth a fraction of the first.
 *
 *   Allowances are stored per (player, item) in SQLite, NOT in memory. That is
 *   what closes the "relog to reset your sell limit" exploit from section 49.
 *
 *   This applies ONLY to selling to the server. Player-to-player QuickShop
 *   trade is deliberately unlimited - the server is the inflation-controlled
 *   money source, the player economy is a free market (section 23).
 *
 * EXPLOIT PROTECTION (section 18)
 *
 *   An item is only sellable if it is byte-for-byte an ordinary vanilla item:
 *   right material, no custom name, no lore, no enchantments, no persistent
 *   data. Renamed dirt, illegally enchanted gear and anything carrying plugin
 *   data is refused rather than silently valued as its base material.
 */
public final class SellService {

    private final UniversalMarketPlugin plugin;

    /** In-memory mirror of this cycle's usage; SQLite remains the source of truth. */
    private final Map<UUID, Map<String, Integer>> soldThisCycle = new ConcurrentHashMap<>();

    public SellService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Validation
    // ==================================================================

    /** The catalog entry this stack may be sold as, or null if it is not sellable. */
    public MarketItem resolveSellable(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;

        // Never, under any circumstances, buy back the Market Terminal.
        if (plugin.terminal().isTerminal(stack)) return null;

        MarketItem item = plugin.catalog().byMaterial(stack.getType());
        if (item == null || item.blacklisted()) return null;
        if (item.serverBuybackBase().signum() <= 0) return null;

        if (!isPlainVanilla(stack)) return null;
        return item;
    }

    /**
     * True only for a completely unmodified item.
     *
     * Deliberately strict. A legitimately special item (a named sword, an
     * enchanted pickaxe) is simply not sellable to the server, which is the safe
     * failure - the player keeps it. The unsafe failure would be paying dirt
     * prices for something valuable, or accepting a spoofed item as genuine.
     */
    public boolean isPlainVanilla(ItemStack stack) {
        if (!stack.hasItemMeta()) return true;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;

        if (meta.hasDisplayName()) return false;
        if (meta.hasLore()) return false;
        if (meta.hasEnchants()) return false;
        if (meta.isUnbreakable()) return false;
        if (meta.hasCustomModelData()) return false;
        if (!meta.getPersistentDataContainer().isEmpty()) return false;
        if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) return false;
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta) return false;
        return true;
    }

    // ==================================================================
    // Pricing
    // ==================================================================

    /** What the player would be paid for selling {@code quantity} right now. */
    public Quote quote(UUID player, MarketItem item, int quantity) {
        if (item == null || quantity <= 0) {
            return new Quote(BigDecimal.ZERO, BigDecimal.ZERO, 1, 0);
        }

        BigDecimal unitBase = plugin.pricing().currentBuyback(item);
        int alreadySold = usage(player, item.id());

        BigDecimal total = BigDecimal.ZERO;
        int highestTier = 1;

        // Walk unit by unit so a sale that straddles a tier boundary is priced
        // correctly rather than all at one rate.
        for (int i = 0; i < quantity; i++) {
            int position = alreadySold + i;
            double rate = rateFor(item, position);
            highestTier = Math.max(highestTier, tierFor(item, position));
            total = total.add(unitBase.multiply(BigDecimal.valueOf(rate)));
        }

        total = total.setScale(0, RoundingMode.DOWN);
        BigDecimal effectiveUnit = quantity == 0 ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(quantity), 0, RoundingMode.DOWN);

        return new Quote(total, effectiveUnit, highestTier, alreadySold);
    }

    private double rateFor(MarketItem item, int position) {
        if (!plugin.getConfig().getBoolean("sell-limits.enabled", true)) return 1.0;
        int t1 = item.sellLimitTier1();
        int t2 = t1 + item.sellLimitTier2();
        int t3 = t2 + item.sellLimitTier3();
        if (position < t1) return 1.0;
        if (position < t2) return item.tier2Rate();
        if (position < t3) return item.tier3Rate();
        return item.tierFloorRate();
    }

    private int tierFor(MarketItem item, int position) {
        int t1 = item.sellLimitTier1();
        int t2 = t1 + item.sellLimitTier2();
        int t3 = t2 + item.sellLimitTier3();
        if (position < t1) return 1;
        if (position < t2) return 2;
        if (position < t3) return 3;
        return 4;
    }

    // ==================================================================
    // Execution
    // ==================================================================

    /**
     * Sell items from the player's inventory.
     *
     * Order matters and is the reverse of a purchase: take the ITEMS first, then
     * pay. If payment fails we put the items straight back. Removing first means
     * a failure can never leave the player paid AND still holding the goods.
     *
     * @return the amount paid, or null if nothing was sold
     */
    public BigDecimal sell(Player player, MarketItem item, int quantity) {
        if (item == null || quantity <= 0) return null;

        ItemStack template = plugin.catalog().createApprovedStack(item, 1);
        if (template == null) return null;

        int available = countSellable(player, item);
        int toSell = Math.min(quantity, available);
        if (toSell <= 0) return null;

        Quote quote = quote(player.getUniqueId(), item, toSell);
        if (quote.total().signum() <= 0) return null;

        int removed = removeSellable(player, item, toSell);
        if (removed <= 0) return null;

        // Re-quote against what we actually removed, in case it differed.
        Quote finalQuote = removed == toSell ? quote : quote(player.getUniqueId(), item, removed);

        if (!plugin.economy().deposit(player, finalQuote.total())) {
            // Payment failed: hand the items back. The player loses nothing.
            ItemStack refund = plugin.catalog().createApprovedStack(item, removed);
            if (refund != null) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(refund);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            plugin.getLogger().warning("Sell payment failed for " + player.getName()
                    + "; returned " + removed + "x " + item.id());
            return null;
        }

        recordUsage(player.getUniqueId(), item.id(), removed);
        plugin.pricing().onPlayerSold(item, removed);
        plugin.transactions().recordSale(player.getUniqueId(), item.id(), removed, finalQuote.total());
        return finalQuote.total();
    }

    /** How many of this item the player holds in a sellable, unmodified state. */
    public int countSellable(Player player, MarketItem item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null) continue;
            if (stack.getType() != item.material()) continue;
            if (!isPlainVanilla(stack)) continue;
            if (plugin.terminal().isTerminal(stack)) continue;
            count += stack.getAmount();
        }
        return count;
    }

    private int removeSellable(Player player, MarketItem item, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            if (stack.getType() != item.material()) continue;
            if (!isPlainVanilla(stack)) continue;
            if (plugin.terminal().isTerminal(stack)) continue;

            int take = Math.min(remaining, stack.getAmount());
            if (take >= stack.getAmount()) contents[i] = null;
            else stack.setAmount(stack.getAmount() - take);
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        return amount - remaining;
    }

    // ==================================================================
    // Allowance tracking
    // ==================================================================

    public int usage(UUID player, String itemId) {
        return soldThisCycle.getOrDefault(player, Map.of()).getOrDefault(itemId, 0);
    }

    /**
     * Record allowance usage for a sale made outside this class.
     * SellMenu removes the items itself, so it has to report the usage or the
     * diminishing return tiers would never advance for deposit-box sales.
     */
    public void noteSold(UUID player, MarketItem item, int amount) {
        recordUsage(player, item.id(), amount);
    }

    private void recordUsage(UUID player, String itemId, int amount) {
        soldThisCycle.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .merge(itemId, amount, Integer::sum);

        long cycleStart = System.currentTimeMillis();
        plugin.storage().execute("""
                INSERT INTO sell_limits (player_uuid, item_id, sold_units, cycle_start)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid, item_id) DO UPDATE SET
                    sold_units = sold_units + ?""",
                player.toString(), itemId, amount, cycleStart, amount);
    }

    /** Restore allowances after a restart so relogging cannot reset them. */
    public void loadState() {
        long window = plugin.getConfig().getLong("sell-limits.reset-hours", 24) * 3_600_000L;
        long cutoff = System.currentTimeMillis() - window;

        plugin.storage().execute("DELETE FROM sell_limits WHERE cycle_start < ?", cutoff);
        plugin.storage().query(
                "SELECT player_uuid, item_id, sold_units FROM sell_limits WHERE cycle_start >= ?",
                rs -> {
                    Map<UUID, Map<String, Integer>> loaded = new HashMap<>();
                    try {
                        while (rs.next()) {
                            UUID id = UUID.fromString(rs.getString("player_uuid"));
                            loaded.computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                                    .put(rs.getString("item_id"), rs.getInt("sold_units"));
                        }
                    } catch (Exception ignored) { }
                    return loaded;
                }, cutoff)
                .thenAccept(loaded -> {
                    if (loaded != null) soldThisCycle.putAll(loaded);
                });
    }

    public void resetPlayer(UUID player) {
        soldThisCycle.remove(player);
        plugin.storage().execute("DELETE FROM sell_limits WHERE player_uuid = ?", player.toString());
    }

    /** A priced sell offer. */
    public record Quote(BigDecimal total, BigDecimal perItem, int tier, int alreadySold) {
        public String describeTier() {
            return switch (tier) {
                case 1 -> "<green>Tier 1 <gray>(full rate)";
                case 2 -> "<yellow>Tier 2 <gray>(reduced)";
                case 3 -> "<gold>Tier 3 <gray>(heavily reduced)";
                default -> "<red>Floor rate <gray>(allowance used up)";
            };
        }
    }
}
