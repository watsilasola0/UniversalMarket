package com.sola.universalmarket.catalog;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Set;

/**
 * The ten browsing categories, mirroring vanilla's creative tabs.
 *
 * ON ORDERING: items are sorted by Material.ordinal(), which follows the Bukkit
 * registry order. That is very close to vanilla creative order for most tabs -
 * grass block leads Natural Blocks, wool leads Colored Blocks - but it is an
 * approximation, not a byte-for-byte match. The exact creative ordering lives in
 * the client's own CreativeModeTabs class and is not exposed to the server, so
 * no plugin can reproduce it perfectly. In practice the difference shows up as a
 * few items sitting a row earlier or later than you would expect.
 */
public enum ItemCategory {

    BUILDING_BLOCKS ("Building Blocks",  Material.BRICKS,          "<gold>"),
    COLORED_BLOCKS  ("Colored Blocks",   Material.CYAN_WOOL,       "<aqua>"),
    NATURAL_BLOCKS  ("Natural Blocks",   Material.GRASS_BLOCK,     "<green>"),
    FUNCTIONAL      ("Functional Blocks",Material.OAK_SIGN,        "<yellow>"),
    REDSTONE        ("Redstone Blocks",  Material.REDSTONE,        "<red>"),
    TOOLS           ("Tools & Utilities",Material.DIAMOND_PICKAXE, "<white>"),
    COMBAT          ("Combat",           Material.IRON_SWORD,      "<red>"),
    FOOD            ("Food & Drinks",    Material.GOLDEN_APPLE,    "<gold>"),
    INGREDIENTS     ("Ingredients",      Material.IRON_INGOT,      "<white>"),
    SPAWN_EGGS      ("Spawn Eggs",       Material.CREEPER_SPAWN_EGG, "<light_purple>");

    private final String displayName;
    private final Material icon;
    private final String colour;

    ItemCategory(String displayName, Material icon, String colour) {
        this.displayName = displayName;
        this.icon = icon;
        this.colour = colour;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String colour() { return colour; }
    public String coloured() { return colour + displayName; }

    // ==================================================================
    // Safe spawn eggs
    // ==================================================================

    /**
     * Passive, non-exploitable mobs only.
     *
     * Deliberately excluded, and not by accident:
     *   VILLAGER / WANDERING_TRADER - trading would let players mint emeralds
     *                                 and buy their way around the whole market
     *   IRON_GOLEM                  - an iron farm in a single item
     *   SNOW_GOLEM                  - trivial infinite snow
     *   all hostile mobs            - mob farms, XP farms, drop farms
     *
     * Everything here is decorative or a modest food/wool source, which is why
     * it is safe to sell even at a high price.
     */
    public static final Set<String> ALLOWED_SPAWN_EGGS = Set.of(
            "CHICKEN_SPAWN_EGG", "PIG_SPAWN_EGG", "COW_SPAWN_EGG", "SHEEP_SPAWN_EGG",
            "RABBIT_SPAWN_EGG", "CAT_SPAWN_EGG", "OCELOT_SPAWN_EGG", "PARROT_SPAWN_EGG",
            "WOLF_SPAWN_EGG", "FOX_SPAWN_EGG", "HORSE_SPAWN_EGG", "DONKEY_SPAWN_EGG",
            "MULE_SPAWN_EGG", "LLAMA_SPAWN_EGG", "TRADER_LLAMA_SPAWN_EGG",
            "MOOSHROOM_SPAWN_EGG", "PANDA_SPAWN_EGG", "POLAR_BEAR_SPAWN_EGG",
            "GOAT_SPAWN_EGG", "CAMEL_SPAWN_EGG", "SNIFFER_SPAWN_EGG",
            "ARMADILLO_SPAWN_EGG", "FROG_SPAWN_EGG", "TADPOLE_SPAWN_EGG",
            "AXOLOTL_SPAWN_EGG", "ALLAY_SPAWN_EGG", "BEE_SPAWN_EGG",
            "TURTLE_SPAWN_EGG", "STRIDER_SPAWN_EGG", "BAT_SPAWN_EGG",
            "COD_SPAWN_EGG", "SALMON_SPAWN_EGG", "TROPICAL_FISH_SPAWN_EGG",
            "PUFFERFISH_SPAWN_EGG", "SQUID_SPAWN_EGG", "GLOW_SQUID_SPAWN_EGG"
    );

    public static boolean isAllowedSpawnEgg(Material material) {
        return ALLOWED_SPAWN_EGGS.contains(material.name());
    }

    // ==================================================================
    // Classification
    // ==================================================================

    /**
     * Put a material in a tab. Order of the checks matters: the first match
     * wins, so the most specific tests come first.
     */
    public static ItemCategory classify(Material m) {
        String n = m.name();

        if (n.endsWith("_SPAWN_EGG")) return SPAWN_EGGS;

        // ---- Colored blocks: anything that exists in 16 dye variants ----
        if (n.endsWith("_WOOL") || n.endsWith("_CARPET")
                || n.endsWith("_TERRACOTTA") || n.equals("TERRACOTTA")
                || n.endsWith("_CONCRETE") || n.endsWith("_CONCRETE_POWDER")
                || n.endsWith("_STAINED_GLASS") || n.endsWith("_STAINED_GLASS_PANE")
                || n.endsWith("_SHULKER_BOX") || n.equals("SHULKER_BOX")
                || n.endsWith("_BED") || n.endsWith("_BANNER")
                || n.endsWith("_CANDLE") || n.endsWith("_GLAZED_TERRACOTTA")) {
            return COLORED_BLOCKS;
        }

        // ---- Redstone ----
        if (n.equals("REDSTONE") || n.equals("REDSTONE_BLOCK") || n.equals("REDSTONE_TORCH")
                || n.equals("REDSTONE_LAMP") || n.equals("REPEATER") || n.equals("COMPARATOR")
                || n.equals("OBSERVER") || n.equals("PISTON") || n.equals("STICKY_PISTON")
                || n.equals("SLIME_BLOCK") || n.equals("HONEY_BLOCK") || n.equals("HOPPER")
                || n.equals("DROPPER") || n.equals("DISPENSER") || n.equals("LEVER")
                || n.equals("TNT") || n.equals("TARGET") || n.equals("DAYLIGHT_DETECTOR")
                || n.equals("TRIPWIRE_HOOK") || n.equals("LECTERN") || n.equals("CRAFTER")
                || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
                || n.endsWith("_RAIL") || n.equals("RAIL")) {
            return REDSTONE;
        }

        // ---- Combat ----
        if (n.endsWith("_SWORD") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("ARROW")
                || n.equals("SPECTRAL_ARROW") || n.equals("TIPPED_ARROW")
                || n.equals("SHIELD") || n.equals("TRIDENT") || n.equals("TOTEM_OF_UNDYING")
                || n.endsWith("_HORSE_ARMOR") || n.equals("WOLF_ARMOR")
                || n.equals("SPLASH_POTION") || n.equals("LINGERING_POTION")
                || n.equals("FIRE_CHARGE") || n.equals("WIND_CHARGE")
                || n.equals("MACE") || n.equals("SHULKER_SHELL")) {
            return COMBAT;
        }

        // ---- Tools & utilities ----
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")
                || n.equals("FISHING_ROD") || n.equals("COMPASS") || n.equals("RECOVERY_COMPASS")
                || n.equals("CLOCK") || n.equals("SPYGLASS") || n.equals("MAP")
                || n.equals("LEAD") || n.equals("NAME_TAG") || n.equals("SADDLE")
                || n.equals("ELYTRA") || n.equals("BRUSH") || n.equals("BUNDLE")
                || n.endsWith("_BUCKET") || n.equals("BUCKET")
                || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("_CHEST_BOAT")
                || n.endsWith("_MINECART") || n.equals("MINECART")
                || n.startsWith("MUSIC_DISC") || n.equals("GOAT_HORN")
                || n.endsWith("_SKULL") || n.endsWith("_HEAD")
                || n.equals("FIREWORK_ROCKET")) {
            return TOOLS;
        }

        // ---- Food & drinks ----
        if (m.isEdible() || n.equals("POTION") || n.equals("MILK_BUCKET")
                || n.equals("CAKE") || n.equals("HONEY_BOTTLE")) {
            return FOOD;
        }

        // ---- Natural blocks ----
        if (n.endsWith("_ORE") || n.startsWith("RAW_") && n.endsWith("_BLOCK")
                || n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM")
                || n.endsWith("_HYPHAE") || n.endsWith("_LEAVES") || n.endsWith("_SAPLING")
                || n.equals("DIRT") || n.equals("COARSE_DIRT") || n.equals("ROOTED_DIRT")
                || n.equals("GRASS_BLOCK") || n.equals("PODZOL") || n.equals("MYCELIUM")
                || n.equals("SAND") || n.equals("RED_SAND") || n.equals("GRAVEL")
                || n.equals("CLAY") || n.equals("STONE") || n.equals("DEEPSLATE")
                || n.equals("GRANITE") || n.equals("DIORITE") || n.equals("ANDESITE")
                || n.equals("TUFF") || n.equals("CALCITE") || n.equals("OBSIDIAN")
                || n.equals("NETHERRACK") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL")
                || n.equals("END_STONE") || n.equals("SNOW") || n.equals("SNOW_BLOCK")
                || n.equals("ICE") || n.equals("PACKED_ICE") || n.equals("BLUE_ICE")
                || n.contains("CORAL") || n.contains("SCULK") || n.contains("AMETHYST")
                || n.contains("MOSS") || n.contains("MUSHROOM") || n.contains("FUNGUS")
                || n.contains("SPONGE") || n.contains("MAGMA") || n.equals("BONE_BLOCK")
                || n.equals("CACTUS") || n.equals("BAMBOO") || n.equals("SUGAR_CANE")
                || n.equals("VINE") || n.equals("LILY_PAD") || n.equals("SEAGRASS")
                || n.equals("KELP") || n.equals("DRIED_KELP_BLOCK")) {
            return NATURAL_BLOCKS;
        }

        // ---- Functional blocks ----
        if (n.equals("CRAFTING_TABLE") || n.equals("FURNACE") || n.equals("BLAST_FURNACE")
                || n.equals("SMOKER") || n.equals("CHEST") || n.equals("TRAPPED_CHEST")
                || n.equals("BARREL") || n.equals("ENDER_CHEST") || n.equals("ANVIL")
                || n.equals("ENCHANTING_TABLE") || n.equals("BREWING_STAND")
                || n.equals("CAULDRON") || n.equals("BOOKSHELF") || n.equals("LADDER")
                || n.equals("SCAFFOLDING") || n.equals("JUKEBOX") || n.equals("NOTE_BLOCK")
                || n.equals("BEACON") || n.equals("CONDUIT") || n.equals("LODESTONE")
                || n.equals("RESPAWN_ANCHOR") || n.equals("GRINDSTONE") || n.equals("LOOM")
                || n.equals("SMITHING_TABLE") || n.equals("STONECUTTER") || n.equals("CARTOGRAPHY_TABLE")
                || n.equals("FLETCHING_TABLE") || n.equals("COMPOSTER") || n.equals("BELL")
                || n.equals("SPAWNER") || n.equals("GLASS") || n.equals("GLASS_PANE")
                || n.equals("TORCH") || n.equals("SOUL_TORCH") || n.equals("LANTERN")
                || n.equals("SOUL_LANTERN") || n.equals("CANDLE") || n.equals("CAMPFIRE")
                || n.equals("SOUL_CAMPFIRE") || n.equals("SEA_LANTERN") || n.equals("GLOWSTONE")
                || n.equals("SHROOMLIGHT") || n.equals("END_ROD") || n.equals("ITEM_FRAME")
                || n.equals("GLOW_ITEM_FRAME") || n.equals("ARMOR_STAND") || n.equals("FLOWER_POT")
                || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_FENCE")
                || n.endsWith("_FENCE_GATE") || n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN")) {
            return FUNCTIONAL;
        }

        // ---- Ingredients: raw materials and crafting components ----
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.endsWith("_DYE")
                || n.startsWith("RAW_") || n.equals("STICK") || n.equals("STRING")
                || n.equals("GUNPOWDER") || n.equals("BLAZE_ROD") || n.equals("BLAZE_POWDER")
                || n.equals("DIAMOND") || n.equals("EMERALD") || n.equals("LAPIS_LAZULI")
                || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD") || n.equals("COAL")
                || n.equals("CHARCOAL") || n.equals("FLINT") || n.equals("LEATHER")
                || n.equals("FEATHER") || n.equals("BONE") || n.equals("BONE_MEAL")
                || n.equals("SLIME_BALL") || n.equals("ENDER_PEARL") || n.equals("ENDER_EYE")
                || n.equals("GHAST_TEAR") || n.equals("NETHER_STAR") || n.equals("PAPER")
                || n.equals("BOOK") || n.equals("ENCHANTED_BOOK") || n.equals("WRITABLE_BOOK")
                || n.equals("CLAY_BALL") || n.equals("BRICK") || n.equals("NETHER_BRICK")
                || n.equals("PRISMARINE_SHARD") || n.equals("PRISMARINE_CRYSTALS")
                || n.equals("NAUTILUS_SHELL") || n.equals("HEART_OF_THE_SEA")
                || n.equals("PHANTOM_MEMBRANE") || n.equals("RABBIT_HIDE")
                || n.equals("NETHERITE_SCRAP") || n.equals("ANCIENT_DEBRIS")
                || n.equals("ECHO_SHARD") || n.equals("DISC_FRAGMENT_5")
                || n.endsWith("_SMITHING_TEMPLATE") || n.endsWith("_POTTERY_SHERD")
                || n.equals("EXPERIENCE_BOTTLE") || n.equals("GLASS_BOTTLE")
                || n.equals("BOWL") || n.equals("WHEAT") || n.endsWith("_SEEDS")) {
            return INGREDIENTS;
        }

        // Everything left that is a block is a building block; the rest are
        // ingredients. This keeps the tabs exhaustive so nothing is unreachable.
        return m.isBlock() ? BUILDING_BLOCKS : INGREDIENTS;
    }

    // ==================================================================
    // Ordering
    // ==================================================================

    /**
     * Sort key approximating vanilla creative tab order.
     *
     * Registry order alone is not close enough: in Combat it interleaves swords,
     * axes and armour instead of grouping them the way the real tab does. This
     * sorts by GROUP first (all swords, then all axes, then armour...), then by
     * material tier within the group, then by armour piece, and finally falls
     * back to registry order for anything unrecognised.
     *
     * It is an approximation. The authoritative ordering lives in the client's
     * CreativeModeTabs class, which the server cannot read.
     */
    public static long sortKey(Material m) {
        String n = m.name();
        long group = groupOf(n);
        long tier = tierOf(n);
        long piece = pieceOf(n);
        return group * 10_000_000L + tier * 100_000L + piece * 1_000L + m.ordinal();
    }

    private static long groupOf(String n) {
        // Combat, in the order the vanilla tab presents them.
        if (n.endsWith("_SWORD"))                     return 1;
        if (n.endsWith("_AXE") && !n.endsWith("_PICKAXE")) return 2;
        if (n.equals("TRIDENT"))                      return 3;
        if (n.equals("MACE"))                         return 4;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) return 5;
        if (n.endsWith("_HORSE_ARMOR") || n.equals("WOLF_ARMOR")) return 6;
        if (n.equals("TOTEM_OF_UNDYING") || n.equals("SHIELD")) return 7;
        if (n.equals("BOW") || n.equals("CROSSBOW"))  return 8;
        if (n.equals("ARROW") || n.equals("SPECTRAL_ARROW")) return 9;
        if (n.equals("TIPPED_ARROW"))                 return 10;
        if (n.endsWith("POTION"))                     return 11;

        // Tools, likewise grouped by kind.
        if (n.endsWith("_PICKAXE"))                   return 20;
        if (n.endsWith("_SHOVEL"))                    return 21;
        if (n.endsWith("_HOE"))                       return 22;
        if (n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")) return 23;
        if (n.endsWith("_BUCKET") || n.equals("BUCKET")) return 24;
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("_CHEST_BOAT")) return 25;
        if (n.endsWith("_MINECART") || n.equals("MINECART")) return 26;
        if (n.startsWith("MUSIC_DISC"))               return 27;

        // Ingredients: raw materials before processed ones.
        if (n.endsWith("_INGOT"))                     return 40;
        if (n.endsWith("_NUGGET"))                    return 41;
        if (n.startsWith("RAW_"))                     return 42;
        if (n.endsWith("_DYE"))                       return 43;
        if (n.equals("ENCHANTED_BOOK"))               return 44;
        if (n.endsWith("_SMITHING_TEMPLATE"))         return 45;

        return 60;
    }

    /** Material tier, weakest first, matching how vanilla lists gear. */
    private static long tierOf(String n) {
        if (n.startsWith("WOODEN_"))    return 1;
        if (n.startsWith("STONE_"))     return 2;
        if (n.startsWith("IRON_"))      return 3;
        if (n.startsWith("GOLDEN_"))    return 4;
        if (n.startsWith("DIAMOND_"))   return 5;
        if (n.startsWith("NETHERITE_")) return 6;
        if (n.startsWith("LEATHER_"))   return 0;
        if (n.startsWith("CHAINMAIL_")) return 2;
        if (n.startsWith("TURTLE_"))    return 7;
        return 50;
    }

    /** Armour piece order: helmet, chestplate, leggings, boots. */
    private static long pieceOf(String n) {
        if (n.endsWith("_HELMET"))     return 1;
        if (n.endsWith("_CHESTPLATE")) return 2;
        if (n.endsWith("_LEGGINGS"))   return 3;
        if (n.endsWith("_BOOTS"))      return 4;
        return 50;
    }

    public static ItemCategory byName(String raw) {
        for (ItemCategory c : values()) {
            if (c.name().equalsIgnoreCase(raw)
                    || c.displayName.equalsIgnoreCase(raw)) return c;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
