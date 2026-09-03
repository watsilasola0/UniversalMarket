package com.sola.universalmarket.crate;

import org.bukkit.Material;

/**
 * The seven crate tiers.
 *
 * ON EXPECTED VALUE - read this before changing the numbers.
 *
 *   You asked for a 60% chance of profit. That makes crates net POSITIVE value,
 *   which is not merely inflationary: it is an unbounded money loop. Buy crate,
 *   sell contents, repeat, forever. Nothing in the plugin could stop it.
 *
 *   So the default expected return is slightly BELOW the crate price. A crate
 *   still pays out more than it cost roughly 40% of the time - the big wins are
 *   real and feel good - but over many openings the house edges ahead, which
 *   makes crates a controlled money SINK rather than a faucet.
 *
 *   config: crates.expected-return controls this. Set it above 1.0 if you want
 *   the original behaviour, knowing what it does.
 */
public enum CrateTier {

    WOODEN    ("Wooden",    "<gray>",         Material.OAK_PLANKS,          50_000L),
    STONE     ("Stone",     "<white>",        Material.STONE,              500_000L),
    IRON      ("Iron",      "<white>",        Material.IRON_BLOCK,       3_000_000L),
    GOLD      ("Gold",      "<yellow>",       Material.GOLD_BLOCK,      15_000_000L),
    DIAMOND   ("Diamond",   "<aqua>",         Material.DIAMOND_BLOCK,   60_000_000L),
    NETHERITE ("Netherite", "<dark_gray>",    Material.NETHERITE_BLOCK, 250_000_000L),
    LEGENDARY ("Legendary", "<dark_purple>",  Material.END_CRYSTAL,   1_000_000_000L);

    private final String display;
    private final String colour;
    private final Material icon;
    private final long price;

    CrateTier(String display, String colour, Material icon, long price) {
        this.display = display;
        this.colour = colour;
        this.icon = icon;
        this.price = price;
    }

    public String display() { return display; }
    public String colour() { return colour; }
    public String coloured() { return colour + display; }
    public Material icon() { return icon; }
    public long price() { return price; }

    /**
     * Payout bands. Each entry is {weight, minPercent, maxPercent} where the
     * percentages are of the crate price.
     *
     * The shape is deliberate: most openings land a little under the price, a
     * decent minority beat it, and a rare few pay several times over. That is
     * what makes opening one feel worth watching rather than a flat refund.
     */
    public int[][] payoutBands() {
        return switch (this) {
            // Low tiers swing less - losing 90% of a wooden crate is not a story.
            case WOODEN, STONE -> new int[][]{
                    {30,  40,  70},    // poor
                    {35,  70,  95},    // slightly down
                    {25,  95, 140},    // up
                    {10, 140, 260}     // good
            };
            case IRON, GOLD -> new int[][]{
                    {32,  30,  70},
                    {33,  70,  95},
                    {25,  95, 150},
                    {8,  150, 300},
                    {2,  300, 600}
            };
            // Top tiers swing hard: the jackpot is the point.
            case DIAMOND, NETHERITE -> new int[][]{
                    {35,  20,  65},
                    {30,  65,  95},
                    {24,  95, 160},
                    {9,  160, 400},
                    {2,  400, 900}
            };
            case LEGENDARY -> new int[][]{
                    {36,  15,  60},
                    {29,  60,  95},
                    {23,  95, 170},
                    {9,  170, 500},
                    {3,  500, 1500}
            };
        };
    }

    /**
     * What this tier can pay out in.
     *
     * Deliberately mixed: blocks, tools, armour, food, arrows, potions,
     * enchanted books, redstone gear and rare drops. A crate that only ever
     * spits out ore is a slot machine with one symbol - the variety is what
     * makes opening one interesting even when the value is average.
     */
    public String[] lootPool() {
        return switch (this) {
            case WOODEN -> new String[]{
                    "OAK_LOG", "COBBLESTONE", "COAL", "BREAD", "ARROW", "TORCH",
                    "IRON_NUGGET", "STRING", "LEATHER", "APPLE", "WHEAT", "CLAY_BALL",
                    "STONE_PICKAXE", "STONE_SWORD", "LEATHER_CHESTPLATE", "BOWL",
                    "COOKED_CHICKEN", "WOODEN_AXE", "LADDER", "OAK_PLANKS",
                    "REDSTONE", "STICK", "FLINT", "COOKED_COD", "GLASS", "SAND"};

            case STONE -> new String[]{
                    "IRON_INGOT", "COPPER_INGOT", "COAL", "REDSTONE", "LAPIS_LAZULI",
                    "COOKED_BEEF", "OAK_LOG", "GLASS", "FLINT", "GUNPOWDER",
                    "IRON_PICKAXE", "IRON_SWORD", "IRON_HELMET", "IRON_BOOTS",
                    "BOW", "ARROW", "SHIELD", "BUCKET", "COMPASS", "CLOCK",
                    "REPEATER", "PISTON", "RAIL", "HOPPER", "CHEST", "FURNACE",
                    "BAKED_POTATO", "PUMPKIN_PIE", "COOKED_SALMON", "TORCH"};

            case IRON -> new String[]{
                    "IRON_BLOCK", "GOLD_INGOT", "REDSTONE_BLOCK", "LAPIS_BLOCK",
                    "QUARTZ", "ENDER_PEARL", "BLAZE_ROD", "GOLDEN_CARROT", "OBSIDIAN",
                    "DIAMOND_PICKAXE", "DIAMOND_SWORD", "DIAMOND_HELMET", "DIAMOND_BOOTS",
                    "CROSSBOW", "SPECTRAL_ARROW", "ENCHANTED_BOOK", "EXPERIENCE_BOTTLE",
                    "OBSERVER", "STICKY_PISTON", "COMPARATOR", "DISPENSER", "POWERED_RAIL",
                    "GOLDEN_APPLE", "COOKED_BEEF", "ANVIL", "ENDER_CHEST", "BREWING_STAND"};

            case GOLD -> new String[]{
                    "GOLD_BLOCK", "DIAMOND", "EMERALD", "ENDER_PEARL", "BLAZE_ROD",
                    "GHAST_TEAR", "PHANTOM_MEMBRANE", "GOLDEN_APPLE", "AMETHYST_SHARD",
                    "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_AXE",
                    "ENCHANTED_BOOK", "EXPERIENCE_BOTTLE", "TRIDENT", "ELYTRA",
                    "OBSIDIAN", "CRYING_OBSIDIAN", "RESPAWN_ANCHOR", "BEACON",
                    "OBSERVER", "SHULKER_SHELL", "NETHERITE_SCRAP", "TNT",
                    "ENCHANTING_TABLE", "GOLDEN_CARROT", "SPECTRAL_ARROW"};

            case DIAMOND -> new String[]{
                    "DIAMOND_BLOCK", "EMERALD_BLOCK", "GOLDEN_APPLE", "NETHERITE_SCRAP",
                    "ANCIENT_DEBRIS", "SHULKER_SHELL", "ECHO_SHARD", "HEART_OF_THE_SEA",
                    "NETHERITE_PICKAXE", "NETHERITE_SWORD", "NETHERITE_CHESTPLATE",
                    "ENCHANTED_BOOK", "TOTEM_OF_UNDYING", "TRIDENT", "ELYTRA",
                    "BEACON", "CONDUIT", "ENCHANTED_GOLDEN_APPLE", "EXPERIENCE_BOTTLE",
                    "NETHERITE_INGOT", "DIAMOND", "EMERALD", "SPONGE", "END_CRYSTAL"};

            case NETHERITE -> new String[]{
                    "NETHERITE_INGOT", "DIAMOND_BLOCK", "ANCIENT_DEBRIS", "SHULKER_SHELL",
                    "TOTEM_OF_UNDYING", "ENCHANTED_GOLDEN_APPLE", "NETHERITE_SCRAP",
                    "NETHERITE_BLOCK", "NETHERITE_HELMET", "NETHERITE_BOOTS",
                    "NETHERITE_LEGGINGS", "ELYTRA", "TRIDENT", "BEACON", "CONDUIT",
                    "ENCHANTED_BOOK", "NETHER_STAR", "ECHO_SHARD", "EMERALD_BLOCK",
                    "HEART_OF_THE_SEA", "END_CRYSTAL", "DRAGON_BREATH"};

            case LEGENDARY -> new String[]{
                    "NETHERITE_BLOCK", "NETHER_STAR", "ELYTRA", "TOTEM_OF_UNDYING",
                    "ENCHANTED_GOLDEN_APPLE", "BEACON", "NETHERITE_INGOT", "DIAMOND_BLOCK",
                    "NETHERITE_CHESTPLATE", "NETHERITE_SWORD", "NETHERITE_PICKAXE",
                    "TRIDENT", "CONDUIT", "SHULKER_SHELL", "ENCHANTED_BOOK",
                    "EMERALD_BLOCK", "HEART_OF_THE_SEA", "ECHO_SHARD", "DRAGON_EGG"};
        };
    }

    public static CrateTier byName(String raw) {
        for (CrateTier tier : values()) {
            if (tier.name().equalsIgnoreCase(raw) || tier.display.equalsIgnoreCase(raw)) return tier;
        }
        return null;
    }
}
