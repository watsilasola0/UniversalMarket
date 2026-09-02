package com.sola.universalmarket.quest;

/**
 * What a quest counts.
 *
 * EVERY type here is a repeatable counter. There are deliberately no one-shot
 * objectives like "kill the Ender Dragon" or "obtain a full netherite set",
 * because a quest you can only complete once is worthless the second time it
 * is offered - and quests are offered forever.
 */
public enum QuestType {

    MINE_BLOCK      ("Mine %amount% %target%"),
    PLACE_BLOCK     ("Place %amount% %target%"),
    KILL_MOB        ("Kill %amount% %target%"),
    KILL_ANY_HOSTILE("Kill %amount% hostile mobs"),
    HARVEST_CROP    ("Harvest %amount% %target%"),
    BREED_ANIMAL    ("Breed %amount% animals"),
    TAME_ANIMAL     ("Tame %amount% animals"),
    SHEAR_SHEEP     ("Shear %amount% sheep"),
    FISH_CATCH      ("Catch %amount% fish"),
    FISH_TREASURE   ("Reel in %amount% treasure items"),
    CRAFT_ITEM      ("Craft %amount% %target%"),
    SMELT_ITEM      ("Smelt %amount% items"),
    BREW_POTION     ("Brew %amount% potions"),
    ENCHANT_ITEM    ("Enchant %amount% items"),
    VILLAGER_TRADE  ("Trade with villagers %amount% times"),
    SELL_ITEM       ("Sell %amount% %target% to the server"),
    SELL_VALUE      ("Sell $%amount% of goods to the server"),
    BUY_ITEM        ("Buy %amount% %target% from the market"),
    SPEND_MONEY     ("Spend $%amount% at the Universal Market"),
    CREATE_LISTING  ("List goods for sale %amount% times"),
    LISTING_REVENUE ("Earn $%amount% from your own listings"),
    LOOT_CHEST      ("Loot %amount% naturally generated chests"),
    TRAVEL_WALK     ("Travel %amount% blocks on foot"),
    TRAVEL_BOAT     ("Travel %amount% blocks by boat"),
    TRAVEL_MINECART ("Travel %amount% blocks by minecart"),
    TRAVEL_ELYTRA   ("Travel %amount% blocks by elytra"),
    VISIT_BIOME     ("Visit %amount% different biomes"),
    EAT_FOOD        ("Eat %amount% %target%"),
    GAIN_XP         ("Gain %amount% experience"),
    BONE_MEAL       ("Use bone meal %amount% times"),
    SLEEP           ("Sleep in a bed %amount% times"),
    DAMAGE_TAKEN    ("Take %amount% damage and live to tell it");

    private final String pattern;

    QuestType(String pattern) {
        this.pattern = pattern;
    }

    public String pattern() {
        return pattern;
    }

    /** True when the amount is a money value rather than an item count. */
    public boolean isMoney() {
        return this == SELL_VALUE || this == SPEND_MONEY || this == LISTING_REVENUE;
    }

    /** True when the type needs a specific target (a mob, block or item). */
    public boolean needsTarget() {
        return switch (this) {
            case MINE_BLOCK, PLACE_BLOCK, KILL_MOB, HARVEST_CROP,
                 CRAFT_ITEM, SELL_ITEM, BUY_ITEM, EAT_FOOD -> true;
            default -> false;
        };
    }
}
