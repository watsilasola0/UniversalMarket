package com.sola.universalmarket.command;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /umreset - wipe the economy and start over.
 *
 * This is the single most destructive thing the plugin can do, so it is guarded
 * three ways:
 *
 *   1. Operator permission only.
 *   2. The literal word "confirm" must be typed. No bare /umreset ever wipes
 *      anything, because a mistyped command should not be able to delete a
 *      server's entire economic history.
 *   3. A 30 second window between reading the warning and confirming, so the
 *      confirmation is a separate deliberate act rather than tab-completion.
 *
 * What it does NOT touch: market.yml prices, config.yml, or anyone's items and
 * blocks. This resets progress, not the world - your tuning survives.
 */
public final class ResetCommand implements CommandExecutor, TabCompleter {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    /** Who has read the warning, and when. */
    private final Map<UUID, Long> armed = new HashMap<>();
    private static final long CONFIRM_WINDOW_MILLIS = 30_000L;
    private static final UUID CONSOLE = new UUID(0L, 0L);

    public ResetCommand(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("universalmarket.admin")) {
            sender.sendMessage(mm.deserialize(plugin.messages().get("general.no-permission")));
            return true;
        }

        String scope = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        UUID key = sender instanceof Player p ? p.getUniqueId() : CONSOLE;

        if (!scope.equals("confirm")) {
            armed.put(key, System.currentTimeMillis());
            warn(sender);
            return true;
        }

        Long armedAt = armed.get(key);
        if (armedAt == null || System.currentTimeMillis() - armedAt > CONFIRM_WINDOW_MILLIS) {
            sender.sendMessage(mm.deserialize(
                    "<red>✕ Confirmation expired.</red> <gray>Run <white>/umreset</white> again first."));
            armed.remove(key);
            return true;
        }
        armed.remove(key);
        performReset(sender);
        return true;
    }

    private void warn(CommandSender sender) {
        sender.sendMessage(mm.deserialize("""
                <dark_gray>─────────────────────────────
                <red><b>⚠ FULL ECONOMY RESET</b>
                <gray>This will permanently erase:
                <gray>  • every player's <white>balance</white>
                <gray>  • all lifetime statistics and history
                <gray>  • every player listing (goods are <red>not</red> returned)
                <gray>  • all quest progress and daily counters
                <gray>  • sell limits, rare limits and dynamic prices
                <gray>  • the leaderboard and offline sale notices
                <gray>
                <gray>It will <green>not</green> touch market.yml prices, config.yml,
                <gray>or anyone's items, blocks or builds.
                <gray>
                <red>There is no undo. Back up your database first.
                <gray>
                <yellow>Type <white>/umreset confirm</white> within 30 seconds.
                <dark_gray>─────────────────────────────"""));
    }

    // ==================================================================
    // The reset itself
    // ==================================================================

    private void performReset(CommandSender sender) {
        plugin.getLogger().warning("=== FULL ECONOMY RESET triggered by "
                + sender.getName() + " ===");
        sender.sendMessage(mm.deserialize("<gray>Resetting..."));

        int wallets = resetBalances();
        clearTables();
        clearMemory();

        plugin.getLogger().warning("=== RESET COMPLETE: " + wallets + " balances zeroed ===");

        sender.sendMessage(mm.deserialize("""
                <dark_gray>─────────────────────────────
                <green><b>✓ ECONOMY RESET COMPLETE</b>
                <gray>Balances zeroed: <white>%wallets%</white>
                <gray>Listings, quests, stats and limits cleared.
                <gray>Dynamic prices returned to their base values.
                <gray>
                <gray>Market prices in <white>market.yml</white> were left alone.
                <dark_gray>─────────────────────────────"""
                .replace("%wallets%", String.valueOf(wallets))));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(sender)) {
                online.sendMessage(mm.deserialize(
                        "<yellow>The server economy has been reset. Everyone starts from $0."));
            }
            plugin.sounds().cycle(online);
        }
    }

    /**
     * Zero every known balance.
     *
     * Withdraws whatever each player holds rather than calling a "set" method,
     * because the Vault interface has no setBalance - withdrawing the exact
     * current balance is the portable way to reach zero.
     */
    private int resetBalances() {
        int count = 0;
        List<OfflinePlayer> everyone = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) everyone.add(online);
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && !everyone.contains(offline)) everyone.add(offline);
        }

        for (OfflinePlayer target : everyone) {
            try {
                BigDecimal balance = plugin.economy().balance(target);
                if (balance.signum() <= 0) continue;
                if (plugin.economy().withdraw(target, balance)) count++;
                else plugin.getLogger().warning("Could not zero the balance of "
                        + target.getName() + " (" + NumberFormatter.exactMoney(balance) + ")");
            } catch (Throwable t) {
                plugin.getLogger().warning("Balance reset failed for "
                        + target.getName() + ": " + t);
            }
        }
        return count;
    }

    private void clearTables() {
        String[] tables = {
                "transactions", "lifetime_stats", "sell_limits", "rare_purchases",
                "price_state", "market_state", "quest_progress", "listings",
                "shop_notifications", "favourites"
        };
        for (String table : tables) {
            plugin.storage().execute("DELETE FROM " + table);
        }
    }

    /**
     * Drop the in-memory mirrors too.
     *
     * Clearing only the database would leave every cache holding the old
     * numbers until the next restart, so the reset would look half-applied.
     */
    private void clearMemory() {
        try {
            plugin.listings().load();              // reloads from the now-empty table
            plugin.pricing().rollCycle();          // fresh deals and demand at base prices
            plugin.rareGoods().resetAll();
            plugin.sell().loadState();             // reloads empty sell limits
            plugin.quests().resetDaily();
            plugin.leaderboard().refresh();

            for (Player online : Bukkit.getOnlinePlayers()) {
                UUID id = online.getUniqueId();
                plugin.quests().cancel(online);
                plugin.quests().forget(id);
                plugin.gambling().forget(id);
                plugin.menus().gambleMenu().forget(id);
                plugin.announcements().forget(id);
                plugin.announcements().primeTier(online);
                plugin.sellFlow().clearPending(id);
                online.closeInventory();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Some in-memory state could not be cleared: " + t
                    + " - a restart will finish the job.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // Deliberately NOT suggesting "confirm". Tab-completing your way into
        // wiping the economy is exactly the accident this guards against.
        return List.of();
    }
}
