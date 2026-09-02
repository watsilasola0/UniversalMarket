package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every Universal Market screen.
 *
 * Colour language follows spec section 6 throughout and is applied consistently:
 *   white   item and player names        green  balance and money received
 *   gold    Universal Market prices      aqua   player-shop prices
 *   yellow  deals and notices            red    errors and insufficient funds
 *   gray    secondary text               dark_purple  rare and prestige only
 *
 * Balances are read fresh every time a screen is built, so reopening any menu
 * shows current money without needing a refresh task.
 */
public final class MarketMenus {

    private final UniversalMarketPlugin plugin;

    private final BuyMenu buyMenu;

    public MarketMenus(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.buyMenu = new BuyMenu(plugin, this);
    }

    public BuyMenu buyMenu() {
        return buyMenu;
    }

    // ==================================================================
    // Home
    // ==================================================================

    public void openHome(Player player) {
        BigDecimal balance = plugin.economy().balance(player);
        String tier = wealthTier(balance);

        Gui gui = new Gui("<dark_gray>✦ <gold>UNIVERSAL MARKET <dark_gray>✦", 6);

        // Layout: three tidy rows of four, evenly spaced on the odd columns
        // (19/21/23/25, 28/30/32/34, 37/39/41/43) with the balance centred at
        // the top. Spec section 58's ordering, laid out symmetrically so it
        // reads as a panel rather than a scattered pile of icons.
        gui.set(4, Gui.icon(Material.PLAYER_HEAD,
                "<green><b>" + NumberFormatter.money(balance),
                "<gray>Wealth rank: " + tier,
                "<gray>" + player.getName()));

        // ---- row 1: core trading ----
        gui.set(19, Gui.icon(Material.CHEST,
                "<gold><b>BUY ITEMS",
                "<gray>Browse the market catalogue.",
                "",
                "<gray>Ten categories, real prices,",
                "<gray>and your balance always visible."),
                p -> buyMenu.openCategories(p));

        gui.set(21, Gui.icon(Material.HOPPER,
                "<gold><b>SELL ITEMS",
                "<gray>Sell your items to the server.",
                "",
                "<gray>The server pays less than it",
                "<gray>charges - player shops pay more."),
                p -> plugin.menus().openSell(p));

        gui.set(23, Gui.icon(Material.EMERALD,
                "<aqua><b>PLAYER SHOPS",
                "<gray>Real chest shops built by",
                "<gray>other players.",
                "",
                plugin.playerShops().isAvailable()
                        ? "<gray>Indexed: <white>" + plugin.playerShops().indexSize()
                        : "<red>QuickShop unavailable"),
                this::openPlayerShops);

        gui.set(25, Gui.icon(Material.COMPASS,
                "<gold><b>FIND AN ITEM",
                "<gray>Look up any item's price across",
                "<gray>the market and player shops.",
                "",
                "<gray>Use <white>/um price <item>"),
                p -> {
                    p.closeInventory();
                    p.sendMessage(Gui.MM.deserialize(
                            "<gray>Type <white>/um price <item></white> to look up any item."));
                });

        // ---- row 2: rotations ----
        gui.set(28, Gui.icon(Material.SUNFLOWER,
                "<yellow><b>DAILY DEALS",
                "<gray>Discounted items, rerolled",
                "<gray>each market cycle.",
                "",
                "<gray>Active: <white>" + plugin.pricing().dailyDeals().size()),
                this::openDeals);

        gui.set(30, Gui.icon(Material.BLAZE_POWDER,
                "<yellow><b>HIGH DEMAND",
                "<gray>The server pays extra for",
                "<gray>these right now.",
                "",
                "<gray>Active: <white>" + plugin.pricing().highDemand().size()),
                this::openHighDemand);

        gui.set(32, Gui.icon(Material.END_CRYSTAL,
                "<dark_purple><b>RARE GOODS",
                "<gray>Limited-purchase prestige items.",
                "",
                "<gray>Limits are per player and",
                "<gray>reset on a timer."),
                this::openRareGoods);

        gui.set(34, Gui.icon(Material.WRITTEN_BOOK,
                "<gold><b>CONTRACTS",
                "<gray>Delivery jobs for cash rewards.",
                "",
                "<red>Not built yet."),
                p -> p.sendMessage(Gui.MM.deserialize("<red>Contracts are not built yet.")));

        // ---- row 3: personal ----
        gui.set(37, Gui.icon(Material.GOLD_INGOT,
                "<green><b>SEND MONEY",
                "<gray>Pay another player.",
                "",
                "<gray>Fee: <yellow>" + feePercent() + "%</yellow> paid by you.",
                "<gray>They receive the full amount.",
                "",
                "<gray>Use <white>/um pay <player> <amount>"),
                p -> {
                    p.closeInventory();
                    p.sendMessage(Gui.MM.deserialize(
                            "<gray>Use <white>/um pay <player> <amount></white>  e.g. <white>/um pay Allan 10M"));
                });

        gui.set(39, Gui.icon(Material.DIAMOND,
                "<gold><b>LEADERBOARD",
                "<gray>Richest players on the server.",
                "",
                "<gray>Ranked: <white>" + plugin.leaderboard().size(),
                "<yellow>Click to view"),
                this::openLeaderboard);

        gui.set(41, Gui.icon(Material.BOOK,
                "<white><b>MY ACCOUNT",
                "<gray>Balance, statistics and history."),
                this::openAccount);

        gui.set(43, Gui.icon(Material.CLOCK,
                "<gold><b>MARKET REPORT",
                "<gray>Today's deals, demand and timers."),
                this::openStatus);

        gui.set(49, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Buy - enters the creative browsing session
    // ==================================================================

    /**
     * The creative-browser path is retained but no longer wired to BUY ITEMS.
     * Reachable via /um creative for anyone who prefers the native screen and
     * does not mind buying without visible prices.
     */
    public void enterCreativeMarket(Player player) {
        player.closeInventory();
        if (!player.hasPermission("universalmarket.buy")) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("general.no-permission")));
            return;
        }
        if (plugin.creative() == null) {
            player.sendMessage(Gui.MM.deserialize(
                    "<red>✕ The creative browser is unavailable - PacketEvents did not hook."));
            return;
        }
        if (plugin.bedrock().isBedrock(player.getUniqueId())) {
            player.sendMessage(Gui.MM.deserialize(
                    plugin.messages().get("creative.bedrock-not-supported")));
            return;
        }
        plugin.creative().enterMarket(player);
    }

    // ==================================================================
    // Deals / demand / rare
    // ==================================================================

    private void openDeals(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <yellow>DAILY DEALS <dark_gray>✦", 6);
        Map<String, Double> deals = plugin.pricing().dailyDeals();

        if (deals.isEmpty()) {
            gui.set(22, Gui.icon(Material.BARRIER, "<gray>No deals active",
                    "<gray>Deals reroll next cycle."));
        } else {
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
                        "<gray>Normal: <gray><st>" + NumberFormatter.money(item.umBuyPrice()) + "</st>",
                        "<gray>Today: <gold>" + NumberFormatter.money(now) + "</gold> <gray>each"));
            }
        }
        backAndClose(gui);
        gui.fillEmpty().open(player);
    }

    private void openHighDemand(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <yellow>HIGH DEMAND <dark_gray>✦", 6);
        Map<String, Double> demand = plugin.pricing().highDemand();

        if (demand.isEmpty()) {
            gui.set(22, Gui.icon(Material.BARRIER, "<gray>Nothing in high demand",
                    "<gray>Check back next cycle."));
        } else {
            int slot = 10;
            for (Map.Entry<String, Double> entry : demand.entrySet()) {
                MarketItem item = plugin.catalog().byId(entry.getKey());
                if (item == null) continue;
                if (slot % 9 == 8) slot += 2;
                if (slot >= 44) break;

                gui.set(slot++, Gui.icon(item.material(),
                        "<white>" + item.displayName(),
                        "<green>+" + NumberFormatter.percent(entry.getValue()) + " buyback",
                        "",
                        "<gray>Base: <gray>" + NumberFormatter.money(item.serverBuybackBase()),
                        "<gray>Now: <green>"
                                + NumberFormatter.money(plugin.pricing().currentBuyback(item))
                                + "</green> <gray>each"));
            }
        }
        backAndClose(gui);
        gui.fillEmpty().open(player);
    }

    private void openRareGoods(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <dark_purple>RARE GOODS <dark_gray>✦", 6);
        List<MarketItem> rare = new ArrayList<>();
        for (MarketItem item : plugin.catalog().all()) {
            if (item.rare()) rare.add(item);
        }

        int slot = 10;
        for (MarketItem item : rare) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;

            var allowance = plugin.rareGoods().checkAllowance(player.getUniqueId(), item, 1);
            List<String> lore = new ArrayList<>();
            lore.add("<gold>" + NumberFormatter.money(plugin.pricing().currentBuyPrice(item)));
            lore.add("");
            lore.add("<gray>Limit: <white>" + item.purchaseLimit() + "</white> <gray>per player");
            if (allowance.allowed()) {
                lore.add("<green>Available now");
            } else {
                lore.add("<red>Limit reached");
                lore.add("<gray>Resets in <yellow>"
                        + NumberFormatter.duration(allowance.resetInMillis()));
            }
            lore.add("");
            lore.add("<gray>Buy from the creative browser.");
            gui.set(slot++, Gui.icon(item.material(),
                    "<dark_purple>" + item.displayName(), lore));
        }
        if (rare.isEmpty()) {
            gui.set(22, Gui.icon(Material.BARRIER, "<gray>No rare goods configured"));
        }
        backAndClose(gui);
        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Player shops
    // ==================================================================

    private void openPlayerShops(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <aqua>PLAYER SHOPS <dark_gray>✦", 6);

        if (!plugin.playerShops().isAvailable()) {
            gui.set(22, Gui.icon(Material.BARRIER,
                    "<red>Player shops unavailable",
                    "<gray>QuickShop-Hikari was not detected,",
                    "<gray>or its API could not be bound.",
                    "",
                    "<gray>Run <white>/um debug quickshop</white> for detail."));
        } else if (plugin.playerShops().indexSize() == 0) {
            gui.set(22, Gui.icon(Material.CHEST,
                    "<gray>No shops indexed yet",
                    "<gray>Build a QuickShop chest shop and",
                    "<gray>it will appear within a minute."));
        } else {
            gui.set(22, Gui.icon(Material.EMERALD,
                    "<aqua>" + plugin.playerShops().indexSize() + " shops indexed",
                    "<gray>Use <white>/um price <item></white> to see",
                    "<gray>which shops sell it and for how much."));
        }
        backAndClose(gui);
        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Account
    // ==================================================================

    private void openAccount(Player player) {
        BigDecimal balance = plugin.economy().balance(player);

        Gui gui = new Gui("<dark_gray>✦ <white>MY ACCOUNT <dark_gray>✦", 6);
        gui.set(4, Gui.icon(Material.PLAYER_HEAD,
                "<green>" + NumberFormatter.money(balance),
                "<gray>Exact: <white>" + NumberFormatter.exactMoney(balance),
                "<gray>Rank: " + wealthTier(balance)));

        gui.set(22, Gui.icon(Material.PAPER,
                "<gray>Loading statistics...",
                "<gray>One moment."));
        backAndClose(gui);
        gui.fillEmpty().open(player);

        // Stats live in SQLite, so fetch off-thread and repaint when it lands.
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
                            "",
                            "<gray>Fees paid: <yellow>" + NumberFormatter.money(stats.feesPaid()),
                            "<gray>Shop revenue: <aqua>" + NumberFormatter.money(stats.shopRevenue())));
                }));
    }

    // ==================================================================
    // Market report
    // ==================================================================

    private void openStatus(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <gold>MARKET REPORT <dark_gray>✦", 6);

        gui.set(11, Gui.icon(Material.SUNFLOWER, "<yellow>Daily Deals",
                "<gray>Active: <white>" + plugin.pricing().dailyDeals().size()), this::openDeals);
        gui.set(13, Gui.icon(Material.BLAZE_POWDER, "<yellow>High Demand",
                "<gray>Active: <white>" + plugin.pricing().highDemand().size()), this::openHighDemand);
        gui.set(15, Gui.icon(Material.CLOCK, "<gold>Next cycle",
                "<gray>Rerolls in <yellow>"
                        + NumberFormatter.duration(plugin.pricing().cycleEndsInMillis())));

        gui.set(31, Gui.icon(Material.KNOWLEDGE_BOOK, "<white>How this market works",
                "<gray>The Universal Market is the",
                "<gray>expensive, always-available option.",
                "",
                "<gray>Player shops are usually cheaper.",
                "<gray>Selling to the server pays least.",
                "",
                "<gray>Build a shop and undercut the",
                "<gray>market - that is how you get rich."));

        backAndClose(gui);
        gui.fillEmpty().open(player);
    }

    // ==================================================================
    // Shared bits
    // ==================================================================

    private void openLeaderboard(Player player) {
        Gui gui = new Gui("<dark_gray>✦ <gold>LEADERBOARD <dark_gray>✦", 6);
        var top = plugin.leaderboard().top(10);

        if (top.isEmpty()) {
            gui.set(22, Gui.icon(Material.BARRIER, "<gray>Not ranked yet",
                    "<gray>The board rebuilds every couple",
                    "<gray>of minutes."));
        } else {
            int[] slots = {13, 21, 23, 29, 30, 31, 32, 33, 39, 41};
            for (int i = 0; i < top.size() && i < slots.length; i++) {
                var entry = top.get(i);
                Material icon = switch (i) {
                    case 0 -> Material.NETHERITE_BLOCK;
                    case 1 -> Material.DIAMOND_BLOCK;
                    case 2 -> Material.GOLD_BLOCK;
                    default -> Material.IRON_BLOCK;
                };
                String colour = i == 0 ? "<gold>" : i == 1 ? "<white>" : i == 2 ? "<yellow>" : "<gray>";
                gui.set(slots[i], Gui.icon(icon,
                        colour + "<b>#" + (i + 1) + " " + entry.name(),
                        "<green>" + NumberFormatter.money(entry.balance()),
                        "<gray>" + wealthTier(entry.balance())));
            }
        }

        int rank = plugin.leaderboard().rankOf(player.getUniqueId());
        BigDecimal balance = plugin.economy().balance(player);
        gui.set(49, Gui.icon(Material.PLAYER_HEAD,
                "<white>Your standing",
                "<gray>Rank: <white>" + (rank > 0 ? "#" + rank : "unranked"),
                "<gray>Balance: <green>" + NumberFormatter.money(balance),
                "<gray>Tier: " + wealthTier(balance)));

        gui.set(47, Gui.icon(Material.OAK_SIGN,
                "<yellow>Toggle on-screen sidebar",
                "<gray>Shows the top ten beside your",
                "<gray>screen while you play.",
                "",
                "<gray>Currently: " + (plugin.leaderboard().isSidebarOn(player.getUniqueId())
                        ? "<green>on" : "<red>off")),
                p -> {
                    boolean on = plugin.leaderboard().toggleSidebar(p);
                    p.sendMessage(Gui.MM.deserialize(on
                            ? "<green>✓ Sidebar enabled."
                            : "<gray>Sidebar hidden."));
                    openLeaderboard(p);
                });

        gui.set(45, Gui.icon(Material.ARROW, "<gray>← Back"), this::openHome);
        gui.set(53, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
        gui.fillEmpty().open(player);
    }

    private void backAndClose(Gui gui) {
        gui.set(45, Gui.icon(Material.ARROW, "<gray>← Back"), this::openHome);
        gui.set(49, Gui.icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
    }

    private String feePercent() {
        double fee = plugin.getConfig().getDouble("economy.payment-fee-percent", 7.45);
        return String.valueOf(fee);
    }

    /** Wealth tier label from config thresholds (spec section 37, display only). */
    public String wealthTier(BigDecimal balance) {
        String name = "<gray>Starter";
        var section = plugin.getConfig().getMapList("wealth-tiers");
        for (Object raw : section) {
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

    // ==================================================================
    // Sell menu lives in its own class for size
    // ==================================================================

    public void openSell(Player player) {
        new SellMenu(plugin, this).open(player);
    }
}
