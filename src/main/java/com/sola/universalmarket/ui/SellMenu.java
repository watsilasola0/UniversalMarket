package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sell to Server, as a deposit box.
 *
 * You drop items into the empty grid, it totals what the server will pay, and
 * you confirm. That is a much better fit than the old "click an icon to sell
 * one" list, because it lets you decide exactly what leaves your inventory
 * before any money moves.
 *
 * Unlike every other menu in the plugin, the deposit slots are REAL and
 * clickable. That means the close handler matters: anything still sitting in
 * the box when the window closes is handed straight back, so there is no way to
 * lose items by pressing Escape.
 */
public final class SellMenu implements InventoryHolder {

    /** The deposit area: the top four rows. */
    private static final int DEPOSIT_ROWS = 4;
    private static final int DEPOSIT_SIZE = DEPOSIT_ROWS * 9;

    private static final int SLOT_CONFIRM = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_BACK = 45;

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;
    private final Inventory inventory;
    private final Player owner;

    private boolean confirming = false;
    private boolean finished = false;

    public SellMenu(UniversalMarketPlugin plugin, MarketMenus menus, Player owner) {
        this.plugin = plugin;
        this.menus = menus;
        this.owner = owner;
        this.inventory = Bukkit.createInventory(this, 54,
                Gui.MM.deserialize("<dark_gray>\u2726 <gold>SELL TO SERVER <dark_gray>\u2726"));
        paintFrame();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player owner() {
        return owner;
    }

    public void open() {
        owner.openInventory(inventory);
        plugin.sounds().open(owner);
    }

    // ==================================================================
    // Rendering
    // ==================================================================

    private void paintFrame() {
        ItemStack filler = Gui.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = DEPOSIT_SIZE; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(SLOT_BACK, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"));
        refreshTotals();
    }

    /** Recalculate the payout and repaint the confirm and info buttons. */
    public void refreshTotals() {
        Valuation valuation = value();

        List<String> info = new ArrayList<>();
        if (valuation.entries.isEmpty()) {
            info.add("<gray>Drop items into the space above.");
            info.add("<gray>The server will price them here.");
        } else {
            for (Map.Entry<String, LineItem> entry : valuation.entries.entrySet()) {
                LineItem line = entry.getValue();
                info.add("<white>" + line.quantity + "x " + line.name
                        + " <gray>- <green>" + NumberFormatter.money(line.total));
            }
        }
        if (!valuation.rejected.isEmpty()) {
            info.add("");
            info.add("<red>Will be returned to you:");
            for (String name : valuation.rejected) info.add("<red>  " + name);
        }

        inventory.setItem(SLOT_INFO, Gui.icon(Material.PAPER,
                "<white><b>Total: <green>" + NumberFormatter.money(valuation.total), info));

        if (valuation.total.signum() > 0) {
            inventory.setItem(SLOT_CONFIRM, Gui.icon(Material.LIME_CONCRETE,
                    confirming ? "<green><b>\u2713 Click again to confirm"
                               : "<green><b>\u2713 Sell everything",
                    "<gray>You receive <green>"
                            + NumberFormatter.money(valuation.total),
                    "",
                    confirming ? "<yellow>Click once more to sell."
                               : "<gray>You will be asked to confirm."));
        } else {
            inventory.setItem(SLOT_CONFIRM, Gui.icon(Material.GRAY_CONCRETE,
                    "<dark_gray>Nothing to sell",
                    "<dark_gray>Add items above first."));
        }
    }

    // ==================================================================
    // Valuation
    // ==================================================================

    private record LineItem(String name, int quantity, BigDecimal total) { }

    private static final class Valuation {
        final Map<String, LineItem> entries = new LinkedHashMap<>();
        final List<String> rejected = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
    }

    /**
     * Price everything currently in the deposit area.
     *
     * Quantities are accumulated per item BEFORE quoting, so the diminishing
     * return tiers are applied across the whole deposit rather than per stack -
     * otherwise splitting 200 cobblestone into four stacks would dodge the tier
     * step-down.
     */
    private Valuation value() {
        Valuation valuation = new Valuation();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, MarketItem> items = new LinkedHashMap<>();

        for (int i = 0; i < DEPOSIT_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;

            MarketItem resolved = plugin.sell().resolveSellable(stack);
            if (resolved == null) {
                String pretty = com.sola.universalmarket.util.Names.pretty(
                        stack.getType().name());
                if (!valuation.rejected.contains(pretty)) valuation.rejected.add(pretty);
                continue;
            }
            counts.merge(resolved.id(), stack.getAmount(), Integer::sum);
            items.putIfAbsent(resolved.id(), resolved);
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            MarketItem item = items.get(entry.getKey());
            var quote = plugin.sell().quote(owner.getUniqueId(), item, entry.getValue());
            valuation.entries.put(entry.getKey(),
                    new LineItem(item.displayName(), entry.getValue(), quote.total()));
            valuation.total = valuation.total.add(quote.total());
        }
        return valuation;
    }

    // ==================================================================
    // Clicks, routed from GuiListener
    // ==================================================================

    public boolean isDepositSlot(int slot) {
        return slot >= 0 && slot < DEPOSIT_SIZE;
    }

    public void handleControlClick(int slot) {
        if (slot == SLOT_BACK) {
            plugin.sounds().click(owner);
            returnEverything();
            finished = true;
            Bukkit.getScheduler().runTask(plugin, () -> menus.openHome(owner));
            return;
        }
        if (slot != SLOT_CONFIRM) return;

        Valuation valuation = value();
        if (valuation.total.signum() <= 0) {
            plugin.sounds().error(owner);
            return;
        }
        if (!confirming) {
            confirming = true;
            plugin.sounds().confirm(owner);
            refreshTotals();
            return;
        }
        sellAll();
    }

    private void sellAll() {
        BigDecimal paid = BigDecimal.ZERO;
        Map<String, Integer> sold = new LinkedHashMap<>();

        for (int i = 0; i < DEPOSIT_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;

            MarketItem item = plugin.sell().resolveSellable(stack);
            if (item == null) continue;

            int amount = stack.getAmount();
            var quote = plugin.sell().quote(owner.getUniqueId(), item, amount);
            if (quote.total().signum() <= 0) continue;

            if (!plugin.economy().deposit(owner, quote.total())) {
                plugin.getLogger().warning("Deposit failed selling for " + owner.getName());
                plugin.sounds().error(owner);
                return;
            }
            inventory.setItem(i, null);
            paid = paid.add(quote.total());
            sold.merge(item.displayName(), amount, Integer::sum);

            plugin.sell().noteSold(owner.getUniqueId(), item, amount);
            plugin.pricing().onPlayerSold(item, amount);
            plugin.transactions().recordSale(owner.getUniqueId(), item.id(), amount, quote.total());
        }

        if (paid.signum() <= 0) { plugin.sounds().error(owner); return; }

        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : sold.entrySet()) {
            if (summary.length() > 0) summary.append("<gray>, ");
            summary.append("<white>").append(entry.getValue()).append("x ")
                   .append(entry.getKey());
        }
        owner.sendMessage(Gui.MM.deserialize("<green>+ "
                + NumberFormatter.money(paid) + "</green> <gray>for </gray>" + summary));

        plugin.sounds().sell(owner);
        plugin.announcements().checkPromotion(owner);

        confirming = false;
        refreshTotals();
    }

    /** Hand back anything still in the box. Called on close and on Back. */
    public void returnEverything() {
        if (finished) return;
        for (int i = 0; i < DEPOSIT_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;
            inventory.setItem(i, null);
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(stack);
            for (ItemStack drop : leftover.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), drop);
            }
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        returnEverything();
        finished = true;
    }

    public void resetConfirm() {
        if (confirming) {
            confirming = false;
            refreshTotals();
        }
    }
}
