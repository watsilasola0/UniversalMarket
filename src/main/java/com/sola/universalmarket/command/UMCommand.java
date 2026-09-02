package com.sola.universalmarket.command;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /um and its subcommands.
 *
 * Admin subcommands are permission gated individually rather than behind a
 * single blanket check, so you can hand out /um reload without also handing out
 * limit resets.
 */
public final class UMCommand implements CommandExecutor, TabCompleter {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public UMCommand(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender to, String miniMessage) {
        to.sendMessage(mm.deserialize(miniMessage));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openMarket(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help"     -> sendHelp(sender);
            case "terminal" -> handleTerminal(sender, args);
            case "close"    -> handleClose(sender);
            case "pay"      -> handlePay(sender, args);
            case "sell"     -> handleSell(sender);
            case "creative" -> handleCreative(sender);
            case "give", "addmoney" -> handleGive(sender, args);
            case "take", "removemoney" -> handleTake(sender, args);
            case "board", "sidebar" -> handleBoard(sender);
            case "top", "leaderboard", "baltop" -> handleTop(sender);
            case "price"    -> handlePrice(sender, args);
            case "balance", "bal" -> handleBalance(sender);
            case "status", "marketstatus" -> handleStatus(sender);
            case "reload"   -> handleReload(sender);
            case "debug"    -> handleDebug(sender, args);
            case "resetdaily" -> handleResetCycle(sender);
            case "resetlimits" -> handleResetLimits(sender, args);
            default -> msg(sender, "<red>Unknown subcommand. Try <white>/um help</white>.");
        }
        return true;
    }

    // ==================================================================
    // Player subcommands
    // ==================================================================

    /**
     * Bare /um now opens the chest menu rather than jumping straight into the
     * creative session. The menu's BUY ITEMS button is what starts browsing.
     */
    private void openMarket(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        if (!player.hasPermission("universalmarket.use")) {
            msg(player, plugin.messages().get("general.no-permission"));
            return;
        }
        if (plugin.creative() != null && plugin.creative().inMarket(player)) {
            msg(player, "<gray>You are already browsing. <white>/um close</white> to leave.");
            return;
        }
        if (plugin.menus() == null) {
            msg(player, "<red>The market menu is unavailable.");
            return;
        }
        plugin.menus().openHome(player);
    }

    /**
     * Player-to-player payment. Spec section 34: the SENDER pays the fee on top
     * and the recipient receives the exact amount typed. The fee is applied once,
     * here, using Vault withdraw/deposit directly - we never route through
     * NewEconomy's own /pay path, so its internal fee cannot double-charge.
     */
    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        if (!player.hasPermission("universalmarket.pay")) {
            msg(player, plugin.messages().get("general.no-permission"));
            return;
        }
        if (args.length < 3) {
            msg(player, "<gray>Usage: <white>/um pay <player> <amount></white>  e.g. <white>/um pay Allan 10M");
            return;
        }

        org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(args[1]);
            if (offline.hasPlayedBefore()) target = offline;
        }
        if (target == null || target.getName() == null) {
            msg(player, plugin.messages().get("general.unknown-player").replace("%player%", args[1]));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())
                && !plugin.getConfig().getBoolean("economy.allow-self-payment", false)) {
            msg(player, plugin.messages().get("pay.self"));
            return;
        }

        java.math.BigDecimal amount = NumberFormatter.parse(args[2]);
        long minimum = plugin.getConfig().getLong("economy.minimum-payment", 1);
        if (amount == null || amount.compareTo(java.math.BigDecimal.valueOf(minimum)) < 0) {
            msg(player, plugin.messages().get("pay.invalid-amount"));
            return;
        }

        double feePercent = plugin.getConfig().getDouble("economy.payment-fee-percent", 7.45);
        java.math.BigDecimal fee = amount
                .multiply(java.math.BigDecimal.valueOf(feePercent / 100.0))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal total = amount.add(fee);

        java.math.BigDecimal balance = plugin.economy().balance(player);
        if (balance.compareTo(total) < 0) {
            msg(player, plugin.messages().get("pay.insufficient")
                    .replace("%total%", NumberFormatter.money(total))
                    .replace("%amount%", NumberFormatter.money(amount))
                    .replace("%fee%", NumberFormatter.money(fee))
                    .replace("%balance%", NumberFormatter.money(balance)));
            return;
        }

        if (!plugin.economy().transfer(player, target, amount, fee)) {
            msg(player, plugin.messages().get("pay.rollback"));
            return;
        }

        plugin.transactions().recordPayment(player.getUniqueId(), player.getName(),
                target.getUniqueId(), target.getName(), amount, fee);

        msg(player, plugin.messages().get("pay.sent")
                .replace("%amount%", NumberFormatter.money(amount))
                .replace("%player%", target.getName())
                .replace("%fee%", NumberFormatter.money(fee))
                .replace("%total%", NumberFormatter.money(total)));

        if (target.isOnline() && target.getPlayer() != null) {
            msg(target.getPlayer(), plugin.messages().get("pay.received")
                    .replace("%amount%", NumberFormatter.money(amount))
                    .replace("%player%", player.getName()));
        }
    }


    private void handleClose(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        if (plugin.creative() != null && plugin.creative().inMarket(player)) {
            plugin.creative().exitMarket(player, "command");
            msg(player, plugin.messages().get("creative.exited"));
        } else {
            msg(player, "<gray>You are not browsing the market.");
        }
    }

    /**
     * Admin money grant. Spec section 53 is explicit that the economy must have
     * no automatic faucets, so this is the ONLY way money enters outside trade,
     * buyback and contracts - and it is op-only.
     *
     * Recorded as ADMIN_ADJUST rather than earnings, so leaderboard.
     * count-admin-grants-as-earnings can keep test money out of lifetime stats.
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("universalmarket.admin")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        if (args.length < 3) {
            msg(sender, "<gray>Usage: <white>/um give <player> <amount></white>  e.g. <white>/um give "
                    + (sender instanceof Player p ? p.getName() : "Sola") + " 500M");
            return;
        }
        org.bukkit.OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            msg(sender, plugin.messages().get("general.unknown-player").replace("%player%", args[1]));
            return;
        }
        java.math.BigDecimal amount = NumberFormatter.parse(args[2]);
        if (amount == null || amount.signum() <= 0) {
            msg(sender, plugin.messages().get("pay.invalid-amount"));
            return;
        }
        if (!plugin.economy().deposit(target, amount)) {
            msg(sender, "<red>✕ The economy provider refused that deposit.");
            return;
        }
        msg(sender, "<green>✓ Gave <white>" + NumberFormatter.money(amount)
                + "</white> to <white>" + target.getName() + "</white><green>. New balance: "
                + NumberFormatter.money(plugin.economy().balance(target)));
        if (target.isOnline() && target.getPlayer() != null) {
            msg(target.getPlayer(), "<green>+ " + NumberFormatter.money(amount)
                    + " <gray>(admin grant)");
        }
        plugin.leaderboard().refresh();
    }

    private void handleTake(CommandSender sender, String[] args) {
        if (!sender.hasPermission("universalmarket.admin")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        if (args.length < 3) {
            msg(sender, "<gray>Usage: <white>/um take <player> <amount>");
            return;
        }
        org.bukkit.OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            msg(sender, plugin.messages().get("general.unknown-player").replace("%player%", args[1]));
            return;
        }
        java.math.BigDecimal amount = NumberFormatter.parse(args[2]);
        if (amount == null || amount.signum() <= 0) {
            msg(sender, plugin.messages().get("pay.invalid-amount"));
            return;
        }
        if (!plugin.economy().withdraw(target, amount)) {
            msg(sender, "<red>✕ Could not withdraw that - insufficient funds or economy error.");
            return;
        }
        msg(sender, "<yellow>Took <white>" + NumberFormatter.money(amount)
                + "</white> from <white>" + target.getName() + "</white>. New balance: "
                + NumberFormatter.money(plugin.economy().balance(target)));
        plugin.leaderboard().refresh();
    }

    private void handleBoard(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        boolean on = plugin.leaderboard().toggleSidebar(player);
        msg(player, on
                ? "<green>✓ Leaderboard sidebar enabled. <gray>Run <white>/um board</white> to hide it."
                : "<gray>Leaderboard sidebar hidden.");
    }

    private void handleTop(CommandSender sender) {
        var top = plugin.leaderboard().top(10);
        if (top.isEmpty()) {
            msg(sender, "<gray>The leaderboard has not been built yet - try again shortly.");
            return;
        }
        msg(sender, "<gold><b>✦ RICHEST PLAYERS ✦");
        int rank = 1;
        for (var entry : top) {
            String colour = rank == 1 ? "<gold>" : rank == 2 ? "<white>" : rank == 3 ? "<yellow>" : "<gray>";
            msg(sender, colour + "#" + rank + " <white>" + entry.name()
                    + " <green>" + NumberFormatter.money(entry.balance()));
            rank++;
        }
        if (sender instanceof Player player) {
            int own = plugin.leaderboard().rankOf(player.getUniqueId());
            msg(player, "<dark_gray>─────────────");
            msg(player, "<gray>You: <white>" + (own > 0 ? "#" + own : "unranked")
                    + " <green>" + NumberFormatter.money(plugin.economy().balance(player)));
        }
    }

    private org.bukkit.OfflinePlayer resolvePlayer(String name) {
        org.bukkit.OfflinePlayer online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) return online;
        org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(name);
        return (offline.hasPlayedBefore() && offline.getName() != null) ? offline : null;
    }

    private void handleCreative(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        plugin.menus().enterCreativeMarket(player);
    }

    private void handleSell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        if (!player.hasPermission("universalmarket.sell")) {
            msg(player, plugin.messages().get("general.no-permission"));
            return;
        }
        plugin.menus().openSell(player);
    }

    private void handleTerminal(CommandSender sender, String[] args) {
        // /um terminal <player> is the admin form
        if (args.length >= 2) {
            if (!sender.hasPermission("universalmarket.terminal.give")) {
                msg(sender, plugin.messages().get("general.no-permission"));
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, plugin.messages().get("general.unknown-player").replace("%player%", args[1]));
                return;
            }
            plugin.terminal().repair(target);
            msg(sender, "<green>✓ Checked " + target.getName() + "'s terminal.");
            return;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        if (!player.hasPermission("universalmarket.terminal")) {
            msg(player, plugin.messages().get("general.no-permission"));
            return;
        }
        // repair() is NOT "give me one" - it removes duplicates, or restores a
        // genuinely missing terminal. It can never produce a second copy.
        plugin.terminal().repair(player);
    }

    private void handleBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, plugin.messages().get("general.player-only"));
            return;
        }
        BigDecimal balance = plugin.economy().balance(player);
        msg(player, "<gray>Balance: <green>" + NumberFormatter.money(balance)
                + "</green> <dark_gray>(" + NumberFormatter.exactMoney(balance) + ")");
    }

    private void handlePrice(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "<gray>Usage: <white>/um price <item></white>");
            return;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        List<MarketItem> results = plugin.catalog().search(query, 5);
        if (results.isEmpty()) {
            msg(sender, "<red>✕ No market entry matches <white>" + query + "</white>.");
            return;
        }
        for (MarketItem item : results) {
            BigDecimal buy = plugin.pricing().currentBuyPrice(item);
            BigDecimal back = plugin.pricing().currentBuyback(item);
            int stack = item.material() == null ? 64 : item.material().getMaxStackSize();

            msg(sender, "<white>" + item.displayName() + " <dark_gray>(" + item.id() + ")");
            msg(sender, "  <gray>Universal Market: <gold>" + NumberFormatter.money(buy)
                    + "</gold> <dark_gray>each / " + NumberFormatter.money(
                            buy.multiply(BigDecimal.valueOf(stack))) + " per " + stack);
            msg(sender, "  <gray>Server buyback: <green>" + NumberFormatter.money(back) + "</green> <dark_gray>each");
            msg(sender, "  <gray>Suggested shop price: <aqua>"
                    + NumberFormatter.money(item.suggestedShopMin()) + " - "
                    + NumberFormatter.money(item.suggestedShopMax()) + "</aqua> <dark_gray>each");

            double discount = plugin.pricing().dealDiscount(item);
            if (discount > 0) {
                msg(sender, "  <yellow>Daily Deal: " + NumberFormatter.percent(discount) + " OFF");
            }
            double bonus = plugin.pricing().demandBonus(item);
            if (bonus > 0) {
                msg(sender, "  <yellow>High Demand: +" + NumberFormatter.percent(bonus) + " buyback");
            }
        }
    }

    private void handleStatus(CommandSender sender) {
        msg(sender, "<gray>─────────────────────────────");
        msg(sender, "<gold>MARKET TODAY");
        msg(sender, "");

        var deals = plugin.pricing().deals();
        msg(sender, "<yellow>Daily Deals:");
        if (deals.isEmpty()) msg(sender, "  <dark_gray>none active");
        deals.forEach((id, discount) -> {
            MarketItem item = plugin.catalog().byId(id);
            if (item != null) {
                msg(sender, "  <white>" + item.displayName()
                        + " <yellow>-" + NumberFormatter.percent(discount));
            }
        });

        msg(sender, "");
        msg(sender, "<yellow>High Demand:");
        var demand = plugin.pricing().demand();
        if (demand.isEmpty()) msg(sender, "  <dark_gray>none active");
        demand.forEach((id, bonus) -> {
            MarketItem item = plugin.catalog().byId(id);
            if (item != null) {
                msg(sender, "  <white>" + item.displayName()
                        + " <green>+" + NumberFormatter.percent(bonus));
            }
        });

        msg(sender, "");
        msg(sender, "<gray>Next cycle in: <white>"
                + NumberFormatter.duration(plugin.pricing().cycleEndsInMillis()));
        msg(sender, "<gray>─────────────────────────────");
    }

    // ==================================================================
    // Admin subcommands
    // ==================================================================

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("universalmarket.reload")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        int count = plugin.reloadEverything();
        msg(sender, plugin.messages().get("general.reloaded")
                .replace("%count%", String.valueOf(count)));
    }

    private void handleResetCycle(CommandSender sender) {
        if (!sender.hasPermission("universalmarket.market.reset")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        plugin.pricing().rollCycle();
        msg(sender, "<green>✓ Rolled a new market cycle.");
    }

    private void handleResetLimits(CommandSender sender, String[] args) {
        if (!sender.hasPermission("universalmarket.market.reset")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            msg(sender, "<gray>Usage: <white>/um resetlimits <player></white>");
            return;
        }
        var target = Bukkit.getOfflinePlayer(args[1]);
        plugin.rareGoods().resetPlayer(target.getUniqueId());
        msg(sender, "<green>✓ Cleared sell and rare limits for <white>" + args[1] + "</white>.");
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("universalmarket.debug")) {
            msg(sender, plugin.messages().get("general.no-permission"));
            return;
        }
        String topic = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";

        if (topic.equals("all") || topic.equals("economy")) {
            msg(sender, "<gray>Economy provider: <white>" + plugin.economy().providerName()
                    + "</white> <dark_gray>(ready=" + plugin.economy().isReady() + ")");
        }
        if (topic.equals("all") || topic.equals("packetevents")) {
            msg(sender, "<gray>PacketEvents hooked: <white>" + plugin.packetEventsHooked()
                    + "</white>, creative service: <white>" + (plugin.creative() != null) + "</white>");
        }
        if (topic.equals("all") || topic.equals("floodgate")) {
            msg(sender, "<gray>Floodgate available: <white>" + plugin.bedrock().isAvailable() + "</white>");
            if (sender instanceof Player p) {
                msg(sender, "<gray>You are detected as: <white>"
                        + (plugin.bedrock().isBedrock(p.getUniqueId()) ? "Bedrock" : "Java") + "</white>");
            }
        }
        if (topic.equals("all") || topic.equals("quickshop")) {
            msg(sender, "<gray>Player shop index: <white>" + plugin.playerShops().indexSize()
                    + "</white> listings, available=" + plugin.playerShops().isAvailable());
        }
        if (topic.equals("all") || topic.equals("catalog")) {
            msg(sender, "<gray>Catalog entries: <white>" + plugin.catalog().all().size()
                    + "</white> across " + plugin.catalog().categories().size() + " categories");
        }
        if (topic.equals("all") || topic.equals("storage")) {
            msg(sender, "<gray>Storage ready: <white>" + plugin.storage().isReady() + "</white>");
        }
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "<gray>─────────────────────────────");
        msg(sender, "<gold>UNIVERSAL MARKET");
        msg(sender, "<white>/um <gray>- open the market");
        msg(sender, "<white>/um close <gray>- leave the market");
        msg(sender, "<white>/um terminal <gray>- repair your Market Terminal");
        msg(sender, "<white>/um price <item> <gray>- look up prices");
        msg(sender, "<white>/um balance <gray>- show your balance");
        msg(sender, "<white>/um status <gray>- today's deals and demand");
        if (sender.hasPermission("universalmarket.admin")
                || sender.hasPermission("universalmarket.reload")) {
            msg(sender, "");
            msg(sender, "<dark_gray>Admin:");
            msg(sender, "<white>/um reload <gray>- reload config and catalog");
            msg(sender, "<white>/um debug [topic] <gray>- integration diagnostics");
            msg(sender, "<white>/um resetdaily <gray>- roll a new market cycle");
            msg(sender, "<white>/um resetlimits <player>");
            msg(sender, "<white>/um terminal <player>");
        }
        msg(sender, "<gray>─────────────────────────────");
    }

    // ==================================================================
    // Tab completion
    // ==================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of(
                    "help", "terminal", "close", "price", "balance", "status"));
            if (sender.hasPermission("universalmarket.reload")) options.add("reload");
            if (sender.hasPermission("universalmarket.debug")) options.add("debug");
            if (sender.hasPermission("universalmarket.market.reset")) {
                options.add("resetdaily");
                options.add("resetlimits");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.startsWith(prefix)) out.add(option);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            for (String topic : List.of("economy", "packetevents", "floodgate",
                    "quickshop", "catalog", "storage")) {
                if (topic.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(topic);
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("terminal")
                || args[0].equalsIgnoreCase("resetlimits"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
