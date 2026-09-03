package com.sola.universalmarket.crate;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Crates: buy one, place it, watch it roll, collect the loot.
 *
 * The roll is decided the moment the crate is opened, server-side. The animation
 * is purely cosmetic - it shows random items from the tier's pool while the
 * result is already fixed. Deciding the outcome up front means a disconnect or
 * a crash mid-animation cannot produce a second roll or a duplicated payout.
 */
public final class CrateService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Random random = new Random();

    private final NamespacedKey crateKey;
    private final NamespacedKey tierKey;

    /** Crates currently mid-animation, so they cannot be opened twice. */
    private final Map<Location, Boolean> rolling = new HashMap<>();

    public CrateService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
        this.crateKey = new NamespacedKey(plugin, "crate");
        this.tierKey = new NamespacedKey(plugin, "crate_tier");
    }

    // ==================================================================
    // The crate item
    // ==================================================================

    public ItemStack createCrate(CrateTier tier) {
        ItemStack stack = new ItemStack(Material.CHEST, 1);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(mm.deserialize(tier.colour() + "<b>\u2726 " + tier.display().toUpperCase()
                + " CRATE \u2726").decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : List.of(
                "<gray>Place it down to open.",
                "",
                "<gray>Paid: <gold>" + NumberFormatter.money(BigDecimal.valueOf(tier.price())),
                "<gray>Contents are worth anywhere from",
                "<red>a fraction</red> <gray>to</gray> <green>many times</green> <gray>that.",
                "",
                "<dark_gray>The roll is decided when you open it.")) {
            lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.name());
        try { meta.setEnchantmentGlintOverride(true); } catch (Throwable ignored) { }

        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isCrate(ItemStack stack) {
        if (stack == null || stack.getType() != Material.CHEST) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        Byte flag = meta.getPersistentDataContainer().get(crateKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    public CrateTier tierOf(ItemStack stack) {
        if (!isCrate(stack)) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(tierKey, PersistentDataType.STRING);
        return raw == null ? null : CrateTier.byName(raw);
    }

    // ==================================================================
    // Buying
    // ==================================================================

    public boolean buy(Player player, CrateTier tier) {
        BigDecimal price = BigDecimal.valueOf(tier.price());
        if (plugin.economy().balance(player).compareTo(price) < 0) {
            player.sendMessage(mm.deserialize(plugin.messages().get("crate.insufficient")
                    .replace("%tier%", tier.coloured())
                    .replace("%price%", NumberFormatter.money(price))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().broke(player);
            return false;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(mm.deserialize(plugin.messages().get("buy.inventory-full")));
            plugin.sounds().error(player);
            return false;
        }
        if (!plugin.economy().withdraw(player, price)) {
            plugin.sounds().error(player);
            return false;
        }

        player.getInventory().addItem(createCrate(tier));
        plugin.transactions().recordPurchase(player.getUniqueId(),
                "crate:" + tier.name().toLowerCase(java.util.Locale.ROOT), 1, price);

        player.sendMessage(mm.deserialize(plugin.messages().get("crate.bought")
                .replace("%tier%", tier.coloured())
                .replace("%price%", NumberFormatter.money(price))));
        plugin.sounds().bigBuy(player);
        return true;
    }

    // ==================================================================
    // Rolling
    // ==================================================================

    /** Decide the payout up front, then play the animation over it. */
    public void openCrate(Player player, Block block, CrateTier tier) {
        Location location = block.getLocation();
        if (rolling.putIfAbsent(location, Boolean.TRUE) != null) return;

        List<ItemStack> reward = rollReward(tier);
        BigDecimal worth = valueOf(reward);

        block.setType(Material.AIR);
        Location centre = location.clone().add(0.5, 1.1, 0.5);

        player.sendMessage(mm.deserialize(plugin.messages().get("crate.opening")
                .replace("%tier%", tier.coloured())));

        Item display = location.getWorld().dropItem(centre, new ItemStack(Material.STONE));
        display.setPickupDelay(Integer.MAX_VALUE);
        display.setUnlimitedLifetime(true);
        display.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        display.setGravity(false);
        display.setInvulnerable(true);

        String[] pool = tier.lootPool();

        new BukkitRunnable() {
            int tick = 0;
            int interval = 2;
            int nextSwap = 0;

            @Override
            public void run() {
                // Bail out safely if the world or player goes away mid-roll.
                if (!display.isValid()) { finish(); cancel(); return; }

                if (tick >= nextSwap) {
                    String name = pool[random.nextInt(pool.length)];
                    Material material = Material.getMaterial(name);
                    if (material != null && material.isItem()) {
                        display.setItemStack(new ItemStack(material));
                    }
                    location.getWorld().playSound(centre, "ui.button.click", 0.4f, 1.6f);
                    location.getWorld().spawnParticle(Particle.END_ROD, centre, 6, 0.3, 0.3, 0.3, 0.02);

                    // Ease out: swaps get slower until the reel stops.
                    nextSwap = tick + interval;
                    if (tick > 40) interval++;
                    if (tick > 70) interval += 2;
                }

                display.teleport(centre.clone().add(0, Math.sin(tick / 6.0) * 0.12, 0));

                if (tick++ >= 95) { finish(); cancel(); }
            }

            private void finish() {
                if (display.isValid()) display.remove();
                rolling.remove(location);

                location.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                        centre, 60, 0.5, 0.5, 0.5, 0.3);
                location.getWorld().playSound(centre,
                        worth.compareTo(BigDecimal.valueOf(tier.price())) >= 0
                                ? "ui.toast.challenge_complete" : "block.note_block.bass",
                        0.8f, 1.0f);

                for (ItemStack stack : reward) {
                    location.getWorld().dropItemNaturally(centre, stack);
                }

                if (player.isOnline()) {
                    boolean profit = worth.compareTo(BigDecimal.valueOf(tier.price())) >= 0;
                    StringBuilder contents = new StringBuilder();
                    for (ItemStack stack : reward) {
                        if (contents.length() > 0) contents.append("<gray>, ");
                        contents.append("<white>").append(stack.getAmount()).append("x ")
                                .append(com.sola.universalmarket.quest.Quest
                                        .prettyName(stack.getType().name()));
                    }
                    player.sendMessage(mm.deserialize(plugin.messages().get("crate.result")
                            .replace("%tier%", tier.coloured())
                            .replace("%contents%", contents.toString())
                            .replace("%worth%", NumberFormatter.money(worth))
                            .replace("%verdict%", profit
                                    ? "<green>You came out ahead."
                                    : "<red>Worth less than you paid.")));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Build a loot set whose market value lands inside a randomly chosen band.
     *
     * Bands are weighted, so the shape of the payout curve lives in CrateTier and
     * can be retuned without touching this method.
     */
    private List<ItemStack> rollReward(CrateTier tier) {
        int[][] bands = tier.payoutBands();
        int totalWeight = 0;
        for (int[] band : bands) totalWeight += band[0];

        int roll = random.nextInt(Math.max(1, totalWeight));
        int[] chosen = bands[bands.length - 1];
        int running = 0;
        for (int[] band : bands) {
            running += band[0];
            if (roll < running) { chosen = band; break; }
        }

        double percent = chosen[1] + random.nextDouble() * (chosen[2] - chosen[1]);
        double houseEdge = plugin.getConfig().getDouble("crates.expected-return", 0.92);
        BigDecimal target = BigDecimal.valueOf(tier.price())
                .multiply(BigDecimal.valueOf(percent / 100.0))
                .multiply(BigDecimal.valueOf(houseEdge))
                .setScale(0, RoundingMode.DOWN);

        return fillToValue(tier, target);
    }

    /** Pick items from the tier pool until their market value reaches the target. */
    private List<ItemStack> fillToValue(CrateTier tier, BigDecimal target) {
        List<ItemStack> out = new ArrayList<>();
        String[] pool = tier.lootPool();
        BigDecimal remaining = target;

        int guard = 0;
        while (remaining.signum() > 0 && out.size() < 9 && guard++ < 120) {
            String name = pool[random.nextInt(pool.length)];
            Material material = Material.getMaterial(name);
            if (material == null || !material.isItem()) continue;

            MarketItem entry = plugin.catalog().byMaterial(material);
            if (entry == null || entry.serverBuybackBase().signum() <= 0) continue;

            // Value loot at what the player can actually GET for it, not what
            // the shop charges. Filling to the shop price was the bug behind a
            // $15M crate paying out $2.6M: buyback is roughly 15-20% of the buy
            // price, so "fill to $15M of shop value" meant "$2.5M of real value".
            BigDecimal unit = entry.serverBuybackBase();
            int max = material.getMaxStackSize();
            int affordable = remaining.divide(unit, 0, RoundingMode.DOWN).intValue();
            if (affordable <= 0) {
                // Nothing in this pool fits the remainder; stop rather than
                // overshoot the band we promised.
                if (out.isEmpty()) out.add(new ItemStack(material, 1));
                break;
            }
            int amount = Math.max(1, Math.min(affordable, max));
            out.add(new ItemStack(material, amount));
            remaining = remaining.subtract(unit.multiply(BigDecimal.valueOf(amount)));
        }
        if (out.isEmpty()) out.add(new ItemStack(Material.COBBLESTONE, 1));
        return out;
    }

    private BigDecimal valueOf(List<ItemStack> stacks) {
        BigDecimal total = BigDecimal.ZERO;
        for (ItemStack stack : stacks) {
            MarketItem entry = plugin.catalog().byMaterial(stack.getType());
            if (entry == null) continue;
            total = total.add(entry.serverBuybackBase()
                    .multiply(BigDecimal.valueOf(stack.getAmount())));
        }
        return total;
    }

    public boolean isRolling(Location location) {
        return rolling.containsKey(location);
    }
}
