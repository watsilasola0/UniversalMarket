package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.market.ListingService;
import com.sola.universalmarket.market.SellFlowService;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every Universal Market screen.
 *
 * Navigation language is consistent throughout: RED concrete goes back or
 * cancels, LIME concrete goes forward or confirms, and a clock shows the live
 * countdown for whichever cycle that screen belongs to. No arrows, and no
 * barrier "close" buttons - Escape closes a menu, which is what players do
 * anyway.
 */
public final class MarketMenus {

    private final UniversalMarketPlugin plugin;
    private final BuyMenu buyMenu;
    private final QuestMenu questMenu;
    private final CrateMenu crateMenu;
    private final GambleMenu gambleMenu;

    public MarketMenus(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.buyMenu = new BuyMenu(plugin, this);
        this.questMenu = new QuestMenu(plugin, this);
        this.crateMenu = new CrateMenu(plugin, this);
        this.gambleMenu = new GambleMenu(plugin, this);
    }

    public BuyMenu buyMenu() {
        return buyMenu;
    }

    public QuestMenu questMenu() {
        return questMenu;
    }

    public CrateMenu crateMenu() { return crateMenu; }
    public GambleMenu gambleMenu() { return gambleMenu; }

    // ==================================================================
    // Home
    // ==================================================================

    public void openHome(Player player) {
        BigDecimal balance = plugin.economy().balance(player);
        Gui gui = new Gui("<dark_gray>\u2726 <gold>UNIVERSAL MARKET <dark_gray>\u2726", 6);

        gui.set(4, Gui.playerHead(player,
                "<green><b>" + NumberFormatter.money(balance),
                "<gray>Wealth rank: " + wealthTier(balance),
                "<gray>" + player.getName()));

        gui.set(19, Gui.icon(Material.CHEST, "<gold><b>BUY ITEMS",
                "<gray>Ten categories of everything",
                "<gray>the market sells."),
                p -> { plugin.sounds().click(p); buyMenu.openCategories(p); });

        gui.set(21, Gui.icon(Material.HOPPER, "<gold><b>SELL TO SERVER",
                "<gray>Guaranteed buyback, lowest price.",
                "",
                "<gray>Use <white>/sell</white> to list goods",
                "<gray>to other players for more."),
                p -> { plugin.sounds().click(p); openSell(p); });

        gui.set(23, Gui.icon(Material.EMERALD, "<aqua><b>PLAYER SHOPS",
                "<gray>Buy from other players.",
                "",
                "<gray>Listings: <white>" + plugin.listings().size()),
                p -> { plugin.sounds().click(p); openPlayerShops(p); });

        gui.set(25, Gui.icon(Material.BARREL, "<aqua><b>MY LISTINGS",
                "<gray>What you currently have for sale.",
                "",
                "<gray>Use <white>/sell</white> to add more."),
                p -> { plugin.sounds().click(p); openSellerListings(p, p.getUniqueId()); });

        gui.set(28, Gui.icon(Material.SUNFLOWER, "<yellow><b>DAILY DEALS",
                "<gray>Discounted items, buyable here.",
                "",
                "<gray>Active: <white>" + plugin.pricing().dailyDeals().size(),
                "<gray>Resets in <yellow>"
                        + NumberFormatter.duration(plugin.pricing().dealsEndsInMillis())),
                p -> { plugin.sounds().click(p); openDeals(p); });

        gui.set(30, Gui.icon(Material.BLAZE_POWDER, "<yellow><b>HIGH DEMAND",
                "<gray>Sell these for a bonus,",
                "<gray>directly from the menu.",
                "",
                "<gray>Active: <white>" + plugin.pricing().highDemand().size(),
                "<gray>Resets in <yellow>"
                        + NumberFormatter.duration(plugin.pricing().demandEndsInMillis())),
                p -> { plugin.sounds().click(p); openHighDemand(p); });

        gui.set(32, Gui.icon(Material.END_CRYSTAL, "<dark_purple><b>RARE GOODS",
                "<gray>The fifteen rarest things",
                "<gray>money can buy.",
                "",
                "<gray>Limits reset in <yellow>"
                        + NumberFormatter.duration(plugin.pricing().rareResetInMillis())),
                p -> { plugin.sounds().click(p); openRareGoods(p); });

        gui.set(34, Gui.icon(Material.WRITTEN_BOOK, "<gold><b>QUESTS",
                "<gray>Repeatable jobs for money",
                "<gray>and useful items.",
                "",
                plugin.quests().hasActive(player.getUniqueId())
                        ? "<green>You have an active quest"
                        : "<yellow>Click for a new quest"),
                p -> { plugin.sounds().click(p); questMenu.open(p); });

        gui.set(39, Gui.icon(Material.DIAMOND, "<gold><b>LEADERBOARD",
                "<gray>Richest players on the server.",
                "",
                "<gray>Ranked: <white>" + plugin.leaderboard().size()),
                p -> { plugin.sounds().click(p); openLeaderboard(p); });

        gui.set(41, Gui.icon(Material.BOOK, "<white><b>MY ACCOUNT",
                "<gray>Balance, statistics and history."),
                p -> { plugin.sounds().click(p); openAccount(p); });

        gui.set(37, Gui.glowingIcon(Material.ENDER_CHEST, "<dark_purple><b>CRATES",
                "<gray>Seven tiers of loot crate.",
                "",
                "<gray>Place one down and watch it roll."),
                p -> { plugin.sounds().click(p); crateMenu.open(p); });

        gui.set(43, Gui.icon(Material.GOLD_NUGGET, "<yellow><b>GAMBLING",
                "<gray>Coinflip, Mines, High or Low.",
                "",
                "<dark_gray>The house has a small edge."),
                p -> { plugin.sounds().click(p); gambleMenu.open(p); });

        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Daily deals - buy in place
    // ==================================================================

    public void openDeals(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <yellow>DAILY DEALS <dark_gray>\u2726", 6);
        Map<String, Double> deals = plugin.pricing().dailyDeals();
        BigDecimal balance = plugin.economy().balance(player);

        int slot = 10;
        for (Map.Entry<String, Double> entry : deals.entrySet()) {
            MarketItem item = plugin.catalog().byId(entry.getKey());
            if (item == null) continue;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;

            BigDecimal now = plugin.pricing().currentBuyPrice(item);
            gui.set(slot++, Gui.icon(item.material(),
                    "<white>" + item.displayName(),
                    "<yellow>" + NumberFormatter.percent(entry.getValue()) + " OFF",
                    "",
                    "<gray>Was <st>" + NumberFormatter.money(item.umBuyPrice()) + "</st>",
                    "<gray>Now <gold><b>" + NumberFormatter.money(now) + "</b></gold> <gray>each",
                    "",
                    "<gray>YOUR BALANCE: <green>" + NumberFormatter.money(balance),
                    "",
                    "<yellow>Right click <gray>- buy 1",
                    "<yellow>Shift click <gray>- buy a stack"),
                    (p, click) -> {
                        int qty = switch (click) {
                            case SHIFT_LEFT, SHIFT_RIGHT -> item.material() == null
                                    ? 64 : item.material().getMaxStackSize();
                            default -> 1;
                        };
                        buyDirect(p, item, qty);
                        openDeals(p);
                    });
        }
        if (deals.isEmpty()) {
            gui.set(22, Gui.icon(Material.WHITE_STAINED_GLASS_PANE,
                    "<gray>No deals right now",
                    "<gray>Check back after the next reset."));
        }

        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.live(49, () -> Gui.icon(Material.CLOCK,
                "<yellow><b>" + NumberFormatter.duration(plugin.pricing().dealsEndsInMillis()),
                "<gray>Until deals reroll.",
                "",
                "<gray>New discounts every <white>"
                        + plugin.getConfig().getLong("market-cycle.deals-minutes", 35)
                        + "</white> minutes."));
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // High demand - 7x4, sell in place
    // ==================================================================

    public void openHighDemand(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <yellow>HIGH DEMAND <dark_gray>\u2726", 6);
        Map<String, Double> demand = plugin.pricing().highDemand();

        // Rows 1-4 rather than 0-3, so any unused slots collect at the TOP
        // and the populated rows sit against the navigation row below.
        List<Integer> grid = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) grid.add(row * 9 + col);
        }

        // Fill from the bottom up: leftover glass ends up on the first row.
        int emptySlots = Math.max(0, grid.size() - demand.size());
        int index = emptySlots;
        for (int i = 0; i < emptySlots; i++) {
            gui.set(grid.get(i), Gui.icon(Material.WHITE_STAINED_GLASS_PANE,
                    "<gray>Empty slot",
                    "<gray>Nothing in demand here",
                    "<gray>until the next reset."));
        }
        for (Map.Entry<String, Double> entry : demand.entrySet()) {
            if (index >= grid.size()) break;
            MarketItem item = plugin.catalog().byId(entry.getKey());
            if (item == null) continue;

            int held = plugin.sell().countSellable(player, item);
            var quote = plugin.sell().quote(player.getUniqueId(), item, Math.max(1, held));

            List<String> lore = new ArrayList<>();
            lore.add("<green>+" + NumberFormatter.percent(entry.getValue()) + " buyback");
            lore.add("");
            lore.add("<gray>Pays <green><b>"
                    + NumberFormatter.money(plugin.pricing().currentBuyback(item))
                    + "</b></green> <gray>each");
            lore.add("<gray>You have: <white>" + NumberFormatter.count(held));
            lore.add("<gray>Rate: " + quote.describeTier());
            lore.add("");
            if (held > 0) {
                lore.add("<yellow>Right click <gray>- sell 1");
                lore.add("<yellow>Shift click <gray>- sell all");
            } else {
                lore.add("<red>You have none to sell.");
            }

            gui.set(grid.get(index++), Gui.icon(item.material(),
                    "<white>" + item.displayName(), lore),
                    (p, click) -> {
                        int available = plugin.sell().countSellable(p, item);
                        int qty = switch (click) {
                            case SHIFT_LEFT, SHIFT_RIGHT -> available;
                            default -> 1;
                        };
                        sellDirect(p, item, qty);
                        openHighDemand(p);
                    });
        }

        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.live(49, () -> Gui.icon(Material.CLOCK,
                "<yellow><b>" + NumberFormatter.duration(plugin.pricing().demandEndsInMillis()),
                "<gray>Until demand changes.",
                "",
                "<gray>Every 3 Minecraft days.",
                "<gray>Between <white>"
                        + plugin.getConfig().getInt("market-cycle.high-demand.min-count", 10)
                        + "</white> and <white>"
                        + plugin.getConfig().getInt("market-cycle.high-demand.max-count", 28)
                        + "</white> items each cycle."));
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Rare goods - fixed 5x3
    // ==================================================================

    public void openRareGoods(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <dark_purple>RARE GOODS <dark_gray>\u2726", 6);
        BigDecimal balance = plugin.economy().balance(player);

        List<Integer> grid = new ArrayList<>();
        for (int row = 1; row <= 3; row++) {
            for (int col = 2; col <= 6; col++) grid.add(row * 9 + col);
        }

        List<MarketItem> rare = rarestItems(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            if (i >= rare.size()) {
                gui.set(grid.get(i), Gui.icon(Material.WHITE_STAINED_GLASS_PANE, "<gray>Empty"));
                continue;
            }
            MarketItem item = rare.get(i);
            BigDecimal price = plugin.pricing().currentBuyPrice(item);
            var allowance = plugin.rareGoods().checkAllowance(player.getUniqueId(), item, 1);

            List<String> lore = new ArrayList<>();
            lore.add("<gold><b>" + NumberFormatter.money(price));
            lore.add("");
            if (item.rare()) {
                lore.add("<dark_purple>\u27E1 Limit <white>" + item.purchaseLimit()
                        + "</white> per player");
                lore.add(allowance.allowed()
                        ? "<green>Available now"
                        : "<red>Limit reached this cycle");
                lore.add("");
            }
            lore.add("<gray>YOUR BALANCE: <green>" + NumberFormatter.money(balance));
            lore.add("");
            lore.add(balance.compareTo(price) >= 0
                    ? "<yellow>Click to buy one"
                    : "<red>Short by " + NumberFormatter.money(price.subtract(balance)));

            gui.set(grid.get(i), Gui.icon(item.material(),
                    "<dark_purple><b>" + item.displayName(), lore),
                    (p, click) -> { buyDirect(p, item, 1); openRareGoods(p); });
        }

        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.live(49, () -> Gui.icon(Material.CLOCK,
                "<yellow><b>" + NumberFormatter.duration(plugin.pricing().rareResetInMillis()),
                "<gray>Until purchase limits reset.",
                "",
                "<gray>Every 3 Minecraft days (60 min).",
                "<gray>Resets for everyone at once."));
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    /** The most expensive purchasable entries, rare-flagged ones first. */
    private List<MarketItem> rarestItems(int limit) {
        List<MarketItem> all = new ArrayList<>();
        for (MarketItem item : plugin.catalog().all()) {
            if (item.blacklisted()) continue;
            if (item.key().hasVariant()) continue;
            if (item.umBuyPrice().signum() <= 0) continue;
            all.add(item);
        }
        all.sort((a, b) -> {
            if (a.rare() != b.rare()) return a.rare() ? -1 : 1;
            return b.umBuyPrice().compareTo(a.umBuyPrice());
        });
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    // ==================================================================
    // Player shops
    // ==================================================================

    public void openPlayerShops(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <aqua>PLAYER SHOPS <dark_gray>\u2726", 6);
        List<UUID> sellers = plugin.listings().sellers();

        if (sellers.isEmpty()) {
            gui.set(22, Gui.icon(Material.WHITE_STAINED_GLASS_PANE,
                    "<gray>Nobody is selling anything",
                    "<gray>Be the first - use <white>/sell</white>."));
        } else {
            int slot = 10;
            for (UUID seller : sellers) {
                if (slot % 9 == 8) slot += 2;
                if (slot >= 44) break;

                List<ListingService.Listing> theirs = plugin.listings().bySeller(seller);
                BigDecimal cheapest = null;
                for (ListingService.Listing listing : theirs) {
                    if (cheapest == null || listing.pricePerItem.compareTo(cheapest) < 0) {
                        cheapest = listing.pricePerItem;
                    }
                }
                gui.set(slot++, Gui.playerHead(Bukkit.getOfflinePlayer(seller),
                        "<white><b>" + plugin.listings().sellerName(seller),
                        "<gray>Listings: <white>" + theirs.size(),
                        cheapest == null ? "<gray>" : "<gray>From <aqua>"
                                + NumberFormatter.money(cheapest),
                        "",
                        "<yellow>Click to browse"),
                        p -> { plugin.sounds().click(p); openSellerListings(p, seller); });
            }
        }

        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    public void openSellerListings(Player player, UUID seller) {
        String name = plugin.listings().sellerName(seller);
        boolean own = seller.equals(player.getUniqueId());

        Gui gui = new Gui("<dark_gray>\u2726 <aqua>" + name + " <dark_gray>\u2726", 6);
        List<ListingService.Listing> theirs = plugin.listings().bySeller(seller);
        BigDecimal balance = plugin.economy().balance(player);

        if (theirs.isEmpty()) {
            gui.set(22, Gui.icon(Material.WHITE_STAINED_GLASS_PANE,
                    own ? "<gray>You have nothing listed" : "<gray>Nothing for sale",
                    own ? "<gray>Use <white>/sell</white> to list goods." : "<gray>"));
        } else {
            int slot = 0;
            for (ListingService.Listing listing : theirs) {
                if (slot >= 45) break;
                MarketItem item = plugin.catalog().byId(listing.itemId);
                if (item == null) continue;

                BigDecimal um = plugin.pricing().currentBuyPrice(item);
                List<String> lore = new ArrayList<>();
                lore.add("<aqua><b>" + NumberFormatter.money(listing.pricePerItem)
                        + "</b></aqua> <gray>each");
                lore.add("<gray>Stock: <white>" + NumberFormatter.count(listing.remaining()));
                lore.add("");
                lore.add("<gray>Universal Market: <gold>" + NumberFormatter.money(um));
                if (listing.pricePerItem.compareTo(um) < 0) {
                    lore.add("<green>Cheaper than the market");
                }
                lore.add("");
                lore.add("<gray>YOUR BALANCE: <green>" + NumberFormatter.money(balance));
                lore.add("");
                if (own) {
                    lore.add("<gray>This is your own listing.");
                    lore.add("<dark_gray>Listings cannot be cancelled.");
                } else {
                    lore.add("<yellow>Right click <gray>- buy 1");
                    lore.add("<yellow>Shift click <gray>- buy all");
                }

                final long id = listing.id;
                gui.set(slot++, Gui.icon(item.material(),
                        "<white>" + item.displayName(), lore),
                        (p, click) -> {
                            if (own) { plugin.sounds().error(p); return; }
                            int qty = switch (click) {
                                case SHIFT_LEFT, SHIFT_RIGHT -> Integer.MAX_VALUE;
                                default -> 1;
                            };
                            buyListing(p, id, qty);
                            openSellerListings(p, seller);
                        });
            }
        }

        gui.set(45, backButton(), p -> {
            plugin.sounds().click(p);
            if (own) openHome(p); else openPlayerShops(p);
        });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Sell flow
    // ==================================================================

    public void openPriceChooser(Player player) {
        SellFlowService.PendingSale sale = plugin.sellFlow().pendingFor(player.getUniqueId());
        if (sale == null) return;

        BigDecimal[] suggested = plugin.listings().suggestedPrices(sale.item);
        BigDecimal um = plugin.pricing().currentBuyPrice(sale.item);
        BigDecimal buyback = plugin.pricing().currentBuyback(sale.item);

        Gui gui = new Gui("<dark_gray>\u2726 <gold>SET YOUR PRICE <dark_gray>\u2726", 5);

        gui.set(4, Gui.icon(sale.item.material(),
                "<white><b>" + NumberFormatter.count(sale.quantity) + "x "
                        + sale.item.displayName(),
                "<gray>Prices below are <white>per item</white>.",
                "",
                "<gray>Universal Market sells at <gold>" + NumberFormatter.money(um),
                "<gray>Server buyback is <green>" + NumberFormatter.money(buyback)));

        String[] labels = {"Low", "Fair", "High"};
        Material[] icons = {Material.LIME_DYE, Material.YELLOW_DYE, Material.ORANGE_DYE};
        String[] blurb = {
                "Sells fastest. Well under the market.",
                "Balanced. The usual going rate.",
                "Most profit, slowest to sell."
        };
        int[] slots = {19, 21, 23};

        for (int i = 0; i < 3; i++) {
            final BigDecimal price = suggested[i];
            gui.set(slots[i], Gui.icon(icons[i],
                    "<yellow><b>" + labels[i] + ": <aqua>" + NumberFormatter.money(price),
                    "<gray>" + blurb[i],
                    "",
                    "<gray>Total if it all sells:",
                    "<green>" + NumberFormatter.money(
                            price.multiply(BigDecimal.valueOf(sale.quantity)))),
                    p -> { plugin.sounds().click(p); plugin.sellFlow().choosePrice(p, price); });
        }

        gui.set(25, Gui.icon(Material.NAME_TAG,
                "<white><b>Custom price",
                "<gray>Type your own price in chat.",
                "",
                "<gray>Accepts <white>82</white>, <white>1.7k</white>, <white>200m</white>"),
                p -> { plugin.sounds().click(p); plugin.sellFlow().awaitCustomPrice(p); });

        gui.set(36, Gui.icon(Material.RED_CONCRETE,
                "<red><b>\u2715 Cancel sale",
                "<gray>Removes the stall and returns",
                "<gray>everything inside it."),
                p -> { plugin.sounds().error(p); p.closeInventory(); plugin.sellFlow().cancel(p); });

        gui.fillEmpty().open(player);
        plugin.sounds().confirm(player);
    }

    public void openSellConfirm(Player player) {
        SellFlowService.PendingSale sale = plugin.sellFlow().pendingFor(player.getUniqueId());
        if (sale == null || sale.chosenPrice == null) return;

        BigDecimal total = sale.chosenPrice.multiply(BigDecimal.valueOf(sale.quantity));
        Gui gui = new Gui("<dark_gray>\u2726 <gold>CONFIRM LISTING <dark_gray>\u2726", 3);

        gui.set(13, Gui.icon(sale.item.material(),
                "<white><b>" + NumberFormatter.count(sale.quantity) + "x "
                        + sale.item.displayName(),
                "<gray>Price: <aqua><b>" + NumberFormatter.money(sale.chosenPrice)
                        + "</b></aqua> <gray>each",
                "<gray>Total if it all sells: <green>" + NumberFormatter.money(total),
                "",
                "<red>This cannot be undone.",
                "<gray>Listings cannot be cancelled or refunded."));

        gui.set(11, Gui.icon(Material.LIME_CONCRETE,
                "<green><b>\u2713 List it",
                "<gray>Your goods go on sale now."),
                p -> {
                    boolean ok = plugin.sellFlow().confirm(p);
                    if (ok) plugin.sounds().sell(p); else plugin.sounds().error(p);
                    p.closeInventory();
                });

        gui.set(15, Gui.icon(Material.RED_CONCRETE,
                "<red><b>\u2190 Change price",
                "<gray>Go back without listing."),
                p -> { plugin.sounds().click(p); openPriceChooser(p); });

        gui.fillEmpty().open(player);
        plugin.sounds().confirm(player);
    }

    // ==================================================================
    // Account / leaderboard
    // ==================================================================

    public void openAccount(Player player) {
        BigDecimal balance = plugin.economy().balance(player);
        Gui gui = new Gui("<dark_gray>\u2726 <white>MY ACCOUNT <dark_gray>\u2726", 6);

        gui.set(4, Gui.playerHead(player,
                "<green>" + NumberFormatter.money(balance),
                "<gray>Exact: <white>" + NumberFormatter.exactMoney(balance),
                "<gray>Rank: " + wealthTier(balance)));

        gui.set(22, Gui.icon(Material.PAPER, "<gray>Loading statistics..."));
        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.fillEmpty().open(player);

        plugin.transactions().stats(player.getUniqueId()).thenAccept(stats ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui open)
                            || open != gui) return;
                    gui.set(22, Gui.icon(Material.PAPER, "<white>Lifetime statistics",
                            "<gray>Earned: <green>" + NumberFormatter.money(stats.earned()),
                            "<gray>Spent: <gold>" + NumberFormatter.money(stats.spent()),
                            "",
                            "<gray>Market purchases: <white>" + stats.purchases(),
                            "<gray>Sales to server: <white>" + stats.sales(),
                            "<gray>Shop revenue: <aqua>"
                                    + NumberFormatter.money(stats.shopRevenue())));
                }));
    }

    public void openLeaderboard(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>LEADERBOARD <dark_gray>\u2726", 6);
        var top = plugin.leaderboard().top(10);

        if (top.isEmpty()) {
            gui.set(22, Gui.icon(Material.WHITE_STAINED_GLASS_PANE, "<gray>Not ranked yet"));
        } else {
            int[] slots = {13, 21, 23, 29, 30, 31, 32, 33, 39, 41};
            for (int i = 0; i < top.size() && i < slots.length; i++) {
                var entry = top.get(i);
                String colour = i == 0 ? "<gold>" : i == 1 ? "<white>"
                        : i == 2 ? "<yellow>" : "<gray>";
                gui.set(slots[i], Gui.playerHead(Bukkit.getOfflinePlayer(entry.uuid()),
                        colour + "<b>#" + (i + 1) + " " + entry.name(),
                        "<green>" + NumberFormatter.money(entry.balance()),
                        "<gray>" + wealthTier(entry.balance())));
            }
        }

        int rank = plugin.leaderboard().rankOf(player.getUniqueId());
        gui.set(49, Gui.playerHead(player, "<white>Your standing",
                "<gray>Rank: <white>" + (rank > 0 ? "#" + rank : "unranked"),
                "<gray>Balance: <green>"
                        + NumberFormatter.money(plugin.economy().balance(player))));

        gui.set(47, Gui.icon(Material.OAK_SIGN, "<yellow>Toggle sidebar",
                "<gray>Currently: "
                        + (plugin.leaderboard().isSidebarOn(player.getUniqueId())
                        ? "<green>on" : "<red>off")),
                p -> {
                    plugin.leaderboard().toggleSidebar(p);
                    plugin.sounds().click(p);
                    openLeaderboard(p);
                });

        gui.set(45, backButton(), p -> { plugin.sounds().click(p); openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    public void openSell(Player player) {
        new SellMenu(plugin, this, player).open();
    }

    // ==================================================================
    // Shared helpers
    // ==================================================================

    private org.bukkit.inventory.ItemStack backButton() {
        return Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back");
    }

    private void buyDirect(Player player, MarketItem item, int quantity) {
        var result = plugin.purchases().buy(player, item, quantity);
        if (result.success()) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("buy.success")
                    .replace("%qty%", String.valueOf(result.quantity()))
                    .replace("%item%", item.displayName())
                    .replace("%price%", NumberFormatter.money(result.amount()))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().buy(player);
            plugin.announcements().checkPromotion(player);
        } else {
            player.sendMessage(Gui.MM.deserialize(
                    plugin.messages().get(result.messageKey())
                            .replace("%item%", item.displayName())
                            .replace("%price%", NumberFormatter.money(result.amount()))
                            .replace("%balance%",
                                    NumberFormatter.money(plugin.economy().balance(player)))
                            .replace("%time%",
                                    NumberFormatter.duration(result.amount().longValue()))));
            plugin.sounds().broke(player);
        }
    }

    private void sellDirect(Player player, MarketItem item, int quantity) {
        if (quantity <= 0) { plugin.sounds().error(player); return; }
        BigDecimal paid = plugin.sell().sell(player, item, quantity);
        if (paid == null) { plugin.sounds().error(player); return; }

        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("sell.success")
                .replace("%amount%", NumberFormatter.money(paid))
                .replace("%qty%", String.valueOf(quantity))
                .replace("%item%", item.displayName())));
        plugin.sounds().sell(player);
        plugin.announcements().checkPromotion(player);
    }

    private void buyListing(Player player, long listingId, int quantity) {
        var result = plugin.listings().buy(player, listingId, quantity);
        if (result.success()) {
            plugin.sounds().buy(player);
            plugin.announcements().checkPromotion(player);
        } else {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get(result.messageKey())));
            plugin.sounds().error(player);
        }
    }

    public String wealthTier(BigDecimal balance) {
        String name = "<gray>Starter";
        for (Object raw : plugin.getConfig().getMapList("wealth-tiers")) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            try {
                long threshold = Long.parseLong(String.valueOf(map.get("threshold")));
                if (balance.compareTo(BigDecimal.valueOf(threshold)) >= 0) {
                    name = String.valueOf(map.get("color")) + map.get("name");
                }
            } catch (Exception ignored) { }
        }
        return name;
    }
}
