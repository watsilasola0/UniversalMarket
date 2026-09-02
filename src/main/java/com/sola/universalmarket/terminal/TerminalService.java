package com.sola.universalmarket.terminal;

import com.sola.universalmarket.UniversalMarketPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The permanent, soulbound Market Terminal.
 *
 * WHY THE OLD DESIGN DUPLICATED (spec section 7, Problem 4):
 *
 *   The previous plugin ran a repeating task that scanned the player's inventory
 *   slots every few seconds and created a terminal if it did not find one. When a
 *   player picks an item up with the mouse it leaves the slot array entirely and
 *   lives on the CURSOR. The scan saw zero terminals, created a replacement, the
 *   player then dropped the original back into a slot, and now there were two.
 *   Hold the item long enough and it duplicated on every scan tick.
 *
 * THE FIX - two independent changes, both necessary:
 *
 *   1. NO PERIODIC SCAN AT ALL. Terminal existence is only ever evaluated at
 *      discrete, meaningful moments: first join, respawn, and an explicit
 *      /um terminal request. There is no timer that can fire mid-drag.
 *
 *   2. The count function is drag-aware. It inspects storage, armor, offhand,
 *      the CURSOR, and any currently open inventory view. So even at those
 *      discrete moments, an in-flight item is counted as present.
 *
 * Fixing only (2) would still leave a scan racing against inventory transactions.
 * Fixing only (1) would still miscount if a player ran /um terminal mid-drag.
 * Both together make duplication structurally impossible rather than unlikely.
 */
public final class TerminalService {

    private final UniversalMarketPlugin plugin;
    private final NamespacedKey terminalKey;
    private final MiniMessage mm = MiniMessage.miniMessage();

    /**
     * Bumped whenever the terminal's appearance changes. Players carrying an
     * older one get it silently rebuilt on join, so a cosmetic change does not
     * require everyone to delete and re-request their terminal.
     */
    private static final int TERMINAL_VERSION = 3;

    private final NamespacedKey versionKey;

    public TerminalService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.terminalKey = new NamespacedKey(plugin, "market_terminal");
        this.versionKey = new NamespacedKey(plugin, "terminal_version");
    }

    /** True when this terminal was built by an older version of the plugin. */
    public boolean isOutdated(ItemStack stack) {
        if (!isTerminal(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;
        Integer version = meta.getPersistentDataContainer()
                .get(versionKey, PersistentDataType.INTEGER);
        return version == null || version < TERMINAL_VERSION;
    }

    public NamespacedKey key() {
        return terminalKey;
    }

    // ==================================================================
    // Identity
    // ==================================================================

    /** True if this stack is a Market Terminal, by PDC marker - never by name or material. */
    public boolean isTerminal(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte flag = pdc.get(terminalKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    public ItemStack createTerminal() {
        Material mat = Material.matchMaterial(
                plugin.getConfig().getString("terminal.material", "NETHER_STAR"));
        if (mat == null || !mat.isItem()) mat = Material.NETHER_STAR;

        ItemStack stack = new ItemStack(mat, 1);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(mm.deserialize(
                plugin.getConfig().getString("terminal.display-name", "<gold>✦ MARKET TERMINAL ✦"))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("terminal.lore")) {
            lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(terminalKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, TERMINAL_VERSION);
        meta.setUnbreakable(true);

        // --- appearance ---
        //
        // A Recovery Compass renders an extra client-generated tooltip line with
        // your last death coordinates. That text is produced by the client from
        // the item type, not from lore we control, so the only way to remove it
        // is HIDE_ADDITIONAL_TOOLTIP - the component flag that suppresses the
        // client's own extra lines.
        try {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        } catch (Throwable ignored) {
            // Older API name; harmless if absent.
        }
        try {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_ENCHANTS);
        } catch (Throwable ignored) { }

        // Enchanted glint without a real enchantment, so the item carries no
        // actual effect and cannot be disenchanted at a grindstone.
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable t) {
            // Fallback for servers where the override component is unavailable:
            // a hidden, harmless enchantment produces the same shimmer.
            try {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } catch (Throwable ignored) { }
        }

        // setItemMeta returns boolean on the real Paper API. Compiling against the
        // genuine paper-api artifact is what keeps this from becoming a
        // NoSuchMethodError at runtime (spec section 69, Problem 2).
        stack.setItemMeta(meta);
        return stack;
    }

    // ==================================================================
    // Counting - drag aware
    // ==================================================================

    /**
     * Count every terminal the player is currently in possession of, including
     * ones that are mid-transaction and therefore not in any slot array.
     */
    public int countTerminals(Player player) {
        int count = 0;
        PlayerInventory inv = player.getInventory();

        for (ItemStack s : inv.getStorageContents()) if (isTerminal(s)) count++;
        for (ItemStack s : inv.getArmorContents())   if (isTerminal(s)) count++;
        if (isTerminal(inv.getItemInOffHand())) count++;

        // THE CRITICAL ONE: an item held by the mouse is in none of the above.
        if (isTerminal(player.getItemOnCursor())) count++;

        // And anything sitting in a container the player currently has open.
        InventoryView view = player.getOpenInventory();
        if (view != null) {
            Inventory top = view.getTopInventory();
            if (top != null && top.getHolder() != player) {
                for (ItemStack s : top.getContents()) if (isTerminal(s)) count++;
            }
        }
        return count;
    }

    /** True when it is safe to conclude the player genuinely has no terminal. */
    public boolean isMissing(Player player) {
        return countTerminals(player) == 0;
    }

    // ==================================================================
    // Ensure / repair
    // ==================================================================

    /**
     * Give the player a terminal if and only if they truly have none.
     * Safe to call from join, respawn and the /um terminal command.
     */
    public boolean ensureTerminal(Player player) {
        if (!player.isOnline()) return false;

        int count = countTerminals(player);
        if (count == 1) {
            upgradeOutdated(player);
            return false;
        }

        if (count > 1) {
            int removed = removeExtras(player);
            plugin.getLogger().info("Cleaned up " + removed + " duplicate terminal(s) for " + player.getName());
            return false;
        }

        ItemStack terminal = createTerminal();
        PlayerInventory inv = player.getInventory();

        int preferred = plugin.getConfig().getInt("terminal.preferred-slot", 8);
        if (preferred >= 0 && preferred <= 8) {
            ItemStack existing = inv.getItem(preferred);
            if (existing == null || existing.getType() == Material.AIR) {
                inv.setItem(preferred, terminal);
                return true;
            }
        }
        if (inv.firstEmpty() == -1) {
            // Inventory completely full: do not drop it on the floor, just tell them.
            player.sendMessage(mm.deserialize(plugin.messages().get("terminal.no-space")));
            return false;
        }
        inv.addItem(terminal);
        return true;
    }

    /**
     * Rebuild an old-looking terminal in place, preserving its slot so the
     * player's hotbar layout is untouched.
     */
    public void upgradeOutdated(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = false;

        for (int i = 0; i < storage.length; i++) {
            if (isOutdated(storage[i])) { storage[i] = createTerminal(); changed = true; }
        }
        if (changed) inv.setStorageContents(storage);

        if (isOutdated(inv.getItemInOffHand())) {
            inv.setItemInOffHand(createTerminal());
            changed = true;
        }
        if (changed) player.updateInventory();
    }

    /** Keep exactly one terminal; delete the rest. Returns how many were removed. */
    public int removeExtras(Player player) {
        boolean kept = false;
        int removed = 0;
        PlayerInventory inv = player.getInventory();

        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (!isTerminal(storage[i])) continue;
            if (!kept) { kept = true; storage[i].setAmount(1); }
            else { storage[i] = null; removed++; }
        }
        inv.setStorageContents(storage);

        if (isTerminal(inv.getItemInOffHand())) {
            if (!kept) kept = true;
            else { inv.setItemInOffHand(null); removed++; }
        }
        if (isTerminal(player.getItemOnCursor())) {
            if (!kept) kept = true;
            else { player.setItemOnCursor(null); removed++; }
        }
        player.updateInventory();
        return removed;
    }

    /**
     * Destroy every terminal the player is carrying.
     *
     * Used when they try to put it somewhere it does not belong. Deleting is
     * safer than blocking: there is no way for a half-completed inventory move
     * to leave a copy behind, which is the class of bug that caused the original
     * duplication. They get it back instantly with /um.
     */
    public void consume(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (isTerminal(storage[i])) storage[i] = null;
        }
        inv.setStorageContents(storage);
        if (isTerminal(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isTerminal(player.getItemOnCursor())) player.setItemOnCursor(null);
        player.updateInventory();
        player.sendMessage(MiniMessage.miniMessage()
                .deserialize(plugin.messages().get("terminal.dissolved")));
    }

    /** Player-facing repair used by /um terminal. Never a source of free copies. */
    public void repair(Player player) {
        int count = countTerminals(player);
        if (count > 1) {
            int removed = removeExtras(player);
            player.sendMessage(mm.deserialize(plugin.messages().get("terminal.duplicates-removed")
                    .replace("%count%", String.valueOf(removed))));
        } else if (count == 1) {
            player.sendMessage(mm.deserialize(plugin.messages().get("terminal.already-have")));
        } else {
            if (ensureTerminal(player)) {
                player.sendMessage(mm.deserialize(plugin.messages().get("terminal.restored")));
            }
        }
    }
}
