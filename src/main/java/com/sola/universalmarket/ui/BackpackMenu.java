package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.backpack.BackpackService;
import com.sola.universalmarket.backpack.BackpackTier;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Backpack shop, and the storage view itself.
 *
 * The storage view is a real inventory with real slots, like the sell deposit
 * box - so it needs the same care: contents are written straight back into the
 * owner's backpack, and a viewer who is not the owner gets every slot locked.
 */
public final class BackpackMenu {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    public BackpackMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    // ==================================================================
    // Shop
    // ==================================================================

    public void openShop(Player player) {
        var owned = plugin.backpacks().of(player.getUniqueId());
        Gui gui = new Gui("<dark_gray>\u2726 <dark_purple>BACKPACKS <dark_gray>\u2726", 6);
        BigDecimal balance = plugin.economy().balance(player);

        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (BackpackTier tier : BackpackTier.values()) {
            BigDecimal price = BigDecimal.valueOf(tier.price());
            boolean isOwned = owned != null && owned.tier == tier;
            boolean isBelow = owned != null && owned.tier.level() > tier.level();
            boolean affordable = balance.compareTo(price) >= 0;

            List<String> lore = new ArrayList<>();
            lore.add("<gray>Capacity: <white>" + tier.slots() + "</white> slots");
            lore.add("<dark_gray>about " + tier.chestEquivalent() + " chests, "
                    + tier.pages() + " page" + (tier.pages() == 1 ? "" : "s"));
            lore.add("");
            lore.add("<gold><b>" + NumberFormatter.money(price));
            lore.add("");
            if (isOwned) {
                lore.add("<green>\u2713 This is your backpack");
            } else if (isBelow) {
                lore.add("<dark_gray>Smaller than the one you own.");
            } else if (owned != null) {
                lore.add("<yellow>Upgrade from " + owned.tier.coloured());
                lore.add("<gray>Everything inside is kept.");
                lore.add("<dark_gray>Upgrades cost full price.");
                lore.add("");
                lore.add(affordable ? "<yellow>Click to upgrade"
                        : "<red>Short by " + NumberFormatter.money(price.subtract(balance)));
            } else {
                lore.add(affordable ? "<yellow>Click to buy"
                        : "<red>Short by " + NumberFormatter.money(price.subtract(balance)));
            }

            gui.set(slots[tier.level() - 1], Gui.icon(
                    isBelow ? Material.GRAY_SHULKER_BOX : tier.icon(),
                    tier.colour() + "<b>TIER " + tier.level() + " \u2013 " + tier.display(),
                    lore),
                    p -> {
                        if (isOwned || isBelow) { plugin.sounds().error(p); return; }
                        if (plugin.backpacks().buy(p, tier)) openShop(p);
                    });
        }

        if (owned != null) {
            gui.set(29, Gui.icon(Material.ENDER_CHEST,
                    "<white><b>Your backpack",
                    "<gray>Tier " + owned.tier.level() + " - " + owned.tier.coloured(),
                    "<gray>Used: <white>" + owned.usedSlots() + "</white>/"
                            + owned.tier.slots(),
                    "",
                    owned.isPlaced()
                            ? "<yellow>Currently placed at <white>"
                                    + owned.placedAt.getBlockX() + ", "
                                    + owned.placedAt.getBlockY() + ", "
                                    + owned.placedAt.getBlockZ()
                            : "<gray>Not placed anywhere."));

            gui.set(33, Gui.icon(Material.LIME_CONCRETE,
                    "<green><b>RECALL BACKPACK",
                    "<gray>Bring it back to your inventory.",
                    "",
                    owned.isPlaced()
                            ? "<gray>The placed block will vanish."
                            : "<gray>Hands you the item again.",
                    "<dark_gray>Your items are never at risk."),
                    p -> {
                        if (plugin.backpacks().recall(p)) {
                            p.sendMessage(Gui.MM.deserialize(
                                    plugin.messages().get("backpack.recalled")));
                            plugin.sounds().confirm(p);
                        }
                        openShop(p);
                    });
        } else {
            gui.set(31, Gui.icon(Material.PAPER,
                    "<white><b>How backpacks work",
                    "<gray>Buy one, and you get a shulker item.",
                    "<gray>Place it down to open it.",
                    "",
                    "<gray>Breaking it makes the block vanish -",
                    "<gray>your items stay safe. Recall it here.",
                    "",
                    "<gray>Others can look inside but",
                    "<gray>cannot take anything.",
                    "",
                    "<dark_gray>Upgrading keeps everything stored."));
        }

        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Storage view
    // ==================================================================

    /** A live page of a backpack. Real slots, unlike every other Gui. */
    public static final class StorageView implements InventoryHolder {
        private final Inventory inventory;
        public final BackpackService.Backpack backpack;
        public final Player viewer;
        public final boolean readOnly;
        public final int page;

        StorageView(BackpackService.Backpack backpack, Player viewer,
                    boolean readOnly, int page, String title) {
            this.backpack = backpack;
            this.viewer = viewer;
            this.readOnly = readOnly;
            this.page = page;
            this.inventory = Bukkit.createInventory(this, 54, Gui.MM.deserialize(title));
        }

        @Override
        public Inventory getInventory() { return inventory; }

        public int firstSlot() { return page * BackpackTier.PAGE_SIZE; }

        public boolean isStorageSlot(int slot) {
            if (slot < 0 || slot >= BackpackTier.PAGE_SIZE) return false;
            return firstSlot() + slot < backpack.contents.length;
        }
    }

    public void openStorage(Player viewer, BackpackService.Backpack backpack, int page) {
        boolean readOnly = !viewer.getUniqueId().equals(backpack.owner);
        int pages = backpack.tier.pages();
        int current = Math.max(0, Math.min(page, pages - 1));

        String title = readOnly
                ? "<dark_gray>" + backpack.ownerName + "'s Backpack <red>(viewing)"
                : backpack.tier.colour() + "Backpack <dark_gray>(" + (current + 1)
                        + "/" + pages + ")";

        StorageView view = new StorageView(backpack, viewer, readOnly, current, title);
        Inventory inventory = view.getInventory();

        int offset = current * BackpackTier.PAGE_SIZE;
        for (int i = 0; i < BackpackTier.PAGE_SIZE; i++) {
            int index = offset + i;
            if (index >= backpack.contents.length) {
                inventory.setItem(i, Gui.icon(Material.BLACK_STAINED_GLASS_PANE, " "));
            } else {
                inventory.setItem(i, backpack.contents[index]);
            }
        }

        // Navigation row.
        ItemStack filler = Gui.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inventory.setItem(i, filler);

        if (current > 0) {
            inventory.setItem(45, Gui.icon(Material.RED_CONCRETE,
                    "<red><b>\u2190 Previous page",
                    "<gray>Page " + current + " of " + pages));
        }
        if (current < pages - 1) {
            inventory.setItem(53, Gui.icon(Material.LIME_CONCRETE,
                    "<green><b>Next page \u2192",
                    "<gray>Page " + (current + 2) + " of " + pages));
        }
        inventory.setItem(49, Gui.icon(backpack.tier.icon(),
                backpack.tier.colour() + "<b>" + backpack.ownerName + "'s backpack",
                "<gray>Tier " + backpack.tier.level() + " - "
                        + backpack.tier.slots() + " slots",
                "<gray>Used: <white>" + backpack.usedSlots() + "</white>/"
                        + backpack.tier.slots(),
                "",
                readOnly ? "<red>You cannot take from this."
                         : "<gray>Page " + (current + 1) + " of " + pages));

        viewer.openInventory(inventory);
        plugin.sounds().open(viewer);
        plugin.backpacks().touch(backpack);
    }

    /** Write a page back into the backpack. Called on every change and on close. */
    public void syncPage(StorageView view) {
        if (view.readOnly) return;
        Inventory inventory = view.getInventory();
        int offset = view.firstSlot();

        for (int i = 0; i < BackpackTier.PAGE_SIZE; i++) {
            int index = offset + i;
            if (index >= view.backpack.contents.length) continue;
            ItemStack stack = inventory.getItem(i);
            view.backpack.contents[index] =
                    (stack == null || stack.getType() == Material.AIR) ? null : stack;
        }
        plugin.backpacks().touch(view.backpack);
    }

    public void handlePageButton(StorageView view, int slot) {
        int pages = view.backpack.tier.pages();
        if (slot == 45 && view.page > 0) {
            syncPage(view);
            plugin.sounds().page(view.viewer);
            openStorage(view.viewer, view.backpack, view.page - 1);
        } else if (slot == 53 && view.page < pages - 1) {
            syncPage(view);
            plugin.sounds().page(view.viewer);
            openStorage(view.viewer, view.backpack, view.page + 1);
        }
    }
}
