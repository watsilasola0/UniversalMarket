package com.sola.universalmarket.catalog;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what a single item is worth.
 *
 * THE PROBLEM THIS REPLACES
 *
 *   The old model matched on name prefixes in a near-arbitrary order, so
 *   COOKED_CHICKEN hit a "COOKED_" rule worth 7,000 while GOLDEN_APPLE matched
 *   nothing and fell through to a 1,200 default. A golden apple costs eight gold
 *   ingots; a cooked chicken costs one chicken. The prices were backwards.
 *
 * THE MODEL
 *
 *   Value flows from raw materials upward, the same way crafting does:
 *
 *     1. RESOURCES  - a hand-set price for each raw material (iron, diamond,
 *                     leather, gold). These are the axioms; everything else is
 *                     derived from them, so retuning iron retunes every iron
 *                     item at once.
 *     2. DERIVED    - crafted goods are priced from what they are made of.
 *                     Golden apple = 8 gold + 1 apple. Iron chestplate = 8 iron.
 *                     Netherite gear = the diamond piece plus a netherite ingot.
 *     3. ANCHORS    - the section 20 values and hand-tuned exceptions win over
 *                     everything, because things like Elytra and Mending have no
 *                     sensible recipe-derived price.
 *
 *   A crafted item is worth slightly more than the sum of its parts (CRAFT_MARKUP),
 *   because the Universal Market is a convenience store, not a foundry. That also
 *   keeps "buy materials, craft, sell product" from being a reliable profit loop.
 */
public final class PriceModel {

    private PriceModel() {}

    /** Crafted goods cost a little more than their inputs. */
    private static final double CRAFT_MARKUP = 1.15;

    /** Storage-block name -> the resource item it is crafted from. */
    private static final Map<String, String> BLOCK_ALIASES = Map.of(
            "IRON",      "IRON_INGOT",
            "GOLD",      "GOLD_INGOT",
            "COPPER",    "COPPER_INGOT",
            "NETHERITE", "NETHERITE_INGOT",
            "LAPIS",     "LAPIS_LAZULI",
            "SLIME",     "SLIME_BALL",
            "AMETHYST",  "AMETHYST_SHARD",
            "HONEYCOMB", "HONEYCOMB");

    // ==================================================================
    // 1. Raw resource values - the axioms
    // ==================================================================

    private static final Map<String, Long> RESOURCE = new HashMap<>();
    static {
        // Metals and minerals
        RESOURCE.put("IRON_INGOT",       15_000L);
        RESOURCE.put("GOLD_INGOT",       25_000L);
        RESOURCE.put("COPPER_INGOT",      8_000L);
        RESOURCE.put("DIAMOND",         250_000L);
        RESOURCE.put("EMERALD",         150_000L);
        RESOURCE.put("NETHERITE_INGOT",5_000_000L);
        RESOURCE.put("NETHERITE_SCRAP",1_150_000L);
        RESOURCE.put("ANCIENT_DEBRIS", 1_000_000L);
        RESOURCE.put("COAL",              2_000L);
        RESOURCE.put("CHARCOAL",          1_800L);
        RESOURCE.put("LAPIS_LAZULI",      3_000L);
        RESOURCE.put("QUARTZ",            4_000L);
        RESOURCE.put("REDSTONE",          4_000L);
        RESOURCE.put("AMETHYST_SHARD",    6_000L);

        // Common organics
        RESOURCE.put("STICK",               200L);
        RESOURCE.put("STRING",              900L);
        RESOURCE.put("LEATHER",           3_000L);
        RESOURCE.put("FEATHER",             800L);
        RESOURCE.put("BONE",              1_200L);
        RESOURCE.put("FLINT",               900L);
        RESOURCE.put("PAPER",               600L);
        RESOURCE.put("BOOK",              4_000L);
        RESOURCE.put("WHEAT",               800L);
        RESOURCE.put("APPLE",             3_000L);
        RESOURCE.put("EGG",               1_000L);
        RESOURCE.put("CLAY_BALL",           700L);
        RESOURCE.put("BRICK",             1_500L);
        RESOURCE.put("SUGAR",               500L);
        RESOURCE.put("SLIME_BALL",        6_000L);
        RESOURCE.put("HONEYCOMB",         3_000L);
        RESOURCE.put("INK_SAC",           1_500L);

        // Hostile / dungeon drops
        RESOURCE.put("GUNPOWDER",         5_000L);
        RESOURCE.put("SPIDER_EYE",        3_000L);
        RESOURCE.put("ROTTEN_FLESH",        300L);
        RESOURCE.put("ENDER_PEARL",      30_000L);
        RESOURCE.put("BLAZE_ROD",        45_000L);
        RESOURCE.put("GHAST_TEAR",      120_000L);
        RESOURCE.put("MAGMA_CREAM",      18_000L);
        RESOURCE.put("PHANTOM_MEMBRANE", 40_000L);
        RESOURCE.put("PRISMARINE_SHARD",  8_000L);
        RESOURCE.put("PRISMARINE_CRYSTALS",12_000L);
        RESOURCE.put("NAUTILUS_SHELL",3_000_000L);
        RESOURCE.put("SHULKER_SHELL", 8_000_000L);
        RESOURCE.put("ECHO_SHARD",    5_000_000L);
        RESOURCE.put("NETHER_STAR", 150_000_000L);
        RESOURCE.put("DRAGON_BREATH", 2_000_000L);

        // Building basics, per single item
        RESOURCE.put("OAK_PLANKS",          560L);
        RESOURCE.put("COBBLESTONE",         391L);
        RESOURCE.put("STONE",               547L);
        RESOURCE.put("DIRT",                313L);
        RESOURCE.put("SAND",              1_250L);
        RESOURCE.put("GRAVEL",            1_094L);
        RESOURCE.put("GLASS",             2_344L);
        RESOURCE.put("OBSIDIAN",         12_000L);
        RESOURCE.put("NETHERRACK",          400L);
        RESOURCE.put("END_STONE",         2_500L);
        RESOURCE.put("SOUL_SAND",         2_000L);
    }

    // ==================================================================
    // 2. Explicit values that no formula should override
    // ==================================================================

    private static final Map<String, Long> EXPLICIT = new HashMap<>();
    static {
        // --- Food. Cheap and renewable; the whole point is that farming is
        //     accessible. Cooked meat is worth barely more than the raw drop.
        EXPLICIT.put("BREAD",             2_500L);
        EXPLICIT.put("COOKED_BEEF",       7_000L);   // section 20 anchor
        EXPLICIT.put("BEEF",              3_500L);
        EXPLICIT.put("COOKED_PORKCHOP",   6_000L);
        EXPLICIT.put("PORKCHOP",          3_000L);
        EXPLICIT.put("COOKED_CHICKEN",    3_000L);
        EXPLICIT.put("CHICKEN",           1_500L);
        EXPLICIT.put("COOKED_MUTTON",     5_000L);
        EXPLICIT.put("MUTTON",            2_500L);
        EXPLICIT.put("COOKED_RABBIT",     5_000L);
        EXPLICIT.put("RABBIT",            2_500L);
        EXPLICIT.put("COOKED_COD",        2_500L);
        EXPLICIT.put("COD",               1_200L);
        EXPLICIT.put("COOKED_SALMON",     3_000L);
        EXPLICIT.put("SALMON",            1_500L);
        EXPLICIT.put("CARROT",              800L);
        EXPLICIT.put("POTATO",              800L);
        EXPLICIT.put("BAKED_POTATO",      1_500L);
        EXPLICIT.put("BEETROOT",            800L);
        EXPLICIT.put("MELON_SLICE",         400L);
        EXPLICIT.put("SWEET_BERRIES",       600L);
        EXPLICIT.put("GLOW_BERRIES",      2_000L);
        EXPLICIT.put("DRIED_KELP",          400L);
        EXPLICIT.put("PUMPKIN_PIE",       4_000L);
        EXPLICIT.put("COOKIE",              800L);
        EXPLICIT.put("CAKE",             15_000L);
        EXPLICIT.put("HONEY_BOTTLE",      3_500L);
        EXPLICIT.put("GOLDEN_CARROT",    25_000L);   // section 20 anchor
        EXPLICIT.put("ENCHANTED_GOLDEN_APPLE", 100_000_000L);
        EXPLICIT.put("CHORUS_FRUIT",      4_000L);
        EXPLICIT.put("POISONOUS_POTATO",    300L);
        EXPLICIT.put("ROTTEN_FLESH",        300L);
        EXPLICIT.put("SPIDER_EYE",        3_000L);
        EXPLICIT.put("PUFFERFISH",        4_000L);
        EXPLICIT.put("TROPICAL_FISH",     3_000L);

        // --- Section 20 anchors ---
        EXPLICIT.put("ARROW",             2_000L);
        EXPLICIT.put("TOTEM_OF_UNDYING", 25_000_000L);
        EXPLICIT.put("TRIDENT",         100_000_000L);
        EXPLICIT.put("ELYTRA",          250_000_000L);
        EXPLICIT.put("NETHER_STAR",     150_000_000L);

        // --- Rare / prestige, where a recipe price would be meaningless ---
        EXPLICIT.put("BEACON",           80_000_000L);
        EXPLICIT.put("CONDUIT",          50_000_000L);
        EXPLICIT.put("HEART_OF_THE_SEA", 40_000_000L);
        EXPLICIT.put("RECOVERY_COMPASS", 30_000_000L);
        EXPLICIT.put("DRAGON_EGG",      500_000_000L);
        EXPLICIT.put("END_CRYSTAL",       6_000_000L);
        EXPLICIT.put("SPONGE",            4_000_000L);
        EXPLICIT.put("WET_SPONGE",        4_000_000L);
        EXPLICIT.put("WITHER_SKELETON_SKULL", 15_000_000L);
        EXPLICIT.put("DRAGON_HEAD",      60_000_000L);
        EXPLICIT.put("NETHERITE_UPGRADE_SMITHING_TEMPLATE", 15_000_000L);

        // --- Utility ---
        EXPLICIT.put("ENDER_CHEST",     600_000L);
        EXPLICIT.put("ENCHANTING_TABLE",900_000L);
        EXPLICIT.put("ANVIL",           500_000L);
        EXPLICIT.put("BREWING_STAND",   150_000L);
        EXPLICIT.put("CAULDRON",        110_000L);
        EXPLICIT.put("HOPPER",          130_000L);
        EXPLICIT.put("SHIELD",           30_000L);
        EXPLICIT.put("SADDLE",           45_000L);
        EXPLICIT.put("NAME_TAG",         60_000L);
        EXPLICIT.put("LEAD",              8_000L);
        EXPLICIT.put("SPYGLASS",         40_000L);
        EXPLICIT.put("BUNDLE",           25_000L);
        EXPLICIT.put("BRUSH",            30_000L);
        EXPLICIT.put("EXPERIENCE_BOTTLE",25_000L);
        EXPLICIT.put("FIREWORK_ROCKET",   3_000L);
        EXPLICIT.put("TNT",              35_000L);
        EXPLICIT.put("SPAWNER",     250_000_000L);
    }

    // ==================================================================
    // Gear derivation
    // ==================================================================

    /** How many units of the tier material each piece consumes. */
    private static int gearUnits(String name) {
        if (name.endsWith("_SWORD"))      return 2;
        if (name.endsWith("_PICKAXE"))    return 3;
        if (name.endsWith("_AXE"))        return 3;
        if (name.endsWith("_SHOVEL"))     return 1;
        if (name.endsWith("_HOE"))        return 2;
        if (name.endsWith("_HELMET"))     return 5;
        if (name.endsWith("_CHESTPLATE")) return 8;
        if (name.endsWith("_LEGGINGS"))   return 7;
        if (name.endsWith("_BOOTS"))      return 4;
        return 0;
    }

    /** Value of one unit of the gear tier, or -1 when this is not gear. */
    private static long gearTierUnit(String name) {
        if (name.startsWith("WOODEN_"))    return RESOURCE.get("OAK_PLANKS");
        if (name.startsWith("STONE_"))     return RESOURCE.get("COBBLESTONE");
        if (name.startsWith("IRON_"))      return RESOURCE.get("IRON_INGOT");
        if (name.startsWith("GOLDEN_"))    return RESOURCE.get("GOLD_INGOT");
        if (name.startsWith("DIAMOND_"))   return RESOURCE.get("DIAMOND");
        if (name.startsWith("LEATHER_"))   return RESOURCE.get("LEATHER");
        if (name.startsWith("CHAINMAIL_")) return RESOURCE.get("IRON_INGOT") * 2;
        if (name.startsWith("TURTLE_"))    return RESOURCE.get("PRISMARINE_SHARD") * 3;
        return -1;
    }

    // ==================================================================
    // Main entry point
    // ==================================================================

    /** Unit value in whole dollars, or -1 when nothing here applies. */
    public static long valueOf(Material material) {
        String n = material.name();

        Long explicit = EXPLICIT.get(n);
        if (explicit != null) return explicit;

        Long resource = RESOURCE.get(n);
        if (resource != null) return resource;

        // --- Netherite gear: the diamond piece plus a netherite ingot ---
        if (n.startsWith("NETHERITE_") && gearUnits(n) > 0) {
            String diamondEquivalent = "DIAMOND_" + n.substring("NETHERITE_".length());
            long base = 0;
            Material diamondPiece = Material.getMaterial(diamondEquivalent);
            if (diamondPiece != null) base = valueOf(diamondPiece);
            return markup(base + RESOURCE.get("NETHERITE_INGOT"));
        }

        // --- Tools and armour, priced from what they are made of ---
        int units = gearUnits(n);
        if (units > 0) {
            long tier = gearTierUnit(n);
            if (tier > 0) {
                long sticks = n.endsWith("_SWORD") || n.endsWith("_PICKAXE")
                        || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                        ? RESOURCE.get("STICK") * 2 : 0;
                return markup(tier * units + sticks);
            }
        }

        // --- Storage blocks: nine of the resource ---
        //
        // The block is named after the metal (IRON_BLOCK) but the resource is
        // keyed by the item (IRON_INGOT), so aliases bridge the two. Without
        // this, iron and gold blocks silently fell through to the fallback.
        if (n.endsWith("_BLOCK")) {
            String base = n.substring(0, n.length() - "_BLOCK".length());
            Long unit = RESOURCE.get(base);
            if (unit == null) unit = RESOURCE.get(BLOCK_ALIASES.getOrDefault(base, base));
            if (unit == null && base.equals("RAW_IRON")) unit = 12_000L;
            if (unit == null && base.equals("RAW_GOLD")) unit = 20_000L;
            if (unit == null && base.equals("RAW_COPPER")) unit = 6_000L;
            if (unit != null) return unit * 9;
        }
        if (n.equals("IRON_NUGGET"))  return Math.max(1, RESOURCE.get("IRON_INGOT") / 9);
        if (n.equals("GOLD_NUGGET"))  return Math.max(1, RESOURCE.get("GOLD_INGOT") / 9);

        // --- Golden food: this is the case the old model got backwards ---
        if (n.equals("GOLDEN_APPLE")) {
            return markup(RESOURCE.get("GOLD_INGOT") * 8 + RESOURCE.get("APPLE"));
        }
        if (n.equals("GLISTERING_MELON_SLICE")) {
            return markup(RESOURCE.get("GOLD_NUGGET") == null ? 25_000L
                    : (RESOURCE.get("GOLD_INGOT") / 9) * 8 + 400L);
        }

        // --- Raw ore drops sit just under the smelted ingot ---
        if (n.startsWith("RAW_")) {
            String metal = n.substring("RAW_".length());
            Long ingot = RESOURCE.get(metal + "_INGOT");
            if (ingot != null) return (long) (ingot * 0.80);
        }
        // --- Ore blocks: the drop plus the effort of mining it ---
        if (n.endsWith("_ORE")) {
            String body = n.replace("DEEPSLATE_", "").replace("NETHER_", "");
            String metal = body.substring(0, body.length() - "_ORE".length());
            Long ingot = RESOURCE.get(metal + "_INGOT");
            if (ingot == null) ingot = RESOURCE.get(metal);
            if (ingot != null) return (long) (ingot * 1.30);
        }

        // --- Wood family, all derived from the log anchor ---
        if (n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) return 1_875L;
        if (n.endsWith("_PLANKS")) return 560L;
        if (n.endsWith("_SLAB"))   return 300L;
        if (n.endsWith("_STAIRS")) return 850L;
        if (n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE")) return 900L;
        if (n.endsWith("_DOOR"))   return 1_200L;
        if (n.endsWith("_TRAPDOOR")) return 1_100L;
        if (n.endsWith("_SIGN"))   return 1_400L;
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT")) return 3_500L;
        if (n.endsWith("_CHEST_BOAT")) return 8_000L;
        if (n.endsWith("_SAPLING")) return 2_000L;
        if (n.endsWith("_LEAVES"))  return 400L;
        if (n.endsWith("_BUTTON"))  return 300L;
        if (n.endsWith("_PRESSURE_PLATE")) return 700L;

        // --- Colour families inherit from their base ---
        if (n.endsWith("_WOOL"))            return 1_200L;
        if (n.endsWith("_CARPET"))          return 800L;
        if (n.endsWith("_CONCRETE"))        return 3_906L;
        if (n.endsWith("_CONCRETE_POWDER")) return 3_300L;
        if (n.endsWith("_TERRACOTTA") && !n.contains("GLAZED")) return 3_438L;
        if (n.endsWith("_GLAZED_TERRACOTTA")) return 5_500L;
        if (n.endsWith("_STAINED_GLASS"))   return 2_700L;
        if (n.endsWith("_STAINED_GLASS_PANE")) return 1_000L;
        if (n.endsWith("_BED"))             return 6_000L;
        if (n.endsWith("_BANNER"))          return 8_000L;
        if (n.endsWith("_CANDLE"))          return 3_500L;
        if (n.endsWith("_DYE"))             return 900L;
        if (n.endsWith("_SHULKER_BOX") || n.equals("SHULKER_BOX")) return 17_000_000L;

        // --- Redstone components ---
        if (n.equals("REPEATER"))    return markup(RESOURCE.get("REDSTONE") * 3 + RESOURCE.get("STONE") * 3 + 400L);
        if (n.equals("COMPARATOR"))  return markup(RESOURCE.get("REDSTONE") * 3 + RESOURCE.get("QUARTZ") + RESOURCE.get("STONE") * 3);
        if (n.equals("PISTON"))      return markup(RESOURCE.get("IRON_INGOT") + RESOURCE.get("REDSTONE") + RESOURCE.get("COBBLESTONE") * 4 + 2_240L);
        if (n.equals("STICKY_PISTON")) return markup(valueOf(Material.PISTON) + RESOURCE.get("SLIME_BALL"));
        if (n.equals("OBSERVER"))    return markup(RESOURCE.get("COBBLESTONE") * 6 + RESOURCE.get("REDSTONE") * 2 + RESOURCE.get("QUARTZ"));
        if (n.equals("DISPENSER") || n.equals("DROPPER")) return 40_000L;
        if (n.equals("RAIL"))        return 3_000L;
        if (n.equals("POWERED_RAIL") || n.equals("ACTIVATOR_RAIL")) return 14_000L;
        if (n.equals("DETECTOR_RAIL")) return 12_000L;
        if (n.equals("MINECART"))    return markup(RESOURCE.get("IRON_INGOT") * 5);
        if (n.endsWith("_MINECART"))  return 120_000L;

        // --- Buckets and glass ---
        if (n.equals("BUCKET"))      return markup(RESOURCE.get("IRON_INGOT") * 3);
        if (n.endsWith("_BUCKET"))   return 50_000L;
        if (n.equals("GLASS_BOTTLE")) return 2_500L;
        if (n.equals("GLASS_PANE"))  return 900L;

        // --- Collectibles ---
        if (n.startsWith("MUSIC_DISC")) return 6_000_000L;
        if (n.endsWith("_SMITHING_TEMPLATE")) return 4_000_000L;
        if (n.endsWith("_POTTERY_SHERD"))     return 800_000L;
        if (n.endsWith("_SKULL") || n.endsWith("_HEAD")) return 5_000_000L;
        if (n.endsWith("_SPAWN_EGG")) return 25_000_000L;

        return -1;
    }

    private static long markup(long base) {
        return Math.max(1L, (long) (base * CRAFT_MARKUP));
    }

    /**
     * Fraction of the buy price the server pays back.
     * Farmable goods get less, so an automated farm cannot outrun the economy.
     */
    public static double buybackRatio(Material m) {
        String n = m.name();
        if (n.contains("COBBLESTONE") || n.equals("STONE") || n.contains("BAMBOO")
                || n.contains("SUGAR_CANE") || n.contains("KELP") || n.contains("PUMPKIN")
                || n.contains("MELON") || n.contains("CACTUS") || n.equals("WHEAT")
                || n.contains("ROTTEN_FLESH") || n.equals("EGG")) {
            return 0.08;
        }
        if (n.endsWith("_INGOT") || n.contains("GUNPOWDER") || n.contains("SLIME")
                || n.equals("BONE") || n.equals("STRING") || n.equals("ARROW")) {
            return 0.18;
        }
        if (n.equals("DIAMOND") || n.equals("NETHERITE_INGOT")
                || n.equals("ANCIENT_DEBRIS")) {
            return 0.22;
        }
        return 0.15;
    }

    public static String pretty(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
