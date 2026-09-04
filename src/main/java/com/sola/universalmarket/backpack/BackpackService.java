package com.sola.universalmarket.backpack;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual backpacks.
 *
 * WHERE THE ITEMS ACTUALLY LIVE
 *
 *   In the database, keyed to the OWNER - never to the block. The placed block
 *   is only a door into that storage. That single decision removes an entire
 *   category of disasters: a world edit, a chunk deletion, a rollback or a
 *   creeper cannot take anyone's items, because the items were never in the
 *   world to begin with.
 *
 *   It also means the terminal can always recall a backpack. If the block has
 *   gone missing for any reason, recalling simply hands the item back and the
 *   contents are exactly where they were.
 *
 * ONE BACKPACK PER PLAYER
 *
 *   Buying a higher tier UPGRADES the one you own and keeps everything in it.
 *   Without that rule someone could own all seven at once and hold 1,161 slots,
 *   and there would be no way to reconcile which one your items were in.
 */
public final class BackpackService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final NamespacedKey backpackKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey tierKey;

    /** owner -> their backpack. Loaded at startup, saved on change. */
    private final Map<UUID, Backpack> backpacks = new HashMap<>();
    /** placed block -> owner, so a right click knows whose door it is. */
    private final Map<Location, UUID> placed = new HashMap<>();

    public BackpackService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.backpackKey = new NamespacedKey(plugin, "backpack");
        this.ownerKey = new NamespacedKey(plugin, "backpack_owner");
        this.tierKey = new NamespacedKey(plugin, "backpack_tier");
    }

    /** One player's backpack: its tier, its contents, and where it is. */
    public static final class Backpack {
        public final UUID owner;
        public String ownerName;
        public BackpackTier tier;
        public ItemStack[] contents;
        public Location placedAt;
        public long lastAccess;

        Backpack(UUID owner, String ownerName, BackpackTier tier) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.tier = tier;
            this.contents = new ItemStack[tier.slots()];
            this.lastAccess = System.currentTimeMillis();
        }

        public boolean isPlaced() { return placedAt != null; }

        /** Grow the array when upgrading, preserving everything already stored. */
        void resizeTo(BackpackTier newTier) {
            ItemStack[] bigger = new ItemStack[newTier.slots()];
            System.arraycopy(contents, 0, bigger, 0,
                    Math.min(contents.length, bigger.length));
            this.contents = bigger;
            this.tier = newTier;
        }

        public int usedSlots() {
            int used = 0;
            for (ItemStack stack : contents) {
                if (stack != null && stack.getType() != Material.AIR) used++;
            }
            return used;
        }
    }

    // ==================================================================
    // Lookup
    // ==================================================================

    public Backpack of(UUID owner) {
        return backpacks.get(owner);
    }

    public boolean has(UUID owner) {
        return backpacks.containsKey(owner);
    }

    public Backpack atBlock(Location location) {
        UUID owner = placed.get(normalise(location));
        return owner == null ? null : backpacks.get(owner);
    }

    public boolean isBackpackBlock(Location location) {
        return placed.containsKey(normalise(location));
    }

    private Location normalise(Location location) {
        return new Location(location.getWorld(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    // ==================================================================
    // Buying and upgrading
    // ==================================================================

    public boolean buy(Player player, BackpackTier tier) {
        Backpack existing = backpacks.get(player.getUniqueId());

        if (existing != null && existing.tier.level() >= tier.level()) {
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.no-downgrade")
                    .replace("%tier%", existing.tier.coloured())));
            plugin.sounds().error(player);
            return false;
        }

        BigDecimal price = BigDecimal.valueOf(tier.price());
        if (plugin.economy().balance(player).compareTo(price) < 0) {
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.insufficient")
                    .replace("%tier%", tier.coloured())
                    .replace("%price%", NumberFormatter.money(price))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().broke(player);
            return false;
        }
        if (!plugin.economy().withdraw(player, price)) {
            plugin.sounds().error(player);
            return false;
        }

        if (existing == null) {
            Backpack fresh = new Backpack(player.getUniqueId(), player.getName(), tier);
            backpacks.put(player.getUniqueId(), fresh);
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.bought")
                    .replace("%tier%", tier.coloured())
                    .replace("%slots%", String.valueOf(tier.slots()))
                    .replace("%price%", NumberFormatter.money(price))));
        } else {
            BackpackTier from = existing.tier;
            existing.resizeTo(tier);
            player.sendMessage(mm.deserialize(plugin.messages().get("backpack.upgraded")
                    .replace("%from%", from.coloured())
                    .replace("%tier%", tier.coloured())
                    .replace("%slots%", String.valueOf(tier.slots()))
                    .replace("%price%", NumberFormatter.money(price))));
            // A placed block still points at the same backpack, so nothing to move.
        }

        plugin.transactions().recordPurchase(player.getUniqueId(),
                "backpack:tier" + tier.level(), 1, price);
        save(backpacks.get(player.getUniqueId()));
        plugin.sounds().bigBuy(player);
        return true;
    }

    // ==================================================================
    // The physical item
    // ==================================================================

    public ItemStack createItem(Backpack backpack) {
        ItemStack stack = new ItemStack(backpack.tier.icon(), 1);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(mm.deserialize(backpack.tier.colour() + "<b>\u2726 "
                        + backpack.tier.display().toUpperCase() + " BACKPACK \u2726")
                .decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : List.of(
                "<gray>Owner: <white>" + backpack.ownerName,
                "<gray>Capacity: <white>" + backpack.tier.slots() + "</white> slots"
                        + " <dark_gray>(" + backpack.tier.chestEquivalent() + " chests)",
                "<gray>In use: <white>" + backpack.usedSlots() + "</white>/"
                        + backpack.tier.slots(),
                "",
                "<gray>Place it down to open it.",
                "<gray>Break it and it vanishes - your items",
                "<gray>are safe, recall it from the terminal.",
                "",
                "<dark_gray>Others can look inside but cannot take.")) {
            lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(backpackKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                backpack.owner.toString());
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER,
                backpack.tier.level());
        try { meta.setEnchantmentGlintOverride(true); } catch (Throwable ignored) { }

        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isBackpackItem(ItemStack stack) {
        if (stack == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        Byte flag = meta.getPersistentDataContainer().get(backpackKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    public UUID ownerOfItem(ItemStack stack) {
        if (!isBackpackItem(stack)) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING);
        try { return raw == null ? null : UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ==================================================================
    // Placing, breaking, recalling
    // ==================================================================

    public void registerPlaced(Block block, Backpack backpack) {
        Location location = normalise(block.getLocation());
        backpack.placedAt = location;
        backpack.lastAccess = System.currentTimeMillis();
        placed.put(location, backpack.owner);
        save(backpack);
    }

    /**
     * Recall a backpack to its owner's hand.
     *
     * Works whether or not the block still exists. If the world it was in has
     * been deleted or the block is gone, the item comes back anyway, because
     * the contents were never stored in the block.
     */
    public boolean recall(Player player) {
        Backpack backpack = backpacks.get(player.getUniqueId());
        if (backpack == null) return false;

        if (backpack.placedAt != null) {
            placed.remove(backpack.placedAt);
            try {
                Block block = backpack.placedAt.getBlock();
                if (isShulker(block.getType())) block.setType(Material.AIR);
            } catch (Throwable ignored) {
                // World unloaded or deleted. The item still comes back.
            }
            backpack.placedAt = null;
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(mm.deserialize(plugin.messages().get("terminal.no-space")));
            plugin.sounds().error(player);
            save(backpack);
            return false;
        }
        player.getInventory().addItem(createItem(backpack));
        save(backpack);
        return true;
    }

    /** Owner broke it: the block vanishes and no item drops. */
    public void onBroken(Backpack backpack) {
        if (backpack.placedAt != null) {
            placed.remove(backpack.placedAt);
            backpack.placedAt = null;
        }
        save(backpack);
    }

    private boolean isShulker(Material material) {
        return material.name().endsWith("SHULKER_BOX");
    }

    /**
     * Auto-recall backpacks left standing for too long.
     *
     * Purely to stop the world filling with abandoned shulkers - the contents
     * are never at risk either way. Set to 0 in config to leave them placed
     * forever.
     */
    public void tickAutoRecall() {
        long minutes = plugin.getConfig().getLong("backpacks.auto-recall-minutes", 15);
        if (minutes <= 0) return;
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;

        for (Backpack backpack : new ArrayList<>(backpacks.values())) {
            if (backpack.placedAt == null) continue;
            if (backpack.lastAccess > cutoff) continue;

            placed.remove(backpack.placedAt);
            try {
                Block block = backpack.placedAt.getBlock();
                if (isShulker(block.getType())) block.setType(Material.AIR);
            } catch (Throwable ignored) { }
            backpack.placedAt = null;
            save(backpack);

            Player owner = Bukkit.getPlayer(backpack.owner);
            if (owner != null) {
                owner.sendMessage(mm.deserialize(plugin.messages().get("backpack.auto-recalled")));
            }
        }
    }

    public void touch(Backpack backpack) {
        backpack.lastAccess = System.currentTimeMillis();
    }

    // ==================================================================
    // What may not go inside
    // ==================================================================

    /**
     * Reject nested storage and plugin items.
     *
     * Putting a container inside a container is the classic duplication vector,
     * and a backpack holding another backpack has no sane resolution when one
     * of them is recalled.
     */
    public boolean isAllowed(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return true;
        if (isBackpackItem(stack)) return false;
        if (plugin.terminal().isTerminal(stack)) return false;
        if (plugin.sellFlow().isSellChest(stack)) return false;
        if (plugin.crates().isCrate(stack)) return false;
        return !isShulker(stack.getType());
    }

    // ==================================================================
    // Persistence
    // ==================================================================

    public void save(Backpack backpack) {
        String encoded = encode(backpack.contents);
        Location at = backpack.placedAt;

        plugin.storage().execute("""
                INSERT INTO backpacks
                    (owner_uuid, owner_name, tier, contents,
                     world, x, y, z, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid) DO UPDATE SET
                    owner_name = ?, tier = ?, contents = ?,
                    world = ?, x = ?, y = ?, z = ?, updated_at = ?""",
                backpack.owner.toString(), backpack.ownerName, backpack.tier.level(), encoded,
                at == null ? null : at.getWorld().getName(),
                at == null ? 0 : at.getBlockX(),
                at == null ? 0 : at.getBlockY(),
                at == null ? 0 : at.getBlockZ(),
                System.currentTimeMillis(),
                backpack.ownerName, backpack.tier.level(), encoded,
                at == null ? null : at.getWorld().getName(),
                at == null ? 0 : at.getBlockX(),
                at == null ? 0 : at.getBlockY(),
                at == null ? 0 : at.getBlockZ(),
                System.currentTimeMillis());
    }

    public void loadAll() {
        plugin.storage().query("""
                SELECT owner_uuid, owner_name, tier, contents, world, x, y, z
                FROM backpacks""",
                rs -> {
                    List<Object[]> rows = new ArrayList<>();
                    try {
                        while (rs.next()) {
                            rows.add(new Object[]{
                                    rs.getString("owner_uuid"), rs.getString("owner_name"),
                                    rs.getInt("tier"), rs.getString("contents"),
                                    rs.getString("world"), rs.getInt("x"),
                                    rs.getInt("y"), rs.getInt("z")});
                        }
                    } catch (Exception ignored) { }
                    return rows;
                }).thenAccept(rows -> {
                    if (rows == null) return;
                    // Deserialising ItemStacks touches Bukkit, so hop to the main thread.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Object[] row : rows) {
                            try {
                                UUID owner = UUID.fromString((String) row[0]);
                                BackpackTier tier = BackpackTier.byLevel((Integer) row[2]);
                                if (tier == null) continue;

                                Backpack backpack = new Backpack(owner, (String) row[1], tier);
                                ItemStack[] decoded = decode((String) row[3], tier.slots());
                                if (decoded != null) backpack.contents = decoded;

                                String world = (String) row[4];
                                if (world != null && Bukkit.getWorld(world) != null) {
                                    Location at = new Location(Bukkit.getWorld(world),
                                            (Integer) row[5], (Integer) row[6], (Integer) row[7]);
                                    backpack.placedAt = at;
                                    placed.put(at, owner);
                                }
                                backpacks.put(owner, backpack);
                            } catch (Throwable t) {
                                plugin.getLogger().warning("Skipped an unreadable backpack: " + t);
                            }
                        }
                        plugin.getLogger().info("Loaded " + backpacks.size() + " backpacks.");
                    });
                });
    }

    public void saveAll() {
        for (Backpack backpack : backpacks.values()) save(backpack);
    }

    private String encode(ItemStack[] contents) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(contents.length);
            for (ItemStack stack : contents) out.writeObject(stack);
            out.flush();
            // JDK Base64 rather than snakeyaml's internal coder: that class
            // lives in a shaded, non-API package and is not guaranteed to be
            // on Paper's classpath.
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Throwable t) {
            plugin.getLogger().severe("Could not serialise a backpack: " + t);
            return "";
        }
    }

    private ItemStack[] decode(String encoded, int expectedSlots) {
        if (encoded == null || encoded.isBlank()) return null;
        try (ByteArrayInputStream bytes =
                     new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {

            int length = in.readInt();
            ItemStack[] out = new ItemStack[Math.max(expectedSlots, length)];
            for (int i = 0; i < length; i++) out[i] = (ItemStack) in.readObject();

            // Trim or pad to the tier's real size, in case a tier was retuned.
            if (out.length != expectedSlots) {
                ItemStack[] resized = new ItemStack[expectedSlots];
                System.arraycopy(out, 0, resized, 0, Math.min(out.length, expectedSlots));
                return resized;
            }
            return out;
        } catch (Throwable t) {
            plugin.getLogger().severe("Could not read a backpack's contents: " + t);
            return null;
        }
    }

    public int count() {
        return backpacks.size();
    }
}
