package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The /sell flow.
 *
 *   /sell            -> hands you a marked, glowing chest
 *   place it         -> the block is registered to you
 *   fill and close   -> contents are validated and priced
 *   pick a price     -> low / mid / high suggestion, or type a custom one
 *   confirm          -> chest and contents vanish, listing goes live
 *   cancel           -> chest and contents come straight back
 *
 * ONE ITEM TYPE PER CHEST, as you asked. Mixing is rejected on close rather than
 * on click, so you can rearrange freely while filling and only get told off when
 * you actually finish.
 *
 * The goods are only removed at the confirm step. Up to that point they are
 * physically in the chest, so a crash, a disconnect or a plugin reload loses
 * nothing - the chest is still standing there with everything in it.
 */
public final class SellFlowService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final NamespacedKey chestKey;

    /** Placed sell chests, keyed by block location. */
    private final Map<Location, UUID> placedChests = new HashMap<>();

    /** Players currently choosing a price. */
    private final Map<UUID, PendingSale> pending = new HashMap<>();

    /** Players whose next chat message is a custom price. */
    private final Map<UUID, Long> awaitingPrice = new HashMap<>();

    public SellFlowService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.chestKey = new NamespacedKey(plugin, "sell_chest");
    }

    /** A sale waiting on a price decision. */
    public static final class PendingSale {
        public final Location chestLocation;
        public final MarketItem item;
        public final int quantity;
        public BigDecimal chosenPrice;

        PendingSale(Location chestLocation, MarketItem item, int quantity) {
            this.chestLocation = chestLocation;
            this.item = item;
            this.quantity = quantity;
        }
    }

    // ==================================================================
    // The chest item
    // ==================================================================

    public ItemStack createSellChest() {
        ItemStack stack = new ItemStack(Material.CHEST, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(mm.deserialize("<gold>✦ MARKET STALL ✦")
                .decoration(TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : List.of(
                "<gray>Place this, then fill it with",
                "<gray>ONE type of item to sell.",
                "",
                "<gray>Close the chest to set a price.",
                "",
                "<dark_purple>Single use")) {
            lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(chestKey, PersistentDataType.BYTE, (byte) 1);
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) { }
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isSellChest(ItemStack stack) {
        if (stack == null || stack.getType() != Material.CHEST) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        Byte flag = meta.getPersistentDataContainer().get(chestKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    // ==================================================================
    // Placement
    // ==================================================================

    public void registerPlaced(Block block, Player owner) {
        placedChests.put(block.getLocation(), owner.getUniqueId());
        owner.sendMessage(mm.deserialize(plugin.messages().get("sell.chest-placed")));
    }

    public boolean isSellChestBlock(Location location) {
        return placedChests.containsKey(location);
    }

    public UUID ownerOf(Location location) {
        return placedChests.get(location);
    }

    public void forgetChest(Location location) {
        placedChests.remove(location);
    }

    // ==================================================================
    // Validation on close
    // ==================================================================

    /**
     * Read a closed sell chest and start the pricing flow.
     * Returns quietly when the chest is empty - closing an empty stall should
     * not nag the player.
     */
    public void handleChestClosed(Player player, Location location) {
        UUID owner = placedChests.get(location);
        if (owner == null || !owner.equals(player.getUniqueId())) return;

        Block block = location.getBlock();
        if (!(block.getState() instanceof Chest chest)) return;

        MarketItem item = null;
        int total = 0;
        boolean mixed = false;

        for (ItemStack stack : chest.getBlockInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (plugin.terminal().isTerminal(stack) || isSellChest(stack)) {
                mixed = true;
                break;
            }
            // Reuse the sell validator: no renamed, enchanted or NBT-carrying goods.
            MarketItem resolved = plugin.sell().resolveSellable(stack);
            if (resolved == null) {
                player.sendMessage(mm.deserialize(plugin.messages().get("sell.rejected-custom")));
                plugin.sounds().error(player);
                return;
            }
            if (item == null) item = resolved;
            else if (!item.id().equals(resolved.id())) { mixed = true; break; }
            total += stack.getAmount();
        }

        if (mixed) {
            player.sendMessage(mm.deserialize(plugin.messages().get("sell.mixed-items")));
            plugin.sounds().error(player);
            return;
        }
        if (item == null || total <= 0) return;   // empty chest, say nothing

        pending.put(player.getUniqueId(), new PendingSale(location, item, total));
        plugin.menus().openPriceChooser(player);
    }

    // ==================================================================
    // Pricing
    // ==================================================================

    public PendingSale pendingFor(UUID player) {
        return pending.get(player);
    }

    public void clearPending(UUID player) {
        pending.remove(player);
        awaitingPrice.remove(player);
    }

    /** Begin waiting for a typed price. */
    public void awaitCustomPrice(Player player) {
        awaitingPrice.put(player.getUniqueId(), System.currentTimeMillis());
        player.closeInventory();
        player.sendMessage(mm.deserialize(plugin.messages().get("sell.type-price")));
    }

    public boolean isAwaitingPrice(UUID player) {
        return awaitingPrice.containsKey(player);
    }

    /**
     * Handle a typed price. Returns true if the message was consumed.
     * The chat message is never broadcast - you asked for the typing itself to
     * be invisible to everyone, including the sender.
     */
    public boolean handleTypedPrice(Player player, String raw) {
        if (!awaitingPrice.containsKey(player.getUniqueId())) return false;

        if (raw.equalsIgnoreCase("cancel")) {
            awaitingPrice.remove(player.getUniqueId());
            plugin.menus().openPriceChooser(player);
            return true;
        }

        BigDecimal price = NumberFormatter.parse(raw);
        PendingSale sale = pending.get(player.getUniqueId());
        if (sale == null) {
            awaitingPrice.remove(player.getUniqueId());
            return true;
        }
        if (price == null || price.signum() <= 0) {
            player.sendMessage(mm.deserialize(plugin.messages().get("sell.bad-price")));
            plugin.sounds().error(player);
            return true;
        }

        awaitingPrice.remove(player.getUniqueId());
        sale.chosenPrice = price;
        plugin.menus().openSellConfirm(player);
        return true;
    }

    public void choosePrice(Player player, BigDecimal price) {
        PendingSale sale = pending.get(player.getUniqueId());
        if (sale == null) return;
        sale.chosenPrice = price;
        plugin.menus().openSellConfirm(player);
    }

    // ==================================================================
    // Completion
    // ==================================================================

    /** Confirm: empty the chest, break it, publish the listing. */
    public boolean confirm(Player player) {
        PendingSale sale = pending.get(player.getUniqueId());
        if (sale == null || sale.chosenPrice == null) return false;

        Block block = sale.chestLocation.getBlock();
        if (!(block.getState() instanceof Chest chest)) {
            clearPending(player.getUniqueId());
            return false;
        }

        // Re-count at the moment of commit rather than trusting the earlier
        // reading, in case anything changed while the menus were open.
        int total = 0;
        for (ItemStack stack : chest.getBlockInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            MarketItem resolved = plugin.sell().resolveSellable(stack);
            if (resolved == null || !resolved.id().equals(sale.item.id())) {
                player.sendMessage(mm.deserialize(plugin.messages().get("sell.mixed-items")));
                return false;
            }
            total += stack.getAmount();
        }
        if (total <= 0) {
            clearPending(player.getUniqueId());
            return false;
        }

        chest.getBlockInventory().clear();
        block.setType(Material.AIR);
        forgetChest(sale.chestLocation);

        plugin.listings().create(player, sale.item, total, sale.chosenPrice);

        player.sendMessage(mm.deserialize(plugin.messages().get("sell.listed")
                .replace("%qty%", NumberFormatter.count(total))
                .replace("%item%", sale.item.displayName())
                .replace("%price%", NumberFormatter.money(sale.chosenPrice))
                .replace("%total%", NumberFormatter.money(
                        sale.chosenPrice.multiply(BigDecimal.valueOf(total))))));
        plugin.sounds().sell(player);

        clearPending(player.getUniqueId());
        return true;
    }

    /** Cancel: give everything back and remove the chest. */
    public void cancel(Player player) {
        PendingSale sale = pending.get(player.getUniqueId());
        clearPending(player.getUniqueId());
        if (sale == null) return;

        Block block = sale.chestLocation.getBlock();
        if (block.getState() instanceof Chest chest) {
            for (ItemStack stack : chest.getBlockInventory().getContents()) {
                if (stack == null || stack.getType() == Material.AIR) continue;
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            chest.getBlockInventory().clear();
            block.setType(Material.AIR);
        }
        forgetChest(sale.chestLocation);

        // The stall itself is returned too, so cancelling costs nothing.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(createSellChest());
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        player.sendMessage(mm.deserialize(plugin.messages().get("sell.cancelled")));
    }

    /** Break a placed stall that was never used, returning its contents. */
    public void breakUnused(Player player, Location location) {
        Block block = location.getBlock();
        if (block.getState() instanceof Chest chest) {
            for (ItemStack stack : chest.getBlockInventory().getContents()) {
                if (stack == null) continue;
                player.getWorld().dropItemNaturally(location, stack);
            }
            chest.getBlockInventory().clear();
        }
        forgetChest(location);
        player.getWorld().dropItemNaturally(location, createSellChest());
    }
}
