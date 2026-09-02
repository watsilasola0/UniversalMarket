package com.sola.universalmarket.quest;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Builds the quest pool.
 *
 * Rather than hand-writing 1,300 quests - which would be unmaintainable and
 * mostly filler - this crosses ~30 templates with the valid targets for each
 * (every ore, every crop, every hostile mob, every food) and a per-tier amount
 * range. That produces far more than 1,300 distinct quests, and adding a
 * template later multiplies out across every target automatically.
 *
 * The pool is capped at the shares you specified: 875 easy, 250 medium,
 * 125 hard, 50 For Real Competition.
 */
public final class QuestPool {

    private final Logger log;
    private final Random random = new Random();

    public QuestPool(Logger log) {
        this.log = log;
    }

    // ==================================================================
    // Target lists
    // ==================================================================

    private static final String[] ORES = {
            "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE",
            "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE", "NETHER_QUARTZ_ORE",
            "DEEPSLATE_IRON_ORE", "DEEPSLATE_GOLD_ORE", "DEEPSLATE_DIAMOND_ORE",
            "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_COAL_ORE", "DEEPSLATE_COPPER_ORE",
            "DEEPSLATE_LAPIS_ORE", "DEEPSLATE_EMERALD_ORE", "ANCIENT_DEBRIS"
    };

    private static final String[] COMMON_BLOCKS = {
            "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "GRANITE",
            "DIORITE", "ANDESITE", "TUFF", "CALCITE", "DIRT", "SAND", "RED_SAND",
            "GRAVEL", "CLAY", "NETHERRACK", "END_STONE", "BASALT", "BLACKSTONE",
            "SANDSTONE", "OBSIDIAN", "MOSS_BLOCK", "SNOW_BLOCK", "PACKED_ICE"
    };

    private static final String[] LOGS = {
            "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG",
            "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "CRIMSON_STEM", "WARPED_STEM"
    };

    private static final String[] CROPS = {
            "WHEAT", "CARROT", "POTATO", "BEETROOT", "PUMPKIN", "MELON",
            "SUGAR_CANE", "BAMBOO", "CACTUS", "COCOA_BEANS", "NETHER_WART",
            "SWEET_BERRIES", "GLOW_BERRIES", "KELP"
    };

    private static final String[] HOSTILE_MOBS = {
            "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN", "WITCH",
            "DROWNED", "HUSK", "STRAY", "PHANTOM", "PILLAGER", "VINDICATOR",
            "BLAZE", "GHAST", "PIGLIN", "HOGLIN", "MAGMA_CUBE", "SLIME",
            "WITHER_SKELETON", "ZOMBIFIED_PIGLIN", "SHULKER", "GUARDIAN",
            "ELDER_GUARDIAN", "RAVAGER", "EVOKER", "SILVERFISH", "CAVE_SPIDER",
            "ENDERMITE", "BREEZE", "BOGGED"
    };

    private static final String[] FOODS = {
            "BREAD", "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_CHICKEN",
            "COOKED_MUTTON", "COOKED_COD", "COOKED_SALMON", "BAKED_POTATO",
            "GOLDEN_CARROT", "PUMPKIN_PIE", "COOKIE", "APPLE", "CARROT"
    };

    private static final String[] CRAFTABLES = {
            "TORCH", "LADDER", "CHEST", "FURNACE", "STONE_BRICKS", "GLASS",
            "IRON_PICKAXE", "IRON_SWORD", "BOW", "ARROW", "BUCKET", "SHIELD",
            "OAK_PLANKS", "STICK", "CRAFTING_TABLE", "HOPPER", "RAIL",
            "DIAMOND_PICKAXE", "ANVIL", "ENCHANTING_TABLE"
    };

    private static final String[] TRADEABLES = {
            "IRON_INGOT", "REDSTONE", "GLASS", "EMERALD", "BOOK", "PAPER",
            "COAL", "BRICK", "LANTERN", "COMPASS"
    };

    // ==================================================================
    // Item reward pools per tier
    // ==================================================================

    private static final Map<QuestTier, String[]> REWARD_ITEMS = new EnumMap<>(QuestTier.class);
    static {
        REWARD_ITEMS.put(QuestTier.EASY, new String[]{
                "BREAD", "COOKED_BEEF", "ARROW", "TORCH", "IRON_INGOT",
                "COAL", "APPLE", "OAK_LOG", "COOKED_CHICKEN", "STRING"});
        REWARD_ITEMS.put(QuestTier.MEDIUM, new String[]{
                "IRON_INGOT", "GOLD_INGOT", "IRON_BLOCK", "EMERALD", "LAPIS_BLOCK",
                "BLAZE_ROD", "ENDER_PEARL", "GOLDEN_CARROT", "OBSIDIAN", "GUNPOWDER"});
        REWARD_ITEMS.put(QuestTier.HARD, new String[]{
                "DIAMOND", "DIAMOND_BLOCK", "GOLD_BLOCK", "EMERALD_BLOCK",
                "GOLDEN_APPLE", "NETHERITE_SCRAP", "TOTEM_OF_UNDYING", "SHULKER_SHELL"});
        REWARD_ITEMS.put(QuestTier.REAL, new String[]{
                "DIAMOND_BLOCK", "NETHERITE_INGOT", "NETHERITE_BLOCK", "ELYTRA",
                "NETHER_STAR", "TOTEM_OF_UNDYING", "ENCHANTED_GOLDEN_APPLE", "BEACON"});
    }

    /** Reward stack sizes per tier: {min, max}. */
    private static final Map<QuestTier, int[]> REWARD_COUNTS = new EnumMap<>(QuestTier.class);
    static {
        REWARD_COUNTS.put(QuestTier.EASY,   new int[]{4, 32});
        REWARD_COUNTS.put(QuestTier.MEDIUM, new int[]{2, 16});
        REWARD_COUNTS.put(QuestTier.HARD,   new int[]{1, 8});
        REWARD_COUNTS.put(QuestTier.REAL,   new int[]{1, 4});
    }

    // ==================================================================
    // Generation
    // ==================================================================

    public Map<QuestTier, List<Quest>> generate() {
        Map<QuestTier, List<Quest>> pool = new EnumMap<>(QuestTier.class);
        for (QuestTier tier : QuestTier.values()) {
            List<Quest> candidates = new ArrayList<>();
            buildFor(tier, candidates);

            // Trim to the share you specified, shuffling first so the cut is
            // spread across template kinds rather than truncating one type.
            java.util.Collections.shuffle(candidates, random);
            int share = Math.min(tier.poolShare(), candidates.size());
            pool.put(tier, new ArrayList<>(candidates.subList(0, share)));
        }
        int total = pool.values().stream().mapToInt(List::size).sum();
        log.info("Quest pool generated: " + total + " quests ("
                + pool.get(QuestTier.EASY).size() + " easy, "
                + pool.get(QuestTier.MEDIUM).size() + " medium, "
                + pool.get(QuestTier.HARD).size() + " hard, "
                + pool.get(QuestTier.REAL).size() + " for real competition)");
        return pool;
    }

    private void buildFor(QuestTier tier, List<Quest> out) {
        double scale = tier.amountScale();

        // --- targeted templates ---
        addTargeted(out, tier, QuestType.MINE_BLOCK, ORES, base(32, scale), base(96, scale));
        addTargeted(out, tier, QuestType.MINE_BLOCK, COMMON_BLOCKS, base(128, scale), base(384, scale));
        addTargeted(out, tier, QuestType.MINE_BLOCK, LOGS, base(64, scale), base(192, scale));
        addTargeted(out, tier, QuestType.PLACE_BLOCK, COMMON_BLOCKS, base(96, scale), base(256, scale));
        addTargeted(out, tier, QuestType.KILL_MOB, HOSTILE_MOBS, base(15, scale), base(45, scale));
        addTargeted(out, tier, QuestType.HARVEST_CROP, CROPS, base(64, scale), base(192, scale));
        addTargeted(out, tier, QuestType.CRAFT_ITEM, CRAFTABLES, base(24, scale), base(64, scale));
        addTargeted(out, tier, QuestType.SELL_ITEM, COMMON_BLOCKS, base(64, scale), base(256, scale));
        addTargeted(out, tier, QuestType.SELL_ITEM, ORES, base(16, scale), base(64, scale));
        addTargeted(out, tier, QuestType.BUY_ITEM, TRADEABLES, base(16, scale), base(64, scale));
        addTargeted(out, tier, QuestType.EAT_FOOD, FOODS, base(12, scale), base(40, scale));

        // --- untargeted templates ---
        addPlain(out, tier, QuestType.KILL_ANY_HOSTILE, base(30, scale), base(90, scale));
        addPlain(out, tier, QuestType.BREED_ANIMAL,     base(6, scale),  base(20, scale));
        addPlain(out, tier, QuestType.TAME_ANIMAL,      base(2, scale),  base(6, scale));
        addPlain(out, tier, QuestType.SHEAR_SHEEP,      base(10, scale), base(30, scale));
        addPlain(out, tier, QuestType.FISH_CATCH,       base(12, scale), base(40, scale));
        addPlain(out, tier, QuestType.FISH_TREASURE,    base(2, scale),  base(8, scale));
        addPlain(out, tier, QuestType.SMELT_ITEM,       base(48, scale), base(160, scale));
        addPlain(out, tier, QuestType.BREW_POTION,      base(4, scale),  base(14, scale));
        addPlain(out, tier, QuestType.ENCHANT_ITEM,     base(3, scale),  base(12, scale));
        addPlain(out, tier, QuestType.VILLAGER_TRADE,   base(12, scale), base(40, scale));
        addPlain(out, tier, QuestType.CREATE_LISTING,   base(2, scale),  base(8, scale));
        addPlain(out, tier, QuestType.LOOT_CHEST,       base(3, scale),  base(10, scale));
        addPlain(out, tier, QuestType.TRAVEL_WALK,      base(1500, scale), base(4000, scale));
        addPlain(out, tier, QuestType.TRAVEL_BOAT,      base(1200, scale), base(3500, scale));
        addPlain(out, tier, QuestType.TRAVEL_MINECART,  base(800, scale),  base(2500, scale));
        addPlain(out, tier, QuestType.TRAVEL_ELYTRA,    base(2000, scale), base(6000, scale));
        addPlain(out, tier, QuestType.VISIT_BIOME,      base(4, scale),  base(12, scale));
        addPlain(out, tier, QuestType.GAIN_XP,          base(300, scale), base(900, scale));
        addPlain(out, tier, QuestType.BONE_MEAL,        base(24, scale), base(80, scale));
        addPlain(out, tier, QuestType.SLEEP,            base(3, scale),  base(10, scale));
        addPlain(out, tier, QuestType.DAMAGE_TAKEN,     base(100, scale), base(300, scale));

        // --- money-denominated templates, scaled off the tier reward band ---
        long moneyLow = tier.minReward() * 2;
        long moneyHigh = tier.maxReward();
        addMoney(out, tier, QuestType.SELL_VALUE, moneyLow, moneyHigh);
        addMoney(out, tier, QuestType.SPEND_MONEY, moneyLow, moneyHigh);
        addMoney(out, tier, QuestType.LISTING_REVENUE, moneyLow, moneyHigh);
    }

    private int base(int value, double scale) {
        return Math.max(1, (int) Math.round(value * scale));
    }

    // ==================================================================
    // Template expansion
    // ==================================================================

    private void addTargeted(List<Quest> out, QuestTier tier, QuestType type,
                             String[] targets, int minAmount, int maxAmount) {
        for (String target : targets) {
            if (!isValidTarget(type, target)) continue;
            int amount = roundNicely(minAmount + random.nextInt(Math.max(1, maxAmount - minAmount + 1)));
            String description = type.pattern()
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%target%", Quest.prettyName(target));
            out.add(build(tier, type, target, amount, description));
        }
    }

    private void addPlain(List<Quest> out, QuestTier tier, QuestType type,
                          int minAmount, int maxAmount) {
        // Three variants of each so the pool is not one quest per template.
        for (int i = 0; i < 3; i++) {
            int amount = roundNicely(minAmount + random.nextInt(Math.max(1, maxAmount - minAmount + 1)));
            String description = type.pattern().replace("%amount%", String.valueOf(amount));
            out.add(build(tier, type, null, amount, description));
        }
    }

    private void addMoney(List<Quest> out, QuestTier tier, QuestType type,
                          long low, long high) {
        for (int i = 0; i < 3; i++) {
            long amount = low + (long) (random.nextDouble() * (high - low));
            amount = Math.max(1000L, (amount / 1000L) * 1000L);
            String description = type.pattern().replace("%amount%",
                    com.sola.universalmarket.util.NumberFormatter.plain(BigDecimal.valueOf(amount)));
            out.add(build(tier, type, null, (int) Math.min(Integer.MAX_VALUE, amount), description));
        }
    }

    /** Skip anything this server version does not know about. */
    private boolean isValidTarget(QuestType type, String target) {
        if (type == QuestType.KILL_MOB) {
            try { EntityType.valueOf(target); return true; }
            catch (IllegalArgumentException e) { return false; }
        }
        Material material = Material.getMaterial(target);
        return material != null && !material.isLegacy();
    }

    /** Round to a tidy number so quests read as "128" rather than "127". */
    private int roundNicely(int value) {
        if (value <= 10) return value;
        if (value <= 100) return (value / 5) * 5;
        if (value <= 1000) return (value / 10) * 10;
        return (value / 50) * 50;
    }

    // ==================================================================
    // Rewards
    // ==================================================================

    private Quest build(QuestTier tier, QuestType type, String target,
                        int amount, String description) {
        long money = tier.minReward()
                + (long) (random.nextDouble() * (tier.maxReward() - tier.minReward()));
        money = Math.max(1000L, (money / 1000L) * 1000L);

        List<Quest.RewardItem> items = new ArrayList<>();
        String[] rewardPool = REWARD_ITEMS.get(tier);
        int[] counts = REWARD_COUNTS.get(tier);
        int stacks = 1 + random.nextInt(2);

        for (int i = 0; i < stacks; i++) {
            String name = rewardPool[random.nextInt(rewardPool.length)];
            Material material = Material.getMaterial(name);
            if (material == null || !material.isItem()) continue;
            int count = counts[0] + random.nextInt(Math.max(1, counts[1] - counts[0] + 1));
            count = Math.min(count, material.getMaxStackSize());
            items.add(new Quest.RewardItem(material, Math.max(1, count)));
        }

        String templateId = tier.name() + ":" + type.name()
                + (target == null ? "" : ":" + target) + ":" + amount;

        return new Quest(templateId, tier, type, target, amount, description,
                BigDecimal.valueOf(money), items);
    }
}
