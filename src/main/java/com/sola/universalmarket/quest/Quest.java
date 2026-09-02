package com.sola.universalmarket.quest;

import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One quest a player can be working on.
 *
 * Progress is CUMULATIVE and never inventory-checked. "Gather 400 logs" counts
 * logs as they are acquired, so mining 400 and then using them still completes
 * the quest. Checking inventories instead would punish players for playing
 * normally, and would be trivially gamed by borrowing items.
 */
public final class Quest {

    private final String templateId;
    private final QuestTier tier;
    private final QuestType type;
    /** Material or entity name this quest counts, or null for untargeted types. */
    private final String target;
    private final int required;
    private final String description;

    private final BigDecimal rewardMoney;
    private final List<RewardItem> rewardItems;

    private int progress;

    /** A stack of items granted on completion. */
    public record RewardItem(Material material, int amount) { }

    public Quest(String templateId, QuestTier tier, QuestType type, String target,
                 int required, String description,
                 BigDecimal rewardMoney, List<RewardItem> rewardItems) {
        this.templateId = templateId;
        this.tier = tier;
        this.type = type;
        this.target = target;
        this.required = Math.max(1, required);
        this.description = description;
        this.rewardMoney = rewardMoney;
        this.rewardItems = rewardItems == null ? new ArrayList<>() : rewardItems;
    }

    public String templateId() { return templateId; }
    public QuestTier tier() { return tier; }
    public QuestType type() { return type; }
    public String target() { return target; }
    public int required() { return required; }
    public String description() { return description; }
    public BigDecimal rewardMoney() { return rewardMoney; }
    public List<RewardItem> rewardItems() { return rewardItems; }

    public int progress() { return progress; }
    public void progress(int value) { this.progress = Math.max(0, Math.min(value, required)); }

    /** Returns true when this increment completed the quest. */
    public boolean advance(int amount) {
        if (isComplete()) return false;
        progress = Math.min(required, progress + Math.max(0, amount));
        return isComplete();
    }

    public boolean isComplete() {
        return progress >= required;
    }

    public double fraction() {
        return required <= 0 ? 1.0 : Math.min(1.0, progress / (double) required);
    }

    /** "3 / 400" for counts, "$1.2M / $5M" for money quests. */
    public String progressText() {
        if (type.isMoney()) {
            return NumberFormatter.money(BigDecimal.valueOf(progress))
                    + " / " + NumberFormatter.money(BigDecimal.valueOf(required));
        }
        return NumberFormatter.count(progress) + " / " + NumberFormatter.count(required);
    }

    /** A ten-segment bar for the sidebar and menus. */
    public String progressBar() {
        int filled = (int) Math.round(fraction() * 10);
        StringBuilder sb = new StringBuilder("<green>");
        for (int i = 0; i < 10; i++) {
            if (i == filled) sb.append("<dark_gray>");
            sb.append('\u25AC');
        }
        return sb.toString();
    }

    public String rewardSummary() {
        StringBuilder sb = new StringBuilder(NumberFormatter.money(rewardMoney));
        for (RewardItem item : rewardItems) {
            sb.append(", ").append(item.amount()).append("x ")
              .append(prettyName(item.material().name()));
        }
        return sb.toString();
    }

    public static String prettyName(String raw) {
        String[] parts = raw.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
