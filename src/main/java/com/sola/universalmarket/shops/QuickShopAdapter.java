package com.sola.universalmarket.shops;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads chest-shop data from QuickShop-Hikari.
 *
 * WHY THIS ONE CLASS USES REFLECTION
 *
 *   Everything else in the plugin is typed against real APIs, because that is
 *   what prevents NoSuchMethodError. QuickShop is the exception, and the reason
 *   is specific rather than lazy: at tag 6.3.0.1 the Shop type is declared
 *
 *       public interface Shop<U, L> extends Locatable<L>, ShopInventory,
 *               ShopMeta<U>, ShopTrading, ShopDisplay, ShopPermission, ...
 *
 *   a generic split-interface shape that differs from 6.2.x and moves again
 *   toward 6.4.x, with getPrice() already marked @Deprecated(forRemoval).
 *   Compiling against one of those shapes guarantees a hard crash on the others.
 *
 *   Binding by name at runtime means a QuickShop update can, at worst, disable
 *   Player Shops with a clear log line - instead of taking the whole plugin
 *   down mid-session. Every method it needs is resolved once at startup and
 *   reported, so you can see exactly what bound and what did not.
 *
 * READ ONLY, ALWAYS
 *
 *   This adapter never writes. It does not move stock, take money, or perform
 *   remote trades. Spec section 31: without a supported API for a remote
 *   purchase, the correct behaviour is to show the shop and let the player walk
 *   there, not to fake a transaction against someone else's chest.
 */
public final class QuickShopAdapter {

    private final UniversalMarketPlugin plugin;

    private Object shopManager;
    private Method getAllShops;
    private Method shopGetItem;
    private Method shopGetPrice;
    private Method shopGetRemainingStock;
    private Method shopGetOwner;
    private Method shopBukkitLocation;
    private Method shopIsSelling;
    private Method shopGetShopId;
    private Method ownerGetUsername;

    private boolean bound = false;

    public QuickShopAdapter(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Binding
    // ==================================================================

    public boolean bind() {
        if (Bukkit.getPluginManager().getPlugin("QuickShop-Hikari") == null) {
            plugin.getLogger().info("QuickShop-Hikari not detected - Player Shops disabled.");
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                plugin.getLogger().warning("QuickShopAPI.getInstance() returned null.");
                return false;
            }
            this.shopManager = apiClass.getMethod("getShopManager").invoke(api);
            if (shopManager == null) {
                plugin.getLogger().warning("QuickShop returned a null ShopManager.");
                return false;
            }

            this.getAllShops = findMethod(shopManager.getClass(), "getAllShops");
            if (getAllShops == null) {
                plugin.getLogger().warning("QuickShop ShopManager has no getAllShops() - "
                        + "Player Shops disabled.");
                return false;
            }
            getAllShops.setAccessible(true);

            // Shop accessors are resolved lazily from the first real shop, because
            // the concrete implementation class is what actually carries them.
            this.bound = true;
            plugin.getLogger().info("QuickShop-Hikari hooked (read-only shop index).");
            return true;

        } catch (Throwable t) {
            plugin.getLogger().warning("Could not bind the QuickShop API: " + t
                    + " - Player Shops disabled. Everything else keeps working.");
            return false;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Method m = current.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { }
            current = current.getSuperclass();
        }
        // Also walk interfaces, since Shop is an interface hierarchy.
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method m = iface.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { }
        }
        try {
            Method m = type.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) { }
        return null;
    }

    private void resolveShopAccessors(Object shop) {
        if (shopGetItem != null) return;
        Class<?> type = shop.getClass();

        shopGetItem            = findMethod(type, "getItem");
        shopGetPrice           = findMethod(type, "getPrice");
        shopGetRemainingStock  = findMethod(type, "getRemainingStock");
        shopGetOwner           = findMethod(type, "getOwner");
        shopIsSelling          = findMethod(type, "isSelling");
        shopGetShopId          = findMethod(type, "getShopId");

        // bukkitLocation() is the non-generic accessor; getLocation() returns the
        // generic L on 6.3.x, so prefer the concrete one and fall back.
        shopBukkitLocation = findMethod(type, "bukkitLocation");
        if (shopBukkitLocation == null) shopBukkitLocation = findMethod(type, "getLocation");

        plugin.getLogger().info("QuickShop accessors bound: "
                + "item=" + (shopGetItem != null)
                + " price=" + (shopGetPrice != null)
                + " stock=" + (shopGetRemainingStock != null)
                + " owner=" + (shopGetOwner != null)
                + " location=" + (shopBukkitLocation != null)
                + " selling=" + (shopIsSelling != null));
    }

    // ==================================================================
    // Indexing
    // ==================================================================

    /**
     * Snapshot every shop QuickShop knows about.
     *
     * Called on a timer from the main thread, never per click. Anything that
     * cannot be read cleanly is skipped rather than guessed at, because a
     * listing showing a wrong price or stale stock is worse than no listing.
     */
    public List<ShopListing> buildIndex() {
        List<ShopListing> out = new ArrayList<>();
        if (!bound || getAllShops == null) return out;

        Object raw;
        try {
            raw = getAllShops.invoke(shopManager);
        } catch (Throwable t) {
            plugin.getLogger().warning("QuickShop getAllShops failed: " + t);
            return out;
        }
        if (!(raw instanceof Collection<?> shops)) return out;

        int skipped = 0;
        for (Object shop : shops) {
            if (shop == null) continue;
            try {
                resolveShopAccessors(shop);
                ShopListing listing = toListing(shop);
                if (listing != null) out.add(listing); else skipped++;
            } catch (Throwable t) {
                skipped++;
            }
        }
        if (skipped > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[quickshop] indexed " + out.size()
                    + " shops, skipped " + skipped + " unreadable");
        }
        return out;
    }

    private ShopListing toListing(Object shop) throws Exception {
        if (shopGetItem == null || shopGetPrice == null) return null;

        Object itemRaw = shopGetItem.invoke(shop);
        if (!(itemRaw instanceof ItemStack stack)) return null;

        // Only plain vanilla items map onto a catalog entry. A shop selling a
        // named or enchanted item simply is not comparable to a market price,
        // so we leave it out rather than mislabel it.
        MarketItem item = plugin.catalog().byMaterial(stack.getType());
        if (item == null) return null;
        if (!plugin.sell().isPlainVanilla(stack)) return null;

        Object priceRaw = shopGetPrice.invoke(shop);
        if (!(priceRaw instanceof Number price)) return null;
        double priceValue = price.doubleValue();
        if (Double.isNaN(priceValue) || Double.isInfinite(priceValue) || priceValue < 0) return null;

        // QuickShop prices are per the shop's stack size, not per item.
        int perTrade = Math.max(1, stack.getAmount());
        BigDecimal unitPrice = BigDecimal.valueOf(priceValue)
                .divide(BigDecimal.valueOf(perTrade), 0, java.math.RoundingMode.HALF_UP);

        int stock = 0;
        if (shopGetRemainingStock != null) {
            Object stockRaw = shopGetRemainingStock.invoke(shop);
            if (stockRaw instanceof Number n) stock = n.intValue();
        }

        boolean selling = true;
        if (shopIsSelling != null) {
            Object sellingRaw = shopIsSelling.invoke(shop);
            if (sellingRaw instanceof Boolean b) selling = b;
        }

        long id = 0L;
        if (shopGetShopId != null) {
            Object idRaw = shopGetShopId.invoke(shop);
            if (idRaw instanceof Number n) id = n.longValue();
        }

        Location location = null;
        if (shopBukkitLocation != null) {
            Object locRaw = shopBukkitLocation.invoke(shop);
            if (locRaw instanceof Location l) location = l;
        }

        String owner = resolveOwnerName(shop);

        return new ShopListing(id, owner, item.id(), item.displayName(),
                unitPrice, stock, location, selling);
    }

    /**
     * Owner is a QUser on 6.3.x and a bare UUID on older builds, so handle both
     * rather than assuming either.
     */
    private String resolveOwnerName(Object shop) {
        if (shopGetOwner == null) return "Unknown";
        try {
            Object owner = shopGetOwner.invoke(shop);
            if (owner == null) return "Unknown";

            if (owner instanceof java.util.UUID uuid) {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                return name == null ? "Unknown" : name;
            }
            if (ownerGetUsername == null) {
                ownerGetUsername = findMethod(owner.getClass(), "getUsername");
                if (ownerGetUsername == null) {
                    ownerGetUsername = findMethod(owner.getClass(), "getDisplay");
                }
            }
            if (ownerGetUsername != null) {
                Object name = ownerGetUsername.invoke(owner);
                if (name instanceof String s && !s.isBlank()) return s;
            }
            return "Unknown";
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    public boolean isBound() {
        return bound;
    }
}
