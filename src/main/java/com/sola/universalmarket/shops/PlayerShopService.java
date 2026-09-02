package com.sola.universalmarket.shops;

import com.sola.universalmarket.catalog.MarketItem;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Cached index of player QuickShop listings.
 *
 * Step 4 wires this to the real QuickShopAdapter. Until then it reports "no
 * shops", which is a truthful empty state rather than fabricated data - the
 * Player Shops menu simply shows nothing and the "cheaper elsewhere" nudge stays
 * silent. Nothing downstream has to special-case it.
 */
public final class PlayerShopService {

    private volatile boolean available = false;
    private volatile List<ShopListing> listings = Collections.emptyList();

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean value) {
        this.available = value;
    }

    public void replaceIndex(List<ShopListing> fresh) {
        this.listings = fresh == null ? Collections.emptyList() : List.copyOf(fresh);
    }

    public int indexSize() {
        return listings.size();
    }

    /** All selling shops for an item, cheapest first (spec section 29). */
    public List<ShopListing> sellingFor(MarketItem item) {
        if (item == null || listings.isEmpty()) return Collections.emptyList();
        return listings.stream()
                .filter(ShopListing::selling)
                .filter(l -> l.itemId().equals(item.id()))
                .filter(ShopListing::inStock)
                .sorted((a, b) -> a.pricePerItem().compareTo(b.pricePerItem()))
                .toList();
    }

    /** Cheapest in-stock player listing, used for the undercut nudge. */
    public Optional<ShopListing> cheapestFor(MarketItem item) {
        return sellingFor(item).stream().findFirst();
    }
}
