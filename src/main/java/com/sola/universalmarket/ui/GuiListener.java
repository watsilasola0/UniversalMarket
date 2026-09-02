package com.sola.universalmarket.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Routes clicks to the Gui that owns the inventory.
 *
 * The ordering here is deliberate and load-bearing: the event is cancelled
 * BEFORE any handler runs, and it is cancelled for the whole event, not just
 * the top inventory. That covers shift-clicking from the player's own inventory
 * into the menu, number-key swaps, and drags that straddle both inventories -
 * all of which are ways to move a menu icon into real storage if you only
 * cancel the obvious case.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();

        // SellMenu is the one screen with REAL, clickable slots: it is a deposit
        // box, so items must be allowed in and out of the top four rows. Only
        // the control row is locked.
        if (top.getHolder() instanceof SellMenu sell) {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int raw = event.getRawSlot();

            if (raw >= 0 && raw < top.getSize() && !sell.isDepositSlot(raw)) {
                event.setCancelled(true);
                sell.handleControlClick(raw);
                return;
            }
            // A deposit change invalidates any pending confirmation, so nobody
            // can queue a confirm and then swap in something different.
            org.bukkit.Bukkit.getScheduler().runTask(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("UniversalMarket"),
                    () -> { sell.resetConfirm(); sell.refreshTotals(); });
            return;
        }

        if (!(top.getHolder() instanceof Gui gui)) return;

        // Cancel first, always, no exceptions.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Only route clicks that landed inside the menu itself.
        if (event.getClickedInventory() != top) return;
        gui.handleClick(player, event.getSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();

        if (top.getHolder() instanceof SellMenu sell) {
            for (int slot : event.getRawSlots()) {
                if (slot < top.getSize() && !sell.isDepositSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
            org.bukkit.Bukkit.getScheduler().runTask(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("UniversalMarket"),
                    () -> { sell.resetConfirm(); sell.refreshTotals(); });
            return;
        }

        if (!(top.getHolder() instanceof Gui)) return;
        // A drag that touches even one menu slot is refused outright.
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) { event.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        var holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof SellMenu sell) {
            // Never let a player lose a deposit by pressing Escape.
            sell.handleClose(event);
            return;
        }
        if (holder instanceof Gui gui) gui.handleClose();
    }
}
