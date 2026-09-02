package com.sola.universalmarket.listener;

import com.sola.universalmarket.UniversalMarketPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Two jobs, both of them defensive:
 *
 *   1. Keep the Market Terminal soulbound - it cannot be dropped, stored,
 *      placed, crafted with, or hoppered away.
 *   2. Keep a Creative-Market session harmless - no flight, no block breaking,
 *      no combat, and no touching your own inventory while the client believes
 *      it is in creative.
 *
 * Note the deliberate absence of any repeating task here. Terminal existence is
 * only evaluated on join and respawn, which is half of why the old duplication
 * bug cannot recur.
 */
public final class ProtectionListener implements Listener {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ProtectionListener(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inMarket(Player p) {
        return plugin.creative() != null && plugin.creative().inMarket(p);
    }

    // ==================================================================
    // Join / respawn / leave
    // ==================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Delayed a tick: the inventory and scoreboard are not fully settled at
        // the instant the join event fires.
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            if (plugin.leaderboard() != null) plugin.leaderboard().onJoin(event.getPlayer());
            plugin.terminal().upgradeOutdated(event.getPlayer());
        }, 20L);
        Player player = event.getPlayer();
        plugin.transactions().touchName(player.getUniqueId(), player.getName());

        if (!plugin.getConfig().getBoolean("terminal.give-on-first-join", true)) return;
        // One tick later, so the inventory is fully loaded before we count.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.terminal().ensureTerminal(player);
        }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("terminal.restore-on-respawn", true)) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.terminal().ensureTerminal(player);
        }, 10L);
    }

    /** The terminal survives death: strip it from drops rather than losing it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(stack -> plugin.terminal().isTerminal(stack));
        Player player = event.getEntity();
        if (inMarket(player)) plugin.creative().exitMarket(player, "death");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.leaderboard() != null) {
            plugin.leaderboard().forget(event.getPlayer().getUniqueId());
        }
        if (plugin.creative() != null) {
            plugin.creative().exitMarket(event.getPlayer(), "quit");
            plugin.creative().forceForget(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        if (plugin.creative() != null) {
            plugin.creative().exitMarket(event.getPlayer(), "kick");
            plugin.creative().forceForget(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (inMarket(event.getPlayer())) {
            plugin.creative().exitMarket(event.getPlayer(), "world change");
        }
    }

    // ==================================================================
    // Terminal protection
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.terminal().isTerminal(event.getItemDrop().getItemStack())) {
            // Let the drop proceed, then remove the entity: the terminal
            // dissolves rather than being blocked. /um brings it back.
            event.getItemDrop().remove();
            event.getPlayer().sendMessage(
                    mm.deserialize(plugin.messages().get("terminal.dissolved")));
            return;
        }
        if (inMarket(event.getPlayer())
                && plugin.getConfig().getBoolean("creative-market.cancel-item-drop", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.terminal().isTerminal(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(mm.deserialize(plugin.messages().get("terminal.cannot-place")));
            return;
        }
        if (inMarket(event.getPlayer())
                && plugin.getConfig().getBoolean("creative-market.cancel-block-place", true)) {
            event.setCancelled(true);
        }
    }

    /** Blocks hoppers, droppers and any automated movement of the terminal. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (plugin.terminal().isTerminal(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        for (ItemStack stack : event.getInventory().getMatrix()) {
            if (plugin.terminal().isTerminal(stack)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * The main container guard. Cancels any attempt to move the terminal into an
     * inventory that is not the player's own, which covers chests, barrels,
     * shulkers, furnaces, anvils, grindstones, smithing tables and QuickShop's
     * own item-selection screens in one rule.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean terminalInvolved =
                plugin.terminal().isTerminal(event.getCurrentItem())
                || plugin.terminal().isTerminal(event.getCursor())
                || (event.getHotbarButton() >= 0
                    && plugin.terminal().isTerminal(
                            player.getInventory().getItem(event.getHotbarButton())));

        if (terminalInvolved) {
            InventoryType topType = event.getView().getTopInventory().getType();
            boolean ownInventoryOnly = topType == InventoryType.CRAFTING
                    || topType == InventoryType.PLAYER;

            // Moving it around your own inventory is fine and expected.
            // Trying to move it into ANY external container dissolves it -
            // cancel the click first so no copy can survive the transaction,
            // then delete every terminal the player holds.
            if (!ownInventoryOnly) {
                event.setCancelled(true);
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.terminal().consume(player));
                return;
            }
        }

        // While browsing the market, the player's real inventory is read-only.
        // This is what makes every creative slot packet unambiguously a purchase
        // intent rather than "I was moving my own stuff" (spec section 14).
        if (inMarket(player)
                && plugin.getConfig().getBoolean("creative-market.lock-player-inventory-during-session", true)) {
            event.setCancelled(true);
            player.updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.terminal().isTerminal(event.getOldCursor())) {
            Inventory top = event.getView().getTopInventory();
            for (int raw : event.getRawSlots()) {
                if (raw < top.getSize() && top.getType() != InventoryType.CRAFTING) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (inMarket(player)
                && plugin.getConfig().getBoolean("creative-market.lock-player-inventory-during-session", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (inMarket(event.getPlayer())) event.setCancelled(true);
    }

    // ==================================================================
    // Terminal use
    // ==================================================================

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        if (held == null || !plugin.terminal().isTerminal(held)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!event.getAction().isRightClick()) return;
        if (!player.hasPermission("universalmarket.use")) {
            player.sendMessage(mm.deserialize(plugin.messages().get("general.no-permission")));
            return;
        }
        // Right-click opens the chest menu. BUY ITEMS inside it is what starts
        // the creative browsing session.
        if (plugin.menus() != null) plugin.menus().openHome(player);
        else player.performCommand("um");
    }

    // ==================================================================
    // Creative session safety
    // ==================================================================

    /**
     * Flight is the single most dangerous side effect of lying to the client
     * about its gamemode, because the client grants itself flight locally and
     * the server would otherwise kick for it. Cancelling here plus the
     * abilities packet re-send in CreativeMarketService closes it from both ends.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!inMarket(player)) return;
        if (player.getGameMode() == GameMode.SURVIVAL) {
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inMarket(event.getPlayer())
                && plugin.getConfig().getBoolean("creative-market.cancel-block-break", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && inMarket(player)
                && plugin.getConfig().getBoolean("creative-market.cancel-entity-damage", true)) {
            event.setCancelled(true);
        }
    }

    /** Do not let picked-up items reshuffle an inventory we are treating as frozen. */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && inMarket(player)) {
            event.setCancelled(true);
        }
    }
}
