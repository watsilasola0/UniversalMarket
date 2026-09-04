package com.sola.universalmarket.backpack;

import org.bukkit.Material;

/**
 * The seven backpack tiers.
 *
 * Tier 1 holds exactly one chest. Everything above is a multiple of that, so
 * "how much is this worth" is answerable in chests rather than abstract slot
 * counts.
 *
 * PAGE SIZE IS A HARD LIMIT, NOT A CHOICE
 *
 *   A Minecraft container GUI cannot exceed 54 slots. Reserving the bottom row
 *   for navigation leaves 45 usable per page, so anything above 45 slots has to
 *   be paginated. Tier 7 is nine pages.
 */
public enum BackpackTier {

    ONE   (1, "White",      "<white>",        Material.WHITE_SHULKER_BOX,       27,        250_000L),
    TWO   (2, "Light Blue", "<aqua>",         Material.LIGHT_BLUE_SHULKER_BOX,  54,      1_500_000L),
    THREE (3, "Lime",       "<green>",        Material.LIME_SHULKER_BOX,        90,      8_000_000L),
    FOUR  (4, "Yellow",     "<yellow>",       Material.YELLOW_SHULKER_BOX,     135,     40_000_000L),
    FIVE  (5, "Orange",     "<gold>",         Material.ORANGE_SHULKER_BOX,     180,    200_000_000L),
    SIX   (6, "Magenta",    "<light_purple>", Material.MAGENTA_SHULKER_BOX,    270,  1_000_000_000L),
    SEVEN (7, "Purple",     "<dark_purple>",  Material.PURPLE_SHULKER_BOX,     405,  5_000_000_000L);

    /** Usable slots per page. The bottom row of a 54-slot GUI is navigation. */
    public static final int PAGE_SIZE = 45;

    private final int level;
    private final String display;
    private final String colour;
    private final Material icon;
    private final int slots;
    private final long price;

    BackpackTier(int level, String display, String colour,
                 Material icon, int slots, long price) {
        this.level = level;
        this.display = display;
        this.colour = colour;
        this.icon = icon;
        this.slots = slots;
        this.price = price;
    }

    public int level() { return level; }
    public String display() { return display; }
    public String colour() { return colour; }
    public String coloured() { return colour + display; }
    public Material icon() { return icon; }
    public int slots() { return slots; }
    public long price() { return price; }

    public int pages() {
        return (int) Math.ceil(slots / (double) PAGE_SIZE);
    }

    /** Roughly how many chests this is worth, for the shop description. */
    public String chestEquivalent() {
        double chests = slots / 27.0;
        return chests == Math.floor(chests)
                ? String.valueOf((int) chests)
                : String.format("%.1f", chests);
    }

    public static BackpackTier byLevel(int level) {
        for (BackpackTier tier : values()) if (tier.level == level) return tier;
        return null;
    }

    public static BackpackTier byName(String raw) {
        if (raw == null) return null;
        for (BackpackTier tier : values()) {
            if (tier.name().equalsIgnoreCase(raw)) return tier;
        }
        return null;
    }
}
