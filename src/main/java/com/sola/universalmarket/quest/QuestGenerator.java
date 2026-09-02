package com.sola.universalmarket.quest;

import com.sola.universalmarket.catalog.MarketCatalog;
import com.sola.universalmarket.catalog.MarketItem;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Builds the quest pool: 875 easy, 250 medium, 125 hard, 50 For Real Competition.
 *
 * Quests are GENERATED from templates crossed with valid targets rather than
 * hand-written. Writing 1,300 by hand would mean 1,300 chances to typo a
 * material name, and no way to retune difficulty without editing all of them.
 * Here, changing one range in config reshapes every quest of that tier.
 *
 * REWARDS ARE PRICED AGAINST THE CATALOGUE
 *
 *   A quest's payout is derived from what the work is actually worth. Mining 64
 *   iron ore is priced off the real iron value, then multiplied by a tier
 *   factor so the quest always beats simply selling what you gathered. If it
 *   did not, quests would be a worse use of time than ordinary play and nobody
 *   would touch them.
 *
 *   Rewards are also floored per tier, so a Hard quest is never worth less than
 *   a Medium one just because it happened to involve cheap blocks.
 */
public final class QuestGenerator {

    private final Logger log;
    private final MarketCatalog catalog;
    private final Random random = new Random();

    public QuestGenerator(Logger log, MarketCatalog catalog) {
        this.log = log;
        this.catalog = catalog;
    }

    // ==================================================================
    // Targets
    // ==================================================================

    private static final EntityType[] COMMON_MOBS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER,
            EntityType.ENDERMAN, EntityType.WITCH, EntityType.DROWNED, EntityType.HUSK,
            EntityType.STRAY, EntityType.SLIME, EntityType.PHANTOM, EntityType.PILLAGER,
            EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.PIGLIN, EntityType.HOGLIN,
            EntityType.WITHER_SKELETON, EntityType.GHAST, EntityType.ZOMBIFIED_PIGLIN,
            EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.GUARDIAN,
            EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER, EntityType.SHULKER
    };

    private static final EntityType[] BREEDABLE = {
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
            EntityType.RABBIT, EntityType.HORSE, EntityType.LLAMA, EntityType.WOLF,
            EntityType.CAT, EntityType.FOX, EntityType.BEE, EntityType.GOAT,
            EntityType.PANDA, EntityType.TURTLE, EntityType.AXOLOTL
    };

    private static final Material[] CROPS = {
            Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT,
            Material.PUMPKIN, Material.MELON, Material.SUGAR_CANE, Material.BAMBOO,
            Material.COCOA_BEANS, Material.NETHER_WART, Material.SWEET_BERRIES,
            Material.CACTUS, Material.KELP
    };

    private static final Material[] ORES = {
            Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE, Material.GOLD_ORE,
            Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS
    };

    private static final Material[] COMMON_BLOCKS = {
            Material.COBBLESTONE, Material.STONE, Material.DIRT, Material.SAND,
            Material.GRAVEL, Material.ANDESITE, Material.GRANITE, Material.DIORITE,
            Material.DEEPSLATE, Material.TUFF, Material.NETHERRACK, Material.END_STONE,
            Material.OBSIDIAN, Material.CLAY, Material.SANDSTONE, Material.BASALT
    };

    private static final Material[] FOODS = {
            Material.BREAD, Material.COOKED_BEEF, Material.COOKED_PORKCHOP,
            Material.COOKED_CHICKEN, Material.COOKED_MUTTON, Material.BAKED_POTATO,
            Material.GOLDEN_CARROT, Material.COOKED_SALMON, Material.PUMPKIN_PIE
    };

    // ==================================================================
    // Generation
    // ==================================================================

    public List<Quest> generate() {
        List<Quest> pool = new ArrayList<>();
        for (Quest.Tier tier : Quest.Tier.values()) {
            int wanted = tier.poolSize;
            int guard = 0;
            List<Quest> tierQuests = new ArrayList<>();
            while (tierQuests.size() < wanted && guard++ < wanted * 40) {
                Quest quest = build(tier, tierQuests.size());
                if (quest != null) tierQuests.add(quest);
            }
            pool.addAll(tierQuests);
            log.info("Generated " + tierQuests.size() + " " + tier.display + " quests.");
        }
        log.info("Quest pool ready: " + pool.size() + " total.");
        return pool;
    }

    private Quest build(Quest.Tier tier, int index) {
        QuestType[] types = QuestType.values();
        QuestType type = types[random.nextInt(types.length)];

        String target = null;
        String label = null;
        long unitValue = 0;

        switch (type) {
            case KILL_MOB -> {
                EntityType mob = COMMON_MOBS[random.nextInt(COMMON_MOBS.length)];
                target = mob.name();
                label = pretty(mob.name());
                unitValue = 2_500L;
            }
            case BREED_SPECIFIC -> {
                EntityType animal = BREEDABLE[random.nextInt(BREEDABLE.length)];
                target = animal.name();
                label = pretty(animal.name());
                unitValue = 4_000L;
            }
            case HARVEST_CROP -> {
                Material crop = CROPS[random.nextInt(CROPS.length)];
                target = crop.name();
                label = pretty(crop.name());
                unitValue = valueOf(crop, 800L);
            }
            case MINE_BLOCK -> {
                Material block = random.nextBoolean()
                        ? ORES[random.nextInt(ORES.length)]
                        : COMMON_BLOCKS[random.nextInt(COMMON_BLOCKS.length)];
                target = block.name();
                label = pretty(block.name());
                unitValue = valueOf(block, 500L);
            }
            case PLACE_BLOCK -> {
                Material block = COMMON_BLOCKS[random.nextInt(COMMON_BLOCKS.length)];
                target = block.name();
                label = pretty(block.name());
                unitValue = valueOf(block, 500L);
            }
            case CRAFT_ITEM, SMELT_ITEM, SELL_ITEM_SERVER, BUY_ITEM_MARKET -> {
                MarketItem item = randomCatalogItem(tier);
                if (item == null) return null;
                target = item.material() == null ? null : item.material().name();
                if (target == null) return null;
                label = item.displayName();
                unitValue = item.umBuyPrice().longValue();
            }
            case EAT_FOOD -> {
                Material food = FOODS[random.nextInt(FOODS.length)];
                target = food.name();
                label = pretty(food.name());
                unitValue = valueOf(food, 3_000L);
            }
            default -> {
                if (type.needsTarget()) return null;   // unsupported combination
                unitValue = defaultUnitValue(type);
            }
        }

        int amount = amountFor(tier, type, unitValue);
        if (amount <= 0) return null;

        BigDecimal money = rewardFor(tier, unitValue, amount);
        List<Quest.ItemReward> items = itemRewardsFor(tier);

        String id = tier.name().toLowerCase(Locale.ROOT) + "_" + index + "_"
                + type.name().toLowerCase(Locale.ROOT);
        return new Quest(id, tier, type, target, label, amount, money, items);
    }

    // ==================================================================
    // Amounts
    // ==================================================================

    /**
     * How many of a thing the quest asks for.
     *
     * Scaled inversely to unit value, so a quest is roughly the same amount of
     * WORK regardless of what it is about: 512 cobblestone and 8 ancient debris
     * both land in the same effort band.
     */
    private int amountFor(Quest.Tier tier, QuestType type, long unitValue) {
        double workBudget = switch (tier) {
            case EASY -> 60_000;
            case MEDIUM -> 900_000;
            case HARD -> 9_000_000;
            case COMPETITION -> 120_000_000;
        };

        if (type.isMoneyAmount()) {
            return (int) Math.min(Integer.MAX_VALUE, Math.round(workBudget * 4));
        }

        long value = Math.max(50L, unitValue);
        int amount = (int) Math.round(workBudget / value);

        // Keep counts sane and readable regardless of the maths.
        int min = switch (tier) {
            case EASY -> 8;
            case MEDIUM -> 24;
            case HARD -> 64;
            case COMPETITION -> 200;
        };
        int max = switch (tier) {
            case EASY -> 256;
            case MEDIUM -> 1_024;
            case HARD -> 4_096;
            case COMPETITION -> 20_000;
        };
        amount = Math.max(min, Math.min(max, amount));

        // Round to something human: 137 becomes 128.
        return roundNicely(amount);
    }

    private int roundNicely(int amount) {
        if (amount <= 16) return amount;
        if (amount <= 64) return (amount / 8) * 8;
        if (amount <= 512) return (amount / 32) * 32;
        if (amount <= 4096) return (amount / 128) * 128;
        return (amount / 500) * 500;
    }

    // ==================================================================
    // Rewards
    // ==================================================================

    /**
     * Money reward.
     *
     * Always a multiple of what the gathered goods would fetch at server
     * buyback, so doing the quest beats just selling the materials. Floored per
     * tier so a Hard quest about cheap blocks still pays like a Hard quest.
     */
    private BigDecimal rewardFor(Quest.Tier tier, long unitValue, int amount) {
        double multiplier = switch (tier) {
            case EASY -> 1.6;
            case MEDIUM -> 2.0;
            case HARD -> 2.6;
            case COMPETITION -> 3.4;
        };
        long raw = Math.round(unitValue * amount * 0.18 * multiplier);

        long floor = switch (tier) {
            case EASY -> 15_000L;
            case MEDIUM -> 250_000L;
            case HARD -> 3_000_000L;
            case COMPETITION -> 40_000_000L;
        };
        long ceiling = switch (tier) {
            case EASY -> 150_000L;
            case MEDIUM -> 2_000_000L;
            case HARD -> 30_000_000L;
            case COMPETITION -> 500_000_000L;
        };
        return BigDecimal.valueOf(Math.max(floor, Math.min(ceiling, raw)));
    }

    private List<Quest.ItemReward> itemRewardsFor(Quest.Tier tier) {
        List<Quest.ItemReward> out = new ArrayList<>();
        switch (tier) {
            case EASY -> {
                out.add(new Quest.ItemReward(FOODS[random.nextInt(FOODS.length)], 8 + random.nextInt(17)));
                if (random.nextBoolean()) {
                    out.add(new Quest.ItemReward(Material.IRON_INGOT, 1 + random.nextInt(3)));
                }
            }
            case MEDIUM -> {
                out.add(new Quest.ItemReward(Material.IRON_INGOT, 8 + random.nextInt(17)));
                out.add(new Quest.ItemReward(FOODS[random.nextInt(FOODS.length)], 12 + random.nextInt(13)));
                if (random.nextInt(3) == 0) {
                    out.add(new Quest.ItemReward(Material.EMERALD, 2 + random.nextInt(5)));
                }
            }
            case HARD -> {
                out.add(new Quest.ItemReward(Material.DIAMOND_BLOCK, 1 + random.nextInt(4)));
                out.add(new Quest.ItemReward(Material.GOLDEN_APPLE, 1 + random.nextInt(3)));
                if (random.nextInt(3) == 0) {
                    out.add(new Quest.ItemReward(Material.NETHERITE_SCRAP, 1));
                }
            }
            case COMPETITION -> {
                out.add(new Quest.ItemReward(Material.DIAMOND_BLOCK, 4 + random.nextInt(9)));
                Material[] prestige = {
                        Material.NETHERITE_INGOT, Material.TOTEM_OF_UNDYING,
                        Material.ENCHANTED_GOLDEN_APPLE, Material.NETHER_STAR,
                        Material.SHULKER_SHELL, Material.HEART_OF_THE_SEA
                };
                out.add(new Quest.ItemReward(prestige[random.nextInt(prestige.length)],
                        1 + random.nextInt(2)));
            }
        }
        return out;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private MarketItem randomCatalogItem(Quest.Tier tier) {
        List<MarketItem> candidates = new ArrayList<>();
        long cap = switch (tier) {
            case EASY -> 20_000L;
            case MEDIUM -> 300_000L;
            case HARD -> 5_000_000L;
            case COMPETITION -> Long.MAX_VALUE;
        };
        for (MarketItem item : catalog.all()) {
            if (item.blacklisted() || item.key().hasVariant()) continue;
            if (item.material() == null || !item.material().isItem()) continue;
            if (item.umBuyPrice().longValue() > cap) continue;
            candidates.add(item);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private long valueOf(Material material, long fallback) {
        MarketItem item = catalog.byMaterial(material);
        return item == null ? fallback : Math.max(1L, item.umBuyPrice().longValue());
    }

    private long defaultUnitValue(QuestType type) {
        return switch (type) {
            case KILL_HOSTILE, KILL_AT_NIGHT, KILL_UNDERGROUND -> 2_500L;
            case KILL_IN_NETHER, KILL_IN_END, KILL_WITH_BOW -> 4_000L;
            case MINE_DEEP, MINE_ANY_ORE -> 3_000L;
            case GATHER_LOGS, STRIP_LOGS -> 1_900L;
            case BREED_ANIMAL, TAME_ANIMAL, SHEAR_SHEEP, MILK_COW -> 4_000L;
            case BONE_MEAL -> 1_200L;
            case FISH_CATCH -> 3_000L;
            case FISH_TREASURE -> 60_000L;
            case CRAFT_ANY, SMELT_ANY, PLACE_ANY -> 900L;
            case BREW_POTION -> 45_000L;
            case ENCHANT_ITEM -> 30_000L;
            case REPAIR_ANVIL -> 20_000L;
            case TRADE_VILLAGER -> 12_000L;
            case BUY_FROM_PLAYER, CREATE_LISTING -> 40_000L;
            case TRAVEL_WALK -> 60L;
            case TRAVEL_BOAT, TRAVEL_MINECART -> 40L;
            case TRAVEL_ELYTRA -> 25L;
            case LOOT_CHEST -> 80_000L;
            case SLEEP_BED -> 8_000L;
            case EAT_ANY -> 4_000L;
            case GAIN_XP -> 300L;
            case THROW_PEARL -> 30_000L;
            case DAMAGE_TAKEN -> 2_000L;
            default -> 2_000L;
        };
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
