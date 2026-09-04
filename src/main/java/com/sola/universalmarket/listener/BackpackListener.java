package com.sola.universalmarket.listener;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.backpack.BackpackService;
import com.sola.universalmarket.ui.Gui;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import java.util.UUID;

/**
 * Placing, opening and breaking backpacks.
 *
 * The ownership rules are enforced here rather than in the menu, because a menu
 * check only protects the menu - a block anyone could break would let someone
 * delete your storage without ever opening it.
 */
public final class BackpackListener implements Listener {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public BackpackListener(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        var stack = event.getItemInHand();
        if (!plugin.backpacks().isBackpackItem(stack)) return;

        Player player = event.getPlayer();
        UUID owner = plugin.backpacks().ownerOfItem(stack);

        // Someone else's backpack item should never have left their inventory,
        // but if it somehow did, it is not placeable by anyone else.
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.not-yours")));
            plugin.sounds().error(player);
            return;
        }

        BackpackService.Backpack backpack = plugin.backpacks().of(owner);
        if (backpack == null) {
            event.setCancelled(true);
            plugin.sounds().error(player);
            return;
        }
        plugin.backpacks().registerPlaced(event.getBlockPlaced(), backpack);
        player.sendMessage(mm.deserialize(plugin.messages().get("backpack.placed")));
        plugin.sounds().click(player);
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Location location = event.getClickedBlock().getLocation();
        if (!plugin.backpacks().isBackpackBlock(location)) return;

        // Always cancel: a backpack block must never open its vanilla shulker
        // inventory, which would be a completely separate, unmanaged container.
        event.setCancelled(true);

        BackpackService.Backpack backpack = plugin.backpacks().atBlock(location);
        if (backpack == null) return;

        plugin.menus().backpackMenu().openStorage(event.getPlayer(), backpack, 0);
    }

    // ------------------------------------------------------------------
    // Breaking
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        if (!plugin.backpacks().isBackpackBlock(location)) return;

        BackpackService.Backpack backpack = plugin.backpacks().atBlock(location);
        Player player = event.getPlayer();

        if (backpack == null) return;

        // Only the owner may break it. Anyone else would be deleting another
        // player's storage door, and there is no good reason to allow that.
        if (!player.getUniqueId().equals(backpack.owner)
                && !player.hasPermission("universalmarket.admin")) {
            event.setCancelled(true);
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.not-yours-break")
                    .replace("%owner%", backpack.ownerName)));
            plugin.sounds().error(player);
            return;
        }

        // The block vanishes with no drop - the item is recalled from the
        // terminal instead. Dropping it here would create a second copy if the
        // player also recalled it before picking the drop up.
        event.setDropItems(false);
        plugin.backpacks().onBroken(backpack);

        player.sendMessage(mm.deserialize(plugin.messages().get("backpack.broken")));
        plugin.sounds().click(player);
    }

    // ------------------------------------------------------------------
    // Holding the item
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClickItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;
        var stack = event.getItem();
        if (!plugin.backpacks().isBackpackItem(stack)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(
                Gui.MM.deserialize(plugin.messages().get("backpack.place-to-open")));
    }
}
