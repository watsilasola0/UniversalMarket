package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.ItemCategory;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.market.PurchaseService;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
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

    /**
     * What each player was last looking at: category, page, chosen quantity and
     * whether the affordable-only filter is on. Keeping this per player is what
     * makes the Back button return you to page 7 instead of page 1.
     */
    private final Map<java.util.UUID, BrowseState> states = new java.util.HashMap<>();

    private static final class BrowseState {
        ItemCategory category;
        int page;
        int quantity = 1;
        boolean affordableOnly;
    }

    private BrowseState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new BrowseState());
    }

    public void forget(java.util.UUID uuid) {
        states.remove(uuid);
    }

    /** Quantities offered by the selector, cycled in order. */
    private static final int[] QUANTITIES = {1, 8, 16, 32, 64};

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

        // Ten tabs, five per row, centred on columns 2-6 of a 9-wide grid.
        // The old 10-14 / 19-23 layout sat one column left of centre.
        int[] slots = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        ItemCategory[] categories = ItemCategory.values();

        for (int i = 0; i < categories.length && i < slots.length; i++) {
            ItemCategory category = categories[i];
            int count = itemsIn(category).size();

            gui.set(slots[i], Gui.icon(category.icon(),
                    category.colour() + "<b>" + category.displayName(),
                    "<gray>" + NumberFormatter.count(count) + " items",
                    "",
                    "<yellow>Click to browse"),
                    p -> {
                        plugin.sounds().click(p);
                        // Return to where they were in this category last time.
                        BrowseState st = state(p);
                        int page = (st.category == category) ? st.page : 0;
                        openCategory(p, category, page);
                    });
        }

        gui.set(31, Gui.icon(Material.COMPASS,
                "<gold>Looking for something specific?",
                "<gray>Use <white>/um price <item></white> to check",
                "<gray>any item's price and which player",
                "<gray>shops sell it cheaper."));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>← Back to market"), menus::openHome);
        gui.set(40, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Category page
    // ==================================================================

    public void openCategory(Player player, ItemCategory category, int page) {
        BrowseState st = state(player);
        st.category = category;

        BigDecimal playerBalance = plugin.economy().balance(player);
        List<MarketItem> items = itemsIn(category);

        // 17 - hide anything they cannot buy right now.
        if (st.affordableOnly) {
            List<MarketItem> affordable = new ArrayList<>();
            for (MarketItem item : items) {
                if (plugin.pricing().currentBuyPrice(item).compareTo(playerBalance) <= 0) {
                    affordable.add(item);
                }
            }
            items = affordable;
        }
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        int current = Math.max(0, Math.min(page, pages - 1));
        st.page = current;

        BigDecimal balance = playerBalance;

        Gui gui = new Gui("<dark_gray>✦ " + category.coloured()
                + " <dark_gray>(" + (current + 1) + "/" + pages + ")", 6);

        currentViewer = player.getUniqueId();

        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, items.size());

        for (int i = from; i < to; i++) {
            MarketItem item = items.get(i);
            gui.set(i - from, renderItem(item, balance, st), (p, click) -> buy(p, item, click, category, current));
        }

        // ---- navigation row ----
        //
        // Red and lime concrete always occupy the same two slots, whether or not
        // there is a page to go to. A control that moves or vanishes is worse
        // than one that is visibly greyed out, because you stop trusting where
        // it will be.
        if (current > 0) {
            gui.set(45, Gui.icon(Material.RED_CONCRETE,
                    "<red><b>← Previous page",
                    "<gray>Go to page <white>" + current + "</white> of " + pages),
                    p -> { plugin.sounds().page(p); openCategory(p, category, current - 1); });
        } else {
            gui.set(45, Gui.icon(Material.GRAY_CONCRETE,
                    "<dark_gray>← Previous page",
                    "<dark_gray>You are on the first page."));
        }

        gui.set(47, Gui.playerHead(player,
                "<green><b>" + NumberFormatter.money(balance),
                "<gray>Your balance"));

        gui.set(49, Gui.glowingIcon(Material.CRAFTING_TABLE,
                "<gold><b>All categories",
                "<gray>Back to the category list.",
                "",
                "<gray>Viewing <white>" + category.displayName() + "</white>",
                "<gray>Page <white>" + (current + 1) + "</white> of <white>" + pages),
                this::openCategories);

        // 5 - quantity selector
        gui.set(48, Gui.icon(Material.PAPER,
                "<yellow><b>Buy quantity: <white>" + st.quantity,
                "<gray>How many a left click buys.",
                "",
                "<gray>Click to cycle 1 → 8 → 16 → 32 → 64",
                "",
                "<gray>Right click an item for 16,",
                "<gray>shift click for a full stack."),
                p -> {
                    BrowseState cur = state(p);
                    int index = 0;
                    for (int q = 0; q < QUANTITIES.length; q++) {
                        if (QUANTITIES[q] == cur.quantity) { index = q; break; }
                    }
                    cur.quantity = QUANTITIES[(index + 1) % QUANTITIES.length];
                    plugin.sounds().click(p);
                    openCategory(p, category, cur.page);
                });

        // 17 - affordable-only filter
        gui.set(50, Gui.icon(st.affordableOnly ? Material.LIME_DYE : Material.GRAY_DYE,
                (st.affordableOnly ? "<green><b>" : "<gray>") + "Only what I can afford",
                "<gray>Hide items you cannot buy",
                "<gray>at your current balance.",
                "",
                "<gray>Currently: " + (st.affordableOnly ? "<green>on" : "<red>off")),
                p -> {
                    BrowseState cur = state(p);
                    cur.affordableOnly = !cur.affordableOnly;
                    plugin.sounds().click(p);
                    openCategory(p, category, 0);
                });

        gui.set(52, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());

        if (current < pages - 1) {
            gui.set(53, Gui.icon(Material.LIME_CONCRETE,
                    "<green><b>Next page →",
                    "<gray>Go to page <white>" + (current + 2) + "</white> of " + pages),
                    p -> { plugin.sounds().page(p); openCategory(p, category, current + 1); });
        } else {
            gui.set(53, Gui.icon(Material.GRAY_CONCRETE,
                    "<dark_gray>Next page →",
                    "<dark_gray>You are on the last page."));
        }

        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Rendering
    // ==================================================================

    /** Set immediately before a page render so lore can do per-player lookups. */
    private java.util.UUID currentViewer;

    private org.bukkit.inventory.ItemStack renderItem(MarketItem item, BigDecimal balance, BrowseState st) {
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

        // 7 - what you would get back if you sold it again.
        BigDecimal buyback = plugin.pricing().currentBuyback(item);
        lore.add("<gray>Sell back for <green>" + NumberFormatter.money(buyback)
                + "</green> <gray>each");
        if (plugin.pricing().isHighDemand(item.id())) {
            lore.add("<yellow>★ High demand: +"
                    + NumberFormatter.percent(plugin.pricing().demandBonus(item.id())));
        }
        lore.add("");

        // 14 - rare allowance and cooldown, shown on the item itself rather
        // than only in the Rare Goods tab.
        if (item.rare()) {
            lore.add("<dark_purple>⟡ Rare <gray>- limit <white>"
                    + item.purchaseLimit() + "</white> per player");
            var allowance = plugin.rareGoods()
                    .checkAllowance(currentViewer, item, 1);
            if (allowance.allowed()) {
                lore.add("<green>Available now");
            } else {
                lore.add("<red>Limit reached <gray>- resets in <yellow>"
                        + NumberFormatter.duration(allowance.resetInMillis()));
            }
            lore.add("");
        }

        // Spec: the balance must be readable without leaving the item you are
        // looking at, and must be correct the moment the menu repaints.
        lore.add("<dark_gray>─────────────────");
        lore.add("<gray>YOUR BALANCE: <green><b>" + NumberFormatter.money(balance));
        lore.add("");

        boolean affordable = balance.compareTo(unit) >= 0;
        if (affordable) {
            int chosen = st == null ? 1 : st.quantity;
            BigDecimal chosenTotal = unit.multiply(BigDecimal.valueOf(chosen));
            lore.add("<yellow>Left click <gray>- buy <white>" + chosen + "</white> ("
                    + NumberFormatter.money(chosenTotal) + ")");
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
        BrowseState st = state(player);

        int quantity = switch (click) {
            case RIGHT, SHIFT_RIGHT -> 16;
            case SHIFT_LEFT -> item.material() == null ? 64 : item.material().getMaxStackSize();
            default -> st.quantity;
        };

        BigDecimal unit = plugin.pricing().currentBuyPrice(item);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(quantity));

        // 3 - confirm anything expensive, so a misclick cannot cost an Elytra.
        long threshold = plugin.getConfig().getLong("buy.confirm-above", 10_000_000L);
        boolean needsConfirm = threshold > 0 && total.compareTo(BigDecimal.valueOf(threshold)) >= 0;
        if (needsConfirm || (item.rare()
                && plugin.getConfig().getBoolean("buy.confirm-rare", true))) {
            openConfirm(player, item, quantity, total, category, page);
            return;
        }
        execute(player, item, quantity, category, page);
    }

    /** Confirmation screen for large or rare purchases. */
    private void openConfirm(Player player, MarketItem item, int quantity,
                             BigDecimal total, ItemCategory category, int page) {
        BigDecimal balance = plugin.economy().balance(player);
        BigDecimal after = balance.subtract(total);

        Gui gui = new Gui("<dark_gray>✦ <gold>CONFIRM PURCHASE <dark_gray>✦", 3);

        gui.set(13, Gui.icon(item.material(),
                "<white>" + quantity + "x " + item.displayName(),
                "<gray>Total: <gold><b>" + NumberFormatter.money(total),
                "<gray>Exact: <white>" + NumberFormatter.exactMoney(total),
                "",
                "<gray>Balance now: <green>" + NumberFormatter.money(balance),
                "<gray>Balance after: "
                        + (after.signum() < 0 ? "<red>" : "<green>")
                        + NumberFormatter.money(after),
                "",
                item.rare() ? "<dark_purple>⟡ This is a rare item." : "<gray>"));

        gui.set(11, Gui.icon(Material.LIME_CONCRETE,
                "<green><b>✓ Confirm",
                "<gray>Buy " + quantity + "x " + item.displayName()),
                p -> {
                    plugin.sounds().click(p);
                    execute(p, item, quantity, category, page);
                });

        gui.set(15, Gui.icon(Material.RED_CONCRETE,
                "<red><b>✕ Cancel",
                "<gray>Go back without buying."),
                p -> {
                    plugin.sounds().click(p);
                    openCategory(p, category, page);
                });

        gui.fillEmpty().open(player);
        plugin.sounds().confirm(player);
    }

    /** Runs the purchase and reports the result. */
    private void execute(Player player, MarketItem item, int quantity,
                         ItemCategory category, int page) {
        PurchaseService.Result result = plugin.purchases().buy(player, item, quantity);

        if (result.success()) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("buy.success")
                    .replace("%qty%", String.valueOf(result.quantity()))
                    .replace("%item%", item.displayName())
                    .replace("%price%", NumberFormatter.money(result.amount()))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));

            // A big purchase gets a more satisfying sound than a stack of dirt.
            if (item.rare() || result.amount().compareTo(BigDecimal.valueOf(1_000_000L)) >= 0) {
                plugin.sounds().bigBuy(player);
            } else {
                plugin.sounds().buy(player);
            }
            plugin.announcements().checkPromotion(player);

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
            if ("buy.insufficient".equals(result.messageKey())) plugin.sounds().broke(player);
            else plugin.sounds().error(player);
        }

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

                // Variants ARE included now. Vanilla's own Combat tab is mostly
                // tipped arrows, so excluding them made the catalogue look wrong
                // next to the real creative screen.
                if (ItemCategory.classify(material) == c) out.add(item);
            }
            // Group by kind, then tier, then piece - see ItemCategory.sortKey.
            // Plain items come before their component variants so that, for
            // example, ARROW leads the tipped arrows rather than being lost
            // among them.
            out.sort(Comparator
                    .comparingLong((MarketItem i) -> {
                        Material m = i.material();
                        return m == null ? Long.MAX_VALUE : ItemCategory.sortKey(m);
                    })
                    .thenComparing(i -> i.key().hasVariant() ? 1 : 0)
                    .thenComparing(MarketItem::displayName));
            return out;
        });
    }
}
