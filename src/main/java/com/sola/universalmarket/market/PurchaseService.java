package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Buying from the Universal Market.
 *
 * The full validation chain from spec section 13 lives here, in one place, so
 * every entry point (catalogue menu, commands, Bedrock forms) enforces exactly
 * the same rules. There is no second implementation to drift out of sync.
 *
 * Order is deliberate: money leaves first, then the item is created. If delivery
 * fails the money is refunded. Doing it the other way round would let a failed
 * withdrawal leave the player holding a free item.
 */
public final class PurchaseService {

    private final UniversalMarketPlugin plugin;

    public PurchaseService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    /** Outcome of an attempted purchase, with a ready-to-send message key. */
    public record Result(boolean success, String messageKey, BigDecimal amount, int quantity) {
        public static Result fail(String key) {
            return new Result(false, key, BigDecimal.ZERO, 0);
        }
    }

    public Result buy(Player player, MarketItem item, int requested) {
        if (item == null || item.blacklisted()) return Result.fail("buy.not-for-sale");
        if (requested <= 0) return Result.fail("buy.not-for-sale");

        if (!player.hasPermission("universalmarket.buy")) {
            return Result.fail("general.no-permission");
        }

        Material material = item.material();
        if (material == null || !material.isItem()) return Result.fail("buy.not-for-sale");

        // Never grant more than a stack in one action.
        int quantity = Math.min(requested, material.getMaxStackSize());

        BigDecimal unit = plugin.pricing().currentBuyPrice(item);
        BigDecimal total = NumberFormatter.toWholeDollars(
                unit.multiply(BigDecimal.valueOf(quantity)));
        if (total.signum() <= 0) return Result.fail("buy.not-for-sale");

        // Rare goods allowance, per player, persisted across restarts.
        if (item.rare()) {
            var allowance = plugin.rareGoods().checkAllowance(player.getUniqueId(), item, quantity);
            if (!allowance.allowed()) {
                return new Result(false, "buy.rare-limit",
                        BigDecimal.valueOf(allowance.resetInMillis()), 0);
            }
        }

        if (plugin.economy().balance(player).compareTo(total) < 0) {
            return new Result(false, "buy.insufficient", total, quantity);
        }

        ItemStack stack = plugin.catalog().createApprovedStack(item, quantity);
        if (stack == null) return Result.fail("buy.not-for-sale");

        // Spec 72: never drop an expensive purchase on the floor.
        if (!hasSpaceFor(player, stack)) return Result.fail("buy.inventory-full");

        if (!plugin.economy().withdraw(player, total)) {
            return Result.fail("buy.economy-error");
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            for (ItemStack rem : leftover.values()) player.getInventory().removeItem(rem);
            if (!plugin.economy().deposit(player, total)) {
                plugin.getLogger().severe("ROLLBACK FAILED refunding "
                        + NumberFormatter.exactMoney(total) + " to " + player.getName()
                        + " for " + item.id() + " - manual correction required.");
            }
            return Result.fail("buy.inventory-full");
        }

        if (item.rare()) plugin.rareGoods().recordPurchase(player.getUniqueId(), item, quantity);
        plugin.transactions().recordPurchase(player.getUniqueId(), item.id(), quantity, total);
        plugin.pricing().onPlayerBought(item, quantity);

        return new Result(true, "buy.success", total, quantity);
    }

    /** Counts partially filled compatible stacks, not just empty slots. */
    private boolean hasSpaceFor(Player player, ItemStack stack) {
        int needed = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) needed -= max;
            else if (slot.isSimilar(stack)) needed -= Math.max(0, max - slot.getAmount());
            if (needed <= 0) return true;
        }
        return needed <= 0;
    }
}
