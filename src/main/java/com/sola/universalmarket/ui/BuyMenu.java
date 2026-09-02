package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.ItemCategory;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.market.PurchaseService;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The buy catalogue: ten creative-style tabs, paginated, with real prices.
 *
 * This replaces the fake-creative browser for buying. The reason is simple and
 * came out of testing: Minecraft hides the entire HUD behind any open inventory
 * screen, so while the native creative screen is up the server cannot draw
 * prices, balance, or purchase confirmations anywhere on screen. A chest menu is
 * a screen we control, so every item can carry its price, the player's balance
 * stays visible, and cheaper player shops can be flagged inline.
 *
 * Item lists are built once per category and cached, because sorting ~1,200
 * entries on every page turn would be wasteful. The cache holds catalogue
 * membership only - prices are read live on render, so deals and dynamic pricing
 * always show current values.
 */
public final class BuyMenu {

    private static final int PAGE_SIZE = 45;

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    /** category -> item list, sorted in registry order. Built lazily. */
    private final Map<ItemCategory, List<MarketItem>> cache = new EnumMap<>(ItemCategory.class);

    public BuyMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    public void invalidate() {
        cache.clear();
    }

    // ==================================================================
    // Category picker
    // ==================================================================

    public void openCategories(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <gold>BUY ITEMS <dark_gray>✦", 5);

        gui.set(4, Gui.playerHead(player,
                "<green><b>" + NumberFormatter.money(plugin.economy().balance(player)),
                "<gray>Your balance"));

        // Ten tabs across two tidy rows.
        int[] slots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
        ItemCategory[] categories = ItemCategory.values();

        for (int i = 0; i < categories.length && i < slots.length; i++) {
            ItemCategory category = categories[i];
            int count = itemsIn(category).size();

            gui.set(slots[i], Gui.icon(category.icon(),
                    category.colour() + "<b>" + category.displayName(),
                    "<gray>" + NumberFormatter.count(count) + " items",
                    "",
                    "<yellow>Click to browse"),
                    p -> openCategory(p, category, 0));
        }

        gui.set(31, Gui.icon(Material.COMPASS,
                "<gold>Looking for something specific?",
                "<gray>Use <white>/um price <item></white> to check",
                "<gray>any item's price and which player",
                "<gray>shops sell it cheaper."));

        gui.set(36, Gui.icon(Material.ARROW, "<gray>← Back"), menus::openHome);
        gui.set(40, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Category page
    // ==================================================================

    public void openCategory(Player player, ItemCategory category, int page) {
        List<MarketItem> items = itemsIn(category);
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        int current = Math.max(0, Math.min(page, pages - 1));

        BigDecimal balance = plugin.economy().balance(player);

        Gui gui = new Gui("<dark_gray>✦ " + category.coloured()
                + " <dark_gray>(" + (current + 1) + "/" + pages + ")", 6);

        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, items.size());

        for (int i = from; i < to; i++) {
            MarketItem item = items.get(i);
            gui.set(i - from, renderItem(item, balance), (p, click) -> buy(p, item, click, category, current));
        }

        // ---- navigation row ----
        if (current > 0) {
            gui.set(45, Gui.icon(Material.ARROW,
                    "<yellow>← Previous page",
                    "<gray>Page " + current + " of " + pages),
                    p -> openCategory(p, category, current - 1));
        }

        gui.set(48, Gui.icon(Material.CHEST,
                "<gray>← All categories"), this::openCategories);

        gui.set(49, Gui.playerHead(player,
                "<green><b>" + NumberFormatter.money(balance),
                "<gray>Your balance",
                "",
                "<gray>Page <white>" + (current + 1) + "</white> of <white>" + pages));

        gui.set(50, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());

        if (current < pages - 1) {
            gui.set(53, Gui.icon(Material.ARROW,
                    "<yellow>Next page →",
                    "<gray>Page " + (current + 2) + " of " + pages),
                    p -> openCategory(p, category, current + 1));
        }

        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Rendering
    // ==================================================================

    private org.bukkit.inventory.ItemStack renderItem(MarketItem item, BigDecimal balance) {
        BigDecimal unit = plugin.pricing().currentBuyPrice(item);
        BigDecimal stackPrice = unit.multiply(BigDecimal.valueOf(
                item.material() == null ? 64 : item.material().getMaxStackSize()));

        List<String> lore = new ArrayList<>();
        lore.add("<gold>" + NumberFormatter.money(unit) + " <gray>each");
        lore.add("<gray>Stack: <gold>" + NumberFormatter.money(stackPrice));

        if (plugin.pricing().isOnDeal(item.id())) {
            lore.add("<yellow>★ ON SALE  <gray><st>"
                    + NumberFormatter.money(item.umBuyPrice()) + "</st>");
        }
        lore.add("");

        // Undercut nudge - the whole point of the server market being expensive.
        plugin.playerShops().cheapestFor(item).ifPresent(listing -> {
            if (listing.pricePerItem().compareTo(unit) < 0) {
                lore.add("<aqua>Player shop: " + NumberFormatter.money(listing.pricePerItem())
                        + " <gray>each");
                lore.add("<gray>from <white>" + listing.ownerName() + "</white> - cheaper!");
                lore.add("");
            }
        });

        lore.add("<gray>Server buys back at <green>"
                + NumberFormatter.money(plugin.pricing().currentBuyback(item)));
        lore.add("");

        if (item.rare()) {
            lore.add("<dark_purple>Rare - limit " + item.purchaseLimit() + " per player");
            lore.add("");
        }

        // Spec: the balance must be readable without leaving the item you are
        // looking at, and must be correct the moment the menu repaints.
        lore.add("<dark_gray>─────────────────");
        lore.add("<gray>YOUR BALANCE: <green><b>" + NumberFormatter.money(balance));
        lore.add("");

        boolean affordable = balance.compareTo(unit) >= 0;
        if (affordable) {
            lore.add("<yellow>Left click <gray>- buy 1");
            lore.add("<yellow>Right click <gray>- buy 16");
            lore.add("<yellow>Shift click <gray>- buy a stack");
        } else {
            lore.add("<red>You cannot afford this.");
            lore.add("<gray>Short by <red>"
                    + NumberFormatter.money(unit.subtract(balance)));
        }

        return Gui.icon(item.material(), "<white>" + item.displayName(), lore);
    }

    // ==================================================================
    // Buying
    // ==================================================================

    private void buy(Player player, MarketItem item, ClickType click,
                     ItemCategory category, int page) {
        int quantity = switch (click) {
            case RIGHT, SHIFT_RIGHT -> 16;
            case SHIFT_LEFT -> item.material() == null ? 64 : item.material().getMaxStackSize();
            default -> 1;
        };
        // Shift-right is ambiguous across clients, so treat it as 16 like right.
        if (click == ClickType.SHIFT_RIGHT) quantity = 16;

        PurchaseService.Result result = plugin.purchases().buy(player, item, quantity);

        if (result.success()) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("buy.success")
                    .replace("%qty%", String.valueOf(result.quantity()))
                    .replace("%item%", item.displayName())
                    .replace("%price%", NumberFormatter.money(result.amount()))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);
        } else {
            String message = switch (result.messageKey()) {
                case "buy.insufficient" -> plugin.messages().get("buy.insufficient")
                        .replace("%item%", item.displayName())
                        .replace("%price%", NumberFormatter.money(result.amount()))
                        .replace("%balance%",
                                NumberFormatter.money(plugin.economy().balance(player)));
                case "buy.rare-limit" -> plugin.messages().get("buy.rare-limit")
                        .replace("%item%", item.displayName())
                        .replace("%time%",
                                NumberFormatter.duration(result.amount().longValue()));
                default -> plugin.messages().get(result.messageKey())
                        .replace("%item%", item.displayName());
            };
            player.sendMessage(Gui.MM.deserialize(message));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
        }

        // Repaint so the balance and affordability hints stay truthful.
        openCategory(player, category, page);
    }

    // ==================================================================
    // Category membership
    // ==================================================================

    private List<MarketItem> itemsIn(ItemCategory category) {
        return cache.computeIfAbsent(category, c -> {
            List<MarketItem> out = new ArrayList<>();
            for (MarketItem item : plugin.catalog().all()) {
                if (item.blacklisted()) continue;
                if (item.umBuyPrice().signum() <= 0) continue;

                Material material = item.material();
                if (material == null || !material.isItem()) continue;

                // Variants (potions, enchanted books, goat horns) are excluded from
                // the visual catalogue: dozens of identical-looking icons would be
                // unreadable. They stay buyable through /um price and search.
                if (item.key().hasVariant()) continue;

                if (ItemCategory.classify(material) == c) out.add(item);
            }
            // Registry order approximates vanilla creative ordering.
            out.sort(Comparator.comparingInt(i -> {
                Material m = i.material();
                return m == null ? Integer.MAX_VALUE : m.ordinal();
            }));
            return out;
        });
    }
}
