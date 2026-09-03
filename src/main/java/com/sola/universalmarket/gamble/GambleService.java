package com.sola.universalmarket.gamble;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared betting state and payout maths.
 *
 * ON THE HOUSE EDGE
 *
 *   Your section 53 explicitly ruled out coinflip and lotteries because money
 *   should not enter the economy for free. Gambling against the server can go
 *   either way, so the deciding factor is the edge: with the house ahead on
 *   average, gambling is a controlled money SINK, which actually helps the
 *   economy your buyback limits and sell tiers are trying to protect.
 *
 *   Every game here pays out at odds slightly worse than fair. Coinflip is an
 *   even-money bet won 48.5% of the time, not 50%. Mines multipliers are the
 *   true odds scaled by the same factor. Over a long session the server nets
 *   money, which is the opposite of a faucet.
 *
 *   config: gambling.house-edge. Setting it to 0 makes the games fair and
 *   turns gambling economy-neutral; setting it above 0.05 gets predatory fast.
 */
public final class GambleService {

    private final UniversalMarketPlugin plugin;

    /** Current bet each player has dialled in, shared across all games. */
    private final Map<UUID, BigDecimal> bets = new HashMap<>();

    public GambleService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Bet handling
    // ==================================================================

    public BigDecimal betOf(Player player) {
        return bets.computeIfAbsent(player.getUniqueId(),
                k -> BigDecimal.valueOf(minBet()));
    }

    public long minBet() {
        return plugin.getConfig().getLong("gambling.min-bet", 1_000L);
    }

    public BigDecimal maxBet() {
        return BigDecimal.valueOf(plugin.getConfig().getLong("gambling.max-bet", 100_000_000L));
    }

    /** Adjust the bet by a delta, clamped to the limits and the player's balance. */
    public void adjustBet(Player player, BigDecimal delta) {
        BigDecimal next = betOf(player).add(delta);
        setBet(player, next);
    }

    public void setBet(Player player, BigDecimal amount) {
        BigDecimal balance = plugin.economy().balance(player);
        BigDecimal clamped = amount;

        if (clamped.compareTo(BigDecimal.valueOf(minBet())) < 0) {
            clamped = BigDecimal.valueOf(minBet());
        }
        if (clamped.compareTo(maxBet()) > 0) clamped = maxBet();
        // Never let someone dial in a bet they cannot cover.
        if (clamped.compareTo(balance) > 0) clamped = balance.max(BigDecimal.ZERO);

        bets.put(player.getUniqueId(), clamped.setScale(0, RoundingMode.DOWN));
    }

    public void allIn(Player player) {
        setBet(player, plugin.economy().balance(player));
    }

    public boolean canAfford(Player player) {
        BigDecimal bet = betOf(player);
        return bet.signum() > 0 && plugin.economy().balance(player).compareTo(bet) >= 0;
    }

    // ==================================================================
    // Settling
    // ==================================================================

    public double houseEdge() {
        return plugin.getConfig().getDouble("gambling.house-edge", 0.03);
    }

    /** Take the stake. Returns false if it could not be taken. */
    public boolean takeStake(Player player, BigDecimal amount) {
        if (amount.signum() <= 0) return false;
        return plugin.economy().withdraw(player, amount);
    }

    /**
     * Pay a win. The multiplier is the TOTAL return including the stake, so a
     * 2.0x coinflip returns double what was staked.
     */
    public BigDecimal payWin(Player player, BigDecimal stake, double multiplier) {
        BigDecimal payout = stake.multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.DOWN);
        plugin.economy().deposit(player, payout);
        plugin.transactions().recordContractReward(player.getUniqueId(), "gamble:win", payout);
        plugin.announcements().checkPromotion(player);
        return payout;
    }

    public void recordLoss(Player player, BigDecimal stake) {
        plugin.transactions().recordPurchase(player.getUniqueId(), "gamble:loss", 1, stake);
    }

    /** Coinflip: an even-money bet won slightly less than half the time. */
    public boolean coinflipWins() {
        double chance = 0.5 * (1.0 - houseEdge());
        return Math.random() < chance;
    }

    /**
     * Fair multiplier for revealing {@code revealed} safe tiles on a
     * {@code total}-tile grid containing {@code mines} mines, with the house
     * edge applied.
     *
     * The fair value is the reciprocal of the probability of surviving that many
     * picks, which is what makes a 24-mine board pay enormously for one tile and
     * a 1-mine board pay almost nothing.
     */
    public double minesMultiplier(int total, int mines, int revealed) {
        if (revealed <= 0) return 1.0;
        double probability = 1.0;
        for (int i = 0; i < revealed; i++) {
            int safeLeft = total - mines - i;
            int tilesLeft = total - i;
            if (tilesLeft <= 0 || safeLeft <= 0) return 1.0;
            probability *= safeLeft / (double) tilesLeft;
        }
        double fair = 1.0 / probability;
        return Math.max(1.0, fair * (1.0 - houseEdge()));
    }

    public void forget(UUID player) {
        bets.remove(player);
    }

    public String formatBet(Player player) {
        return NumberFormatter.money(betOf(player));
    }
}
