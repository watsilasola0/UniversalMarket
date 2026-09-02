package com.sola.universalmarket.shops;

import org.bukkit.Location;

import java.math.BigDecimal;

/**
 * An immutable snapshot of one QuickShop chest shop.
 *
 * Deliberately a snapshot rather than a live handle. The index is rebuilt on a
 * timer, so a listing may be stale by up to the refresh interval. Anything that
 * would act on a shop must re-check it against QuickShop first - see spec
 * section 32, "if the shop unloads or stock changes, data must not lie".
 */
public record ShopListing(long shopId,
                          String ownerName,
                          String itemId,
                          String displayName,
                          BigDecimal pricePerItem,
                          int remainingStock,
                          Location location,
                          boolean selling) {

    /** Price for a full stack of 64, for comparison against Universal Market stack prices. */
    public BigDecimal stackPrice() {
        return pricePerItem.multiply(BigDecimal.valueOf(64));
    }

    public boolean inStock() {
        return remainingStock > 0;
    }

    public String coordinates() {
        if (location == null) return "unknown";
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + (location.getWorld() != null ? " (" + location.getWorld().getName() + ")" : "");
    }
}
