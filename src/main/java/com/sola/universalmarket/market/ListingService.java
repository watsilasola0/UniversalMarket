package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player listings: the virtual replacement for QuickShop chest shops.
 *
 * Goods are surrendered when the listing is created and held by the server, so a
 * buyer can never be sold something the seller no longer has. That is the whole
 * reason this is safe to do without the physical chest: there is no stock to go
 * stale, no unloaded chunk, and no shop that quietly emptied.
 *
 * DELIBERATE RULES YOU ASKED FOR
 *   - No cancelling. Once listed, the goods are committed.
 *   - No refunds.
 *   - Sellers are paid the instant a buyer completes a purchase.
 *
 *   Because of those three, creating a listing is irreversible, so the sell flow
 *   asks for explicit confirmation before it ever takes the items.
 *
 * Listings live in memory AND in SQLite. The in-memory copy is what menus read,
 * so browsing never touches the database; the database is what survives restarts.
 */
public final class ListingService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<Long, Listing> listings = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public ListingService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    /** One player's offer of a stack of goods at a fixed per-item price. */
    public static final class Listing {
        public final long id;
        public final UUID seller;
        public final String sellerName;
        public final String itemId;
        public final BigDecimal pricePerItem;
        private volatile int remaining;
        public final long createdAt;

        Listing(long id, UUID seller, String sellerName, String itemId,
                BigDecimal pricePerItem, int remaining, long createdAt) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.itemId = itemId;
            this.pricePerItem = pricePerItem;
            this.remaining = remaining;
            this.createdAt = createdAt;
        }

        public int remaining() { return remaining; }
        public boolean soldOut() { return remaining <= 0; }

        /** Reserve units atomically so two buyers cannot claim the same goods. */
        synchronized int take(int wanted) {
            int taken = Math.min(wanted, remaining);
            remaining -= taken;
            return taken;
        }

        synchronized void restore(int amount) {
            remaining += amount;
        }
    }

    // ==================================================================
    // Loading
    // ==================================================================

    public void load() {
        plugin.storage().query("""
                SELECT id, seller_uuid, seller_name, item_id, price_per_item,
                       remaining, created_at
                FROM listings WHERE remaining > 0""",
                rs -> {
                    List<Listing> out = new ArrayList<>();
                    try {
                        while (rs.next()) {
                            out.add(new Listing(
                                    rs.getLong("id"),
                                    UUID.fromString(rs.getString("seller_uuid")),
                                    rs.getString("seller_name"),
                                    rs.getString("item_id"),
                                    new BigDecimal(rs.getString("price_per_item")),
                                    rs.getInt("remaining"),
                                    rs.getLong("created_at")));
                        }
                    } catch (Exception ignored) { }
                    return out;
                }).thenAccept(loaded -> {
                    if (loaded == null) return;
                    long highest = 0;
                    for (Listing listing : loaded) {
                        listings.put(listing.id, listing);
                        highest = Math.max(highest, listing.id);
                    }
                    nextId.set(highest + 1);
                    plugin.getLogger().info("Loaded " + listings.size() + " player listings.");
                });
    }

    // ==================================================================
    // Creating
    // ==================================================================

    /**
     * Create a listing. The caller must ALREADY have removed the goods from the
     * seller's inventory - this method does not touch inventories, so there is
     * no path where the items exist in both places at once.
     */
    public Listing create(Player seller, MarketItem item, int quantity, BigDecimal pricePerItem) {
        long id = nextId.getAndIncrement();
        Listing listing = new Listing(id, seller.getUniqueId(), seller.getName(),
                item.id(), pricePerItem, quantity, System.currentTimeMillis());
        listings.put(id, listing);

        plugin.storage().execute("""
                INSERT INTO listings
                    (id, seller_uuid, seller_name, item_id, price_per_item, remaining, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, seller.getUniqueId().toString(), seller.getName(), item.id(),
                pricePerItem.toPlainString(), quantity, listing.createdAt);

        // You asked for new stock to be announced server-wide.
        if (plugin.getConfig().getBoolean("listings.announce-new", true)) {
            String message = plugin.messages().get("listing.announced")
                    .replace("%player%", seller.getName())
                    .replace("%qty%", NumberFormatter.count(quantity))
                    .replace("%item%", item.displayName())
                    .replace("%price%", NumberFormatter.money(pricePerItem));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(mm.deserialize(message));
            }
        }
        return listing;
    }

    // ==================================================================
    // Buying
    // ==================================================================

    public record Purchase(boolean success, String messageKey, int quantity, BigDecimal total) {
        static Purchase fail(String key) {
            return new Purchase(false, key, 0, BigDecimal.ZERO);
        }
    }

    /**
     * Buy from a listing.
     *
     * Stock is reserved BEFORE money moves, and returned if anything downstream
     * fails. Reserving first means two players clicking at the same instant
     * cannot both buy the last item.
     */
    public Purchase buy(Player buyer, long listingId, int wanted) {
        Listing listing = listings.get(listingId);
        if (listing == null || listing.soldOut()) return Purchase.fail("listing.sold-out");
        if (listing.seller.equals(buyer.getUniqueId())) return Purchase.fail("listing.own-listing");

        MarketItem item = plugin.catalog().byId(listing.itemId);
        if (item == null) return Purchase.fail("listing.unavailable");

        int taken = listing.take(Math.max(1, wanted));
        if (taken <= 0) return Purchase.fail("listing.sold-out");

        BigDecimal total = listing.pricePerItem
                .multiply(BigDecimal.valueOf(taken))
                .setScale(0, RoundingMode.DOWN);

        ItemStack stack = plugin.catalog().createApprovedStack(item, taken);
        if (stack == null) {
            listing.restore(taken);
            return Purchase.fail("listing.unavailable");
        }
        if (!hasSpace(buyer, stack)) {
            listing.restore(taken);
            return Purchase.fail("buy.inventory-full");
        }
        if (!plugin.economy().withdraw(buyer, total)) {
            listing.restore(taken);
            return Purchase.fail("buy.insufficient");
        }

        Map<Integer, ItemStack> leftover = buyer.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            for (ItemStack rem : leftover.values()) buyer.getInventory().removeItem(rem);
            plugin.economy().deposit(buyer, total);
            listing.restore(taken);
            return Purchase.fail("buy.inventory-full");
        }

        // Seller is paid immediately, as you specified.
        org.bukkit.OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller);
        if (!plugin.economy().deposit(seller, total)) {
            plugin.getLogger().severe("CRITICAL: could not pay " + listing.sellerName
                    + " " + NumberFormatter.exactMoney(total)
                    + " for listing " + listingId + " - manual correction required.");
        }

        persistRemaining(listing);
        plugin.transactions().recordPurchase(buyer.getUniqueId(), item.id(), taken, total);
        plugin.transactions().recordShopRevenue(listing.seller, item.id(), taken, total);

        // Tell the seller directly, online or on next login.
        notifySeller(listing, buyer.getName(), item.displayName(), taken, total);

        if (listing.soldOut()) listings.remove(listingId);
        return new Purchase(true, "listing.bought", taken, total);
    }

    private void notifySeller(Listing listing, String buyerName,
                              String itemName, int quantity, BigDecimal total) {
        Player online = Bukkit.getPlayer(listing.seller);
        if (online != null) {
            online.sendMessage(mm.deserialize(plugin.messages().get("listing.sold")
                    .replace("%buyer%", buyerName)
                    .replace("%qty%", String.valueOf(quantity))
                    .replace("%item%", itemName)
                    .replace("%amount%", NumberFormatter.money(total))));
            plugin.sounds().money(online);
        } else {
            plugin.storage().execute("""
                    INSERT INTO shop_notifications
                        (owner_uuid, item_id, quantity, amount, created_at, delivered)
                    VALUES (?, ?, ?, ?, ?, 0)""",
                    listing.seller.toString(), listing.itemId, quantity,
                    total.toPlainString(), System.currentTimeMillis());
        }
    }

    private void persistRemaining(Listing listing) {
        if (listing.soldOut()) {
            plugin.storage().execute("DELETE FROM listings WHERE id = ?", listing.id);
        } else {
            plugin.storage().execute("UPDATE listings SET remaining = ? WHERE id = ?",
                    listing.remaining(), listing.id);
        }
    }

    private boolean hasSpace(Player player, ItemStack stack) {
        int needed = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == org.bukkit.Material.AIR) needed -= max;
            else if (slot.isSimilar(stack)) needed -= Math.max(0, max - slot.getAmount());
            if (needed <= 0) return true;
        }
        return needed <= 0;
    }

    // ==================================================================
    // Queries
    // ==================================================================

    public List<Listing> all() {
        List<Listing> out = new ArrayList<>(listings.values());
        out.removeIf(Listing::soldOut);
        return out;
    }

    /** Every seller who currently has stock, most valuable inventory first. */
    public List<UUID> sellers() {
        Map<UUID, BigDecimal> totals = new java.util.HashMap<>();
        for (Listing listing : all()) {
            totals.merge(listing.seller,
                    listing.pricePerItem.multiply(BigDecimal.valueOf(listing.remaining())),
                    BigDecimal::add);
        }
        List<UUID> out = new ArrayList<>(totals.keySet());
        out.sort((a, b) -> totals.get(b).compareTo(totals.get(a)));
        return out;
    }

    public List<Listing> bySeller(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : all()) {
            if (listing.seller.equals(seller)) out.add(listing);
        }
        out.sort(Comparator.comparing(l -> l.itemId));
        return out;
    }

    public String sellerName(UUID seller) {
        for (Listing listing : all()) {
            if (listing.seller.equals(seller)) return listing.sellerName;
        }
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(seller);
        return offline.getName() == null ? "Unknown" : offline.getName();
    }

    /** Cheapest current listing for an item, used for the undercut hint. */
    public Listing cheapestFor(String itemId) {
        Listing best = null;
        for (Listing listing : all()) {
            if (!listing.itemId.equals(itemId)) continue;
            if (best == null || listing.pricePerItem.compareTo(best.pricePerItem) < 0) {
                best = listing;
            }
        }
        return best;
    }

    public int size() {
        return all().size();
    }

    // ==================================================================
    // Suggested pricing
    // ==================================================================

    /**
     * Three suggested per-item prices, all below the Universal Market.
     *
     * The point of the player economy is undercutting the server (spec 19/55),
     * so every suggestion sits under the UM price and above the server buyback.
     * Selling above UM is still allowed via custom, it just will not sell.
     */
    public BigDecimal[] suggestedPrices(MarketItem item) {
        BigDecimal um = plugin.pricing().currentBuyPrice(item);
        double low  = plugin.getConfig().getDouble("listings.suggested.low", 0.45);
        double mid  = plugin.getConfig().getDouble("listings.suggested.mid", 0.60);
        double high = plugin.getConfig().getDouble("listings.suggested.high", 0.78);
        return new BigDecimal[]{
                um.multiply(BigDecimal.valueOf(low)).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE),
                um.multiply(BigDecimal.valueOf(mid)).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE),
                um.multiply(BigDecimal.valueOf(high)).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE)
        };
    }
}
