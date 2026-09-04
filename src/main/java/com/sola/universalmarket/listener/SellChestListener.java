package com.sola.universalmarket.listener;

import com.sola.universalmarket.UniversalMarketPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

/**
 * Drives the /sell stall: placement, filling, closing and the typed price.
 *
 * The goods stay physically inside the chest until the moment the listing is
 * confirmed. A crash, a disconnect or a plugin reload part-way through pricing
 * therefore loses nothing - the stall is still standing there, full. That
 * matters more than usual here because you asked for listings to be final:
 * with no refunds and no cancellation, the commit point has to be as late and
 * as deliberate as possible.
 */
public final class SellChestListener implements Listener {

    private final UniversalMarketPlugin plugin;

    public SellChestListener(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.sellFlow().isSellChest(event.getItemInHand())) return;
        plugin.sellFlow().registerPlaced(event.getBlockPlaced(), event.getPlayer());
        plugin.sounds().click(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // Closing the stall starts pricing
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof Chest chest)) return;

        Location location = chest.getLocation();
        if (!plugin.sellFlow().isSellChestBlock(location)) return;

        // One tick later: the close event fires before the contents settle.
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.sellFlow().handleChestClosed(player, location);
        }, 1L);
    }

    // ------------------------------------------------------------------
    // Breaking an unused stall returns everything
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        if (!plugin.sellFlow().isSellChestBlock(location)) return;

        var owner = plugin.sellFlow().ownerOf(location);
        if (owner != null && !owner.equals(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("universalmarket.admin")) {
            // Someone else's stall: leave it alone.
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<red>\u2715 That stall belongs to someone else."));
            return;
        }

        event.setCancelled(true);
        event.getBlock().setType(org.bukkit.Material.AIR);
        plugin.sellFlow().breakUnused(event.getPlayer(), location);
        plugin.sellFlow().clearPending(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Typed custom price
    // ------------------------------------------------------------------

    /**
     * Consumes the message entirely when a price is expected, so the number
     * never reaches chat. You asked for the typing to be invisible to everyone,
     * including the person typing it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.sellFlow().isAwaitingPrice(player.getUniqueId())) return;

        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Chat is async; everything downstream touches inventories and menus.
        org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> plugin.sellFlow().handleTypedPrice(player, raw));
    }
}
