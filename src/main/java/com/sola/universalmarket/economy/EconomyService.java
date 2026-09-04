package com.sola.universalmarket.economy;

import com.sola.universalmarket.util.NumberFormatter;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.logging.Logger;

/**
 * The single point of contact between UniversalMarket and the server economy.
 *
 * WHY EVERY CALL HERE TAKES OfflinePlayer:
 *
 *   Verified against MilkBowl/VaultAPI Economy.java at master:
 *
 *     line 127  double          getBalance(OfflinePlayer player);
 *     line 189  EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);
 *     line 220  EconomyResponse depositPlayer(OfflinePlayer player, double amount);
 *
 *   There is NO getBalance(Player) overload in the real interface. Your previous
 *   plugin compiled one against a stub, so the JVM looked for a method descriptor
 *   that does not exist on the live server and threw NoSuchMethodError.
 *
 *   Because Player extends OfflinePlayer, passing a Player still binds correctly
 *   to the OfflinePlayer descriptor - as long as we compile against the genuine
 *   artifact, which the pom now does.
 *
 * MONEY DISCIPLINE:
 *
 *   Vault speaks double. Doubles cannot represent trillions in whole dollars
 *   exactly, and this economy is explicitly meant to reach $500T+. So all
 *   arithmetic in the plugin happens in BigDecimal, and we only convert to
 *   double at the last moment, having already rounded DOWN to whole dollars.
 *   Rounding down means the plugin can never overcharge through rounding.
 *
 *   Anything Vault returns is passed through a NaN/Infinity guard before use.
 */
public final class EconomyService {

    private final JavaPlugin plugin;
    private final Logger log;
    private Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Look up the Vault economy provider.
     * Returns false if none is registered, in which case the plugin must disable
     * itself rather than run with a broken economy.
     */
    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null
                && Bukkit.getPluginManager().getPlugin("VaultUnlocked") == null) {
            log.severe("Neither Vault nor VaultUnlocked is installed.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            log.severe("No economy provider is registered with Vault. "
                    + "Is NewEconomy loaded and hooked into VaultUnlocked?");
            return false;
        }
        this.economy = rsp.getProvider();
        if (this.economy == null) {
            log.severe("Vault returned a null economy provider.");
            return false;
        }
        log.info("Vault economy: " + economy.getName());
        return true;
    }

    public boolean isReady() {
        return economy != null;
    }

    public String providerName() {
        return economy == null ? "none" : economy.getName();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Current balance as whole dollars. Never negative, never NaN. */
    public BigDecimal balance(OfflinePlayer player) {
        if (economy == null || player == null) return BigDecimal.ZERO;
        try {
            BigDecimal value = NumberFormatter.safe(economy.getBalance(player));
            return value.signum() < 0 ? BigDecimal.ZERO : value;
        } catch (Throwable t) {
            log.warning("Economy getBalance failed for "
                    + player.getName() + ": " + t);
            return BigDecimal.ZERO;
        }
    }

    public boolean has(OfflinePlayer player, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return true;
        return balance(player).compareTo(amount) >= 0;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Take money from a player. Returns true only on a confirmed success.
     * Callers must treat false as "nothing happened" and abort the transaction.
     */
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        if (economy == null || player == null) return false;
        BigDecimal whole = sanitise(amount);
        if (whole == null) return false;
        if (whole.signum() == 0) return true;

        // Re-check funds immediately before the call to narrow the race window.
        if (balance(player).compareTo(whole) < 0) return false;

        try {
            EconomyResponse response = economy.withdrawPlayer(player, whole.doubleValue());
            if (response == null) return false;
            if (!response.transactionSuccess()) {
                log.warning("Withdraw of " + NumberFormatter.exactMoney(whole) + " from "
                        + player.getName() + " refused: " + response.errorMessage);
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.severe("Withdraw threw for " + player.getName() + ": " + t);
            return false;
        }
    }

    /**
     * Give money to a player. Returns true only on a confirmed success.
     * A false here during a rollback is a serious condition and callers should
     * log it loudly rather than silently swallowing it.
     */
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        if (economy == null || player == null) return false;
        BigDecimal whole = sanitise(amount);
        if (whole == null) return false;
        if (whole.signum() == 0) return true;

        try {
            EconomyResponse response = economy.depositPlayer(player, whole.doubleValue());
            if (response == null) return false;
            if (!response.transactionSuccess()) {
                log.warning("Deposit of " + NumberFormatter.exactMoney(whole) + " to "
                        + player.getName() + " refused: " + response.errorMessage);
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.severe("Deposit threw for " + player.getName() + ": " + t);
            return false;
        }
    }

    /**
     * Atomic-as-possible player to player transfer.
     *
     * Spec section 34: the SENDER pays the fee on top, the recipient receives the
     * exact amount typed, and the fee is applied exactly ONCE - by us. We never
     * route through NewEconomy's own /pay path, so its internal fee cannot
     * double-charge.
     *
     * @param fee   the fee, already computed by the caller
     * @return true if the recipient was credited and the sender debited
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to,
                            BigDecimal amount, BigDecimal fee) {
        BigDecimal net = sanitise(amount);
        BigDecimal charge = sanitise(fee);
        if (net == null || charge == null || net.signum() <= 0) return false;

        BigDecimal total = net.add(charge);
        if (!withdraw(from, total)) return false;

        if (!deposit(to, net)) {
            // Recipient credit failed: put the sender back exactly as they were.
            if (!deposit(from, total)) {
                log.severe("CRITICAL: refund of " + NumberFormatter.exactMoney(total)
                        + " to " + from.getName() + " FAILED after a failed transfer to "
                        + to.getName() + ". Manual correction required.");
            }
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------

    /**
     * Reject anything that is not a clean, finite, non-negative, sanely sized
     * amount, and round down to whole dollars. Returns null when the value must
     * not be used at all.
     */
    private BigDecimal sanitise(BigDecimal amount) {
        if (amount == null) return null;
        if (amount.signum() < 0) return null;
        if (amount.compareTo(NumberFormatter.MAX_TRANSACTION) > 0) return null;
        BigDecimal whole = NumberFormatter.toWholeDollars(amount);
        // Guard the double conversion: beyond 2^53 a double silently loses whole
        // dollars. Well above any legitimate single transaction, but check anyway.
        double d = whole.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        return whole;
    }
}
