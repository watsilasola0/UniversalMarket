package com.sola.universalmarket.catalog;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds the default market catalog once, on first start, and writes it to
 * market.yml. After that the YAML is the source of truth and this class is never
 * consulted again - so you can retune any price without touching Java.
 *
 * PRICING METHOD (spec section 56):
 *
 *   1. Every anchor you gave in section 20 is applied verbatim. Those are the
 *      fixed points the whole economy is calibrated against.
 *   2. Everything else is priced by rule: a base value for its family, then
 *      multipliers for dimension (nether/end), renewability, and effort.
 *   3. A handful of items are force-overridden because a recipe-derived price
 *      would be nonsense - Elytra, Mending, Nether Star, Trident, Enchanted
 *      Golden Apple, netherite templates.
 *
 *   Nothing here is random. Two items in the same family with the same
 *   multipliers get the same price, which makes the result predictable to tune.
 *
 * STACK vs UNIT:
 *
 *   Your section 20 anchors mix stack prices (64 Dirt = $20K) and unit prices
 *   (Iron Ingot = $15K). Everything is stored internally as a UNIT price. So
 *   64 Dirt at $20K becomes $312 each; the UI multiplies back up when showing
 *   stack prices.
 */
public final class CatalogGenerator {

    private final Logger log;

    public CatalogGenerator(Logger log) {
        this.log = log;
    }

    // ==================================================================
    // Anchors from spec section 20
    // ==================================================================

    /** Anchors expressed per 64. value = {umStackPrice, buybackStackPrice} */
    private static final Map<String, long[]> STACK_ANCHORS = new LinkedHashMap<>();
    /** Anchors expressed per single item. value = {umUnitPrice, buybackUnitPrice} */
    private static final Map<String, long[]> UNIT_ANCHORS = new LinkedHashMap<>();

    static {
        STACK_ANCHORS.put("DIRT",          new long[]{ 20_000,   2_000});
        STACK_ANCHORS.put("COBBLESTONE",   new long[]{ 25_000,   2_500});
        STACK_ANCHORS.put("STONE",         new long[]{ 35_000,   4_000});
        STACK_ANCHORS.put("GRAVEL",        new long[]{ 70_000,   9_000});
        STACK_ANCHORS.put("SAND",          new long[]{ 80_000,  10_000});
        STACK_ANCHORS.put("OAK_LOG",       new long[]{120_000,  18_000});
        STACK_ANCHORS.put("GLASS",         new long[]{150_000,  22_000});
        STACK_ANCHORS.put("WHITE_CONCRETE",new long[]{250_000,  40_000});
        STACK_ANCHORS.put("TERRACOTTA",    new long[]{220_000,  35_000});
        STACK_ANCHORS.put("QUARTZ_BLOCK",  new long[]{750_000, 120_000});

        UNIT_ANCHORS.put("IRON_INGOT",            new long[]{     15_000,      3_000});
        UNIT_ANCHORS.put("COPPER_INGOT",          new long[]{      8_000,      1_000});
        UNIT_ANCHORS.put("GOLD_INGOT",            new long[]{     25_000,      5_000});
        UNIT_ANCHORS.put("REDSTONE",              new long[]{      4_000,        500});
        UNIT_ANCHORS.put("EMERALD",               new long[]{    150_000,     20_000});
        UNIT_ANCHORS.put("DIAMOND",               new long[]{    250_000,     50_000});
        UNIT_ANCHORS.put("ANCIENT_DEBRIS",        new long[]{  1_000_000,    250_000});
        UNIT_ANCHORS.put("NETHERITE_INGOT",       new long[]{  5_000_000,  1_200_000});
        UNIT_ANCHORS.put("COOKED_BEEF",           new long[]{      7_000,        800});
        UNIT_ANCHORS.put("GOLDEN_CARROT",         new long[]{     25_000,      3_000});
        UNIT_ANCHORS.put("ARROW",                 new long[]{      2_000,        200});
        UNIT_ANCHORS.put("TOTEM_OF_UNDYING",      new long[]{ 25_000_000,  6_000_000});
        UNIT_ANCHORS.put("TRIDENT",               new long[]{100_000_000, 20_000_000});
        UNIT_ANCHORS.put("NETHER_STAR",           new long[]{150_000_000, 35_000_000});
        UNIT_ANCHORS.put("ELYTRA",                new long[]{250_000_000, 50_000_000});
        UNIT_ANCHORS.put("ENCHANTED_GOLDEN_APPLE",new long[]{100_000_000, 20_000_000});
    }

    // ==================================================================
    // Blacklist - things not legitimately obtainable in vanilla survival
    // ==================================================================

    private static final Set<String> BLACKLIST = Set.of(
            // Admin / creative only
            "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID",
            "JIGSAW", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "DEBUG_STICK", "KNOWLEDGE_BOOK",
            "REINFORCED_DEEPSLATE",
            // Spawners are not obtainable as items in survival
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "CREAKING_HEART",
            // Portal / world structure blocks
            "END_PORTAL_FRAME", "BUDDING_AMETHYST", "DRAGON_EGG",
            // Not obtainable even with silk touch
            "DIRT_PATH", "FARMLAND", "FROGSPAWN",
            "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL",
            "PETRIFIED_OAK_SLAB",
            // Component-carrying items that would let players smuggle NBT in
            "PLAYER_HEAD", "FILLED_MAP", "WRITTEN_BOOK", "SUSPICIOUS_STEW",
            "FIREWORK_STAR", "BUNDLE", "OMINOUS_BOTTLE",
            // Base enchanted book has no meaning; only variants are sold
            "ENCHANTED_BOOK",
            // Base potions likewise
            "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW", "GOAT_HORN"
    );

    /** Overrides applied after heuristics, for things a rule would misprice. */
    private static final Map<String, long[]> FORCED_OVERRIDES = new LinkedHashMap<>();
    static {
        FORCED_OVERRIDES.put("NETHERITE_UPGRADE_SMITHING_TEMPLATE", new long[]{15_000_000, 3_500_000});
        FORCED_OVERRIDES.put("HEART_OF_THE_SEA",                    new long[]{40_000_000, 9_000_000});
        FORCED_OVERRIDES.put("NAUTILUS_SHELL",                      new long[]{ 3_000_000,   700_000});
        FORCED_OVERRIDES.put("SHULKER_SHELL",                       new long[]{ 8_000_000, 1_800_000});
        FORCED_OVERRIDES.put("DRAGON_BREATH",                       new long[]{ 2_000_000,   450_000});
        FORCED_OVERRIDES.put("END_CRYSTAL",                         new long[]{ 6_000_000, 1_400_000});
        FORCED_OVERRIDES.put("BEACON",                              new long[]{80_000_000,18_000_000});
        FORCED_OVERRIDES.put("CONDUIT",                             new long[]{50_000_000,11_000_000});
        FORCED_OVERRIDES.put("SPONGE",                              new long[]{ 4_000_000,   900_000});
        FORCED_OVERRIDES.put("WET_SPONGE",                          new long[]{ 4_000_000,   900_000});
        FORCED_OVERRIDES.put("ECHO_SHARD",                          new long[]{ 5_000_000, 1_100_000});
        FORCED_OVERRIDES.put("RECOVERY_COMPASS",                    new long[]{30_000_000, 7_000_000});
        FORCED_OVERRIDES.put("WITHER_SKELETON_SKULL",               new long[]{15_000_000, 3_500_000});
        FORCED_OVERRIDES.put("DRAGON_HEAD",                         new long[]{60_000_000,14_000_000});
        FORCED_OVERRIDES.put("ELYTRA",                              new long[]{250_000_000,50_000_000});
    }

    /** Rare goods: {perPlayerLimit}. Reset window comes from config. */
    private static final Map<String, Integer> RARE_LIMITS = Map.of(
            "ELYTRA", 1,
            "TRIDENT", 1,
            "NETHER_STAR", 2,
            "ENCHANTED_GOLDEN_APPLE", 4,
            "TOTEM_OF_UNDYING", 4,
            "BEACON", 1,
            "CONDUIT", 1,
            "NETHERITE_UPGRADE_SMITHING_TEMPLATE", 2
    );

    // ==================================================================
    // Generation
    // ==================================================================

    public List<MarketItem> generate() {
        List<MarketItem> out = new ArrayList<>(1500);
        int skipped = 0;

        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isItem() || material == Material.AIR) { skipped++; continue; }
            String name = material.name();
            if (BLACKLIST.contains(name)) { skipped++; continue; }
            if (name.endsWith("_SPAWN_EGG")) { skipped++; continue; }
            if (name.startsWith("INFESTED_")) { skipped++; continue; }
            if (name.startsWith("POTTED_")) { skipped++; continue; }

            long[] prices = priceFor(material);
            if (prices[0] <= 0) { skipped++; continue; }
            out.add(build(MarketItem.Key.of(material), pretty(name), categoryOf(material), prices, name));
        }

        out.addAll(generatePotionVariants());
        out.addAll(generateEnchantedBooks());
        out.addAll(generateGoatHorns());

        log.info("Catalog generated: " + out.size() + " approved entries ("
                + skipped + " materials excluded as non-survival or blacklisted)");
        return out;
    }

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    private List<MarketItem> generatePotionVariants() {
        List<MarketItem> out = new ArrayList<>();
        // Base potion types that cannot be brewed into anything meaningful.
        Set<String> skip = Set.of("WATER", "MUNDANE", "THICK", "AWKWARD", "UNCRAFTABLE");
        try {
            for (PotionType type : PotionType.values()) {
                if (skip.contains(type.name())) continue;
                String variant = type.name().toLowerCase(Locale.ROOT);
                long base = potionBase(type.name());

                add(out, Material.POTION,          variant, base,            "potions");
                add(out, Material.SPLASH_POTION,   variant, (long)(base*1.35), "potions");
                add(out, Material.LINGERING_POTION,variant, (long)(base*1.9),  "potions");
                add(out, Material.TIPPED_ARROW,    variant, (long)(base*0.12), "combat");
            }
        } catch (Throwable t) {
            log.warning("Could not enumerate PotionType, skipping potion variants: " + t);
        }
        return out;
    }

    private long potionBase(String name) {
        long base = 45_000L;
        if (name.contains("STRENGTH") || name.contains("REGENERATION")) base = 120_000L;
        if (name.contains("HEALING") || name.contains("HARMING"))       base = 90_000L;
        if (name.contains("INVISIBILITY") || name.contains("LEAPING"))  base = 80_000L;
        if (name.contains("TURTLE") || name.contains("SLOW_FALLING"))   base = 150_000L;
        if (name.contains("LUCK"))                                      base = 400_000L;
        if (name.startsWith("LONG_"))   base = (long) (base * 1.6);
        if (name.startsWith("STRONG_")) base = (long) (base * 2.1);
        return base;
    }

    private List<MarketItem> generateEnchantedBooks() {
        List<MarketItem> out = new ArrayList<>();
        try {
            for (Enchantment ench : Registry.ENCHANTMENT) {
                NamespacedKey key = ench.getKey();
                String id = key.getKey().toLowerCase(Locale.ROOT);
                int max = Math.max(1, ench.getMaxLevel());
                for (int level = 1; level <= max; level++) {
                    long price = enchantBase(id);
                    // Each level roughly doubles the value.
                    price = (long) (price * Math.pow(1.9, level - 1));
                    add(out, Material.ENCHANTED_BOOK, id + "_" + level, price, "enchanted_books");
                }
            }
        } catch (Throwable t) {
            log.warning("Could not enumerate enchantment registry, skipping enchanted books: " + t);
        }
        return out;
    }

    private long enchantBase(String id) {
        // Mending is explicitly anchored at $20M in spec section 20.
        if (id.equals("mending")) return 20_000_000L;
        if (id.equals("silk_touch")) return 6_000_000L;
        if (id.equals("infinity")) return 8_000_000L;
        if (id.equals("fortune") || id.equals("looting")) return 2_500_000L;
        if (id.equals("efficiency") || id.equals("unbreaking")) return 900_000L;
        if (id.equals("sharpness") || id.equals("protection")) return 800_000L;
        if (id.equals("swift_sneak") || id.equals("soul_speed")) return 4_000_000L;
        if (id.contains("curse")) return 150_000L;
        return 500_000L;
    }

    private List<MarketItem> generateGoatHorns() {
        List<MarketItem> out = new ArrayList<>();
        try {
            for (org.bukkit.MusicInstrument instrument : Registry.INSTRUMENT) {
                String id = instrument.getKey().getKey().toLowerCase(Locale.ROOT);
                add(out, Material.GOAT_HORN, id, 2_000_000L, "collectibles");
            }
        } catch (Throwable t) {
            log.warning("Could not enumerate instrument registry, skipping goat horns: " + t);
        }
        return out;
    }

    private void add(List<MarketItem> out, Material material, String variant, long umPrice, String category) {
        long buyback = Math.max(1L, (long) (umPrice * 0.18));
        out.add(build(MarketItem.Key.of(material, variant),
                pretty(material.name()) + " (" + pretty(variant) + ")",
                category, new long[]{umPrice, buyback}, material.name()));
    }

    // ------------------------------------------------------------------
    // Heuristic pricing
    // ------------------------------------------------------------------

    /** Returns {umUnitPrice, buybackUnitPrice}, both whole dollars. */
    private long[] priceFor(Material material) {
        String n = material.name();

        long[] forced = FORCED_OVERRIDES.get(n);
        if (forced != null) return forced;

        long[] unit = UNIT_ANCHORS.get(n);
        if (unit != null) return unit;

        long[] stack = STACK_ANCHORS.get(n);
        if (stack != null) {
            return new long[]{ divRound(stack[0], 64), divRound(stack[1], 64) };
        }

        // Family-derived anchors: colour and wood variants inherit their base.
        if (n.endsWith("_CONCRETE"))        return unitFromStack("WHITE_CONCRETE");
        if (n.endsWith("_CONCRETE_POWDER")) return scale(unitFromStack("WHITE_CONCRETE"), 0.85);
        if (n.endsWith("_TERRACOTTA"))      return unitFromStack("TERRACOTTA");
        if (n.endsWith("_GLAZED_TERRACOTTA")) return scale(unitFromStack("TERRACOTTA"), 1.6);
        if (n.endsWith("_STAINED_GLASS") || n.endsWith("_STAINED_GLASS_PANE"))
            return scale(unitFromStack("GLASS"), 1.15);
        if (n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("_STEM") || n.endsWith("_HYPHAE"))
            return unitFromStack("OAK_LOG");
        if (n.endsWith("_PLANKS"))          return scale(unitFromStack("OAK_LOG"), 0.30);
        if (n.endsWith("_WOOL"))            return scale(unitFromStack("STONE"), 2.2);
        if (n.endsWith("_CARPET"))          return scale(unitFromStack("STONE"), 1.3);

        long base = familyBase(material);
        base = (long) (base * dimensionMultiplier(n));
        long buyback = (long) (base * buybackRatio(material));
        return new long[]{ Math.max(1L, base), Math.max(1L, buyback) };
    }

    private long[] unitFromStack(String anchorName) {
        long[] s = STACK_ANCHORS.get(anchorName);
        return new long[]{ divRound(s[0], 64), divRound(s[1], 64) };
    }

    private long[] scale(long[] in, double factor) {
        return new long[]{ Math.max(1L, (long)(in[0]*factor)), Math.max(1L, (long)(in[1]*factor)) };
    }

    private long divRound(long value, long by) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(by), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Base unit price by item family, calibrated so it lands between the anchors. */
    private long familyBase(Material m) {
        String n = m.name();

        // Tools, weapons and armour, keyed off their material tier.
        if (n.startsWith("NETHERITE_")) return 9_000_000L;
        if (n.startsWith("DIAMOND_"))   return   900_000L;
        if (n.startsWith("GOLDEN_") && isGear(n))  return 120_000L;
        if (n.startsWith("IRON_") && isGear(n))    return 140_000L;
        if (n.startsWith("STONE_") && isGear(n))   return  12_000L;
        if (n.startsWith("WOODEN_"))    return     6_000L;
        if (n.startsWith("CHAINMAIL_")) return   180_000L;
        if (n.startsWith("LEATHER_"))   return    25_000L;

        // Ores and raw materials
        if (n.startsWith("RAW_"))          return 12_000L;
        if (n.endsWith("_ORE"))            return 30_000L;
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET")) return 15_000L;

        // Redstone and transport
        if (n.contains("RAIL"))            return  14_000L;
        if (n.endsWith("_MINECART") || n.equals("MINECART")) return 90_000L;
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT")) return 40_000L;
        if (n.contains("PISTON") || n.contains("OBSERVER")
                || n.contains("COMPARATOR") || n.contains("REPEATER")
                || n.contains("HOPPER") || n.contains("DISPENSER")
                || n.contains("DROPPER")) return 45_000L;

        // Collectibles
        if (n.startsWith("MUSIC_DISC")) return 6_000_000L;
        if (n.endsWith("_SMITHING_TEMPLATE") || n.endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE"))
            return 4_000_000L;
        if (n.endsWith("_SKULL") || n.endsWith("_HEAD")) return 5_000_000L;
        if (n.endsWith("_SHERD")) return 800_000L;

        // Food and farming
        if (n.startsWith("COOKED_")) return 7_000L;
        if (n.endsWith("_SEEDS") || n.endsWith("_SAPLING")) return 3_000L;

        // Bulk natural blocks
        if (n.endsWith("_SLAB"))   return 300L;
        if (n.endsWith("_STAIRS")) return 600L;
        if (n.endsWith("_WALL") || n.endsWith("_FENCE")) return 700L;
        if (n.endsWith("_DEEPSLATE") || n.startsWith("DEEPSLATE")) return 700L;
        if (n.contains("SANDSTONE") || n.contains("PRISMARINE")
                || n.contains("BRICK") || n.contains("COPPER")) return 1_500L;

        // Sensible default: roughly stone-tier.
        return 1_200L;
    }

    private boolean isGear(String n) {
        return n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    /** Nether and End goods cost more because getting them is riskier. */
    private double dimensionMultiplier(String n) {
        if (n.contains("NETHER") || n.contains("BLAZE") || n.contains("GHAST")
                || n.contains("MAGMA") || n.contains("SOUL") || n.contains("WARPED")
                || n.contains("CRIMSON") || n.contains("BASALT") || n.contains("BLACKSTONE"))
            return 1.6;
        if (n.contains("END") || n.contains("CHORUS") || n.contains("PURPUR")
                || n.contains("SHULKER") || n.contains("ELYTRA"))
            return 2.5;
        return 1.0;
    }

    /**
     * How much of the buy price the server pays back. Lower for farmable goods so
     * an automated farm cannot become a money printer (spec section 23).
     */
    private double buybackRatio(Material m) {
        String n = m.name();
        if (n.contains("COBBLESTONE") || n.contains("BAMBOO") || n.contains("SUGAR_CANE")
                || n.contains("KELP") || n.contains("PUMPKIN") || n.contains("MELON")
                || n.contains("CACTUS") || n.contains("WHEAT"))
            return 0.08;
        if (n.endsWith("_INGOT") || n.contains("GUNPOWDER") || n.contains("SLIME"))
            return 0.18;
        return 0.15;
    }

    // ------------------------------------------------------------------

    private MarketItem build(MarketItem.Key key, String display, String category,
                             long[] prices, String materialName) {
        BigDecimal um = BigDecimal.valueOf(prices[0]);
        BigDecimal buyback = BigDecimal.valueOf(prices[1]);

        // Suggested player-shop band. Section 19 wants player shops to sit
        // clearly between server buyback and the UM price, so undercutting the
        // market is always the profitable play for both sides.
        BigDecimal min = um.multiply(BigDecimal.valueOf(0.45)).setScale(0, RoundingMode.DOWN);
        BigDecimal max = um.multiply(BigDecimal.valueOf(0.78)).setScale(0, RoundingMode.DOWN);

        // Dynamic pricing bounds on the buyback only.
        BigDecimal floor = buyback.multiply(BigDecimal.valueOf(0.70)).setScale(0, RoundingMode.DOWN);
        BigDecimal ceiling = buyback.multiply(BigDecimal.valueOf(1.40)).setScale(0, RoundingMode.DOWN);

        Integer rareLimit = RARE_LIMITS.get(materialName);
        boolean rare = rareLimit != null;
        boolean expensive = um.compareTo(BigDecimal.valueOf(5_000_000L)) >= 0;

        return MarketItem.builder(key)
                .displayName(display)
                .category(category)
                .umBuyPrice(um)
                .serverBuybackBase(buyback)
                .suggested(min, max)
                .dynamicPricing(!rare)
                .floorCeiling(floor, ceiling)
                // Expensive goods get much tighter sell allowances.
                .sellTiers(expensive ? 4 : 128, expensive ? 8 : 384, expensive ? 16 : 1024)
                .tierRates(0.66, 0.33, 0.10)
                // Rare and expensive things almost never appear in deals.
                .dailyDeal(true, expensive ? 0.02 : 1.0, expensive ? 0.15 : 0.30)
                .highDemand(!rare, expensive ? 0.05 : 1.0, 0.45)
                .contractEligible(!rare && !expensive)
                .rare(rare, rareLimit == null ? 0 : rareLimit, 0L)
                .blacklisted(false)
                .build();
    }

    private String categoryOf(Material m) {
        String n = m.name();
        if (m.isBlock()) {
            if (n.endsWith("_ORE") || n.startsWith("RAW_")) return "ores";
            if (n.contains("WOOL") || n.contains("CONCRETE") || n.contains("TERRACOTTA")
                    || n.contains("GLASS")) return "decoration";
            return "building";
        }
        if (isGear(n)) return n.contains("HELMET") || n.contains("CHESTPLATE")
                || n.contains("LEGGINGS") || n.contains("BOOTS") ? "armor" : "tools";
        if (m.isEdible()) return "food";
        if (n.startsWith("MUSIC_DISC") || n.endsWith("_SHERD")) return "collectibles";
        if (n.contains("POTION") || n.contains("ARROW")) return "combat";
        return "misc";
    }

    private String pretty(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
