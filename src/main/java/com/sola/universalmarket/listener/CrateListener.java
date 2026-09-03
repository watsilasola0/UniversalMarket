package com.sola.universalmarket.listener;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.crate.CrateTier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Opens a crate the moment it is placed.
 *
 * There is no second "right click to open" step on purpose: a placed crate that
 * has not been opened yet would be a block another player could break and steal,
 * and the crate item is already worth real money at that point.
 */
public final class CrateListener implements Listener {

    private final UniversalMarketPlugin plugin;

    public CrateListener(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.crates().isCrate(event.getItemInHand())) return;

        CrateTier tier = plugin.crates().tierOf(event.getItemInHand());
        if (tier == null) return;

        plugin.crates().openCrate(event.getPlayer(), event.getBlockPlaced(), tier);
    }
}
