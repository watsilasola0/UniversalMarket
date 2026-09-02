package com.sola.universalmarket.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimal chest-GUI framework.
 *
 * Every screen in the plugin is a Gui. The InventoryHolder is the Gui object
 * itself, which is what makes click handling safe: GuiListener only has to ask
 * "is this inventory's holder one of mine?". It never guesses from the title,
 * and it can never confuse a player's own chest for a menu.
 *
 * All clicks in a Gui are cancelled unconditionally before any handler runs, so
 * no menu icon can ever be removed, dragged, shift-clicked into a real
 * inventory, or duplicated. Handlers only decide what to DO, never whether the
 * click is allowed to move items.
 */
public class Gui implements InventoryHolder {

    protected static final MiniMessage MM = MiniMessage.miniMessage();

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> handlers = new HashMap<>();
    private Runnable onClose;

    public Gui(String miniMessageTitle, int rows) {
        int size = Math.max(9, Math.min(54, rows * 9));
        this.inventory = Bukkit.createInventory(this, size, MM.deserialize(miniMessageTitle));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    public Gui set(int slot, ItemStack icon, Consumer<Player> onClick) {
        if (slot < 0 || slot >= inventory.getSize()) return this;
        inventory.setItem(slot, icon);
        if (onClick != null) handlers.put(slot, onClick);
        else handlers.remove(slot);
        return this;
    }

    public Gui set(int slot, ItemStack icon) {
        return set(slot, icon, null);
    }

    /** Fill every empty slot with dark filler so the menu reads as a panel. */
    public Gui fillEmpty() {
        ItemStack filler = icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
        return this;
    }

    public Gui onClose(Runnable action) {
        this.onClose = action;
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------
    // Called by GuiListener
    // ------------------------------------------------------------------

    public void handleClick(Player player, int slot) {
        Consumer<Player> handler = handlers.get(slot);
        if (handler == null) return;
        try {
            handler.accept(player);
        } catch (Throwable t) {
            player.closeInventory();
            Bukkit.getLogger().warning("UniversalMarket GUI click failed: " + t);
        }
    }

    public void handleClose() {
        if (onClose != null) {
            try { onClose.run(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // Icon helpers
    // ------------------------------------------------------------------

    /**
     * Build a menu icon. Italics are switched off explicitly because Minecraft
     * italicises custom display names by default, which looks wrong in a menu.
     */
    public static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, List.of(lore));
    }

    protected static final int BACK_SLOT = 45;
    protected static final int CLOSE_SLOT = 49;
}
