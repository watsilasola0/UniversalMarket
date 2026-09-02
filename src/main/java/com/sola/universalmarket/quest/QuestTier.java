package com.sola.universalmarket.quest;

/**
 * The four quest difficulties.
 *
 * Reward ranges climb steeply on purpose. The point you raised is that a rich
 * player should still find Hard and For Real Competition quests worth doing, so
 * the top tiers pay on the scale of the late-game economy rather than a flat
 * amount that becomes pocket change.
 */
public enum QuestTier {

    EASY   ("Easy",                    "<green>",       875,      15_000L,       150_000L, 1.0,  0.6),
    MEDIUM ("Medium",                  "<yellow>",      250,     250_000L,     2_000_000L, 3.5,  1.0),
    HARD   ("Hard",                    "<red>",         125,   3_000_000L,    30_000_000L, 14.0, 1.6),
    REAL   ("For Real Competition",    "<dark_purple>",  50,  40_000_000L,   500_000_000L, 55.0, 2.6);

    private final String display;
    private final String colour;
    private final int poolShare;
    private final long minReward;
    private final long maxReward;
    /** Multiplier applied to the base amount a template asks for. */
    private final double amountScale;
    /** Multiplier applied to the value of item rewards. */
    private final double itemScale;

    QuestTier(String display, String colour, int poolShare,
              long minReward, long maxReward, double amountScale, double itemScale) {
        this.display = display;
        this.colour = colour;
        this.poolShare = poolShare;
        this.minReward = minReward;
        this.maxReward = maxReward;
        this.amountScale = amountScale;
        this.itemScale = itemScale;
    }

    public String display() { return display; }
    public String colour() { return colour; }
    public String coloured() { return colour + display; }
    public int poolShare() { return poolShare; }
    public long minReward() { return minReward; }
    public long maxReward() { return maxReward; }
    public double amountScale() { return amountScale; }
    public double itemScale() { return itemScale; }

    public static QuestTier byName(String raw) {
        for (QuestTier tier : values()) {
            if (tier.name().equalsIgnoreCase(raw) || tier.display.equalsIgnoreCase(raw)) return tier;
        }
        return null;
    }
}
