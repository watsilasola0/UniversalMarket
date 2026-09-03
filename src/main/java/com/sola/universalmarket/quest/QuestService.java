package com.sola.universalmarket.quest;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quest state: what each player is doing, how far along they are, and what they
 * get for finishing.
 *
 * ONE ACTIVE QUEST AT A TIME. That is the main brake on quests becoming a money
 * faucet, which your section 53 rules out. Combined with a daily completion cap
 * it keeps quests a supplement to playing rather than a replacement for it.
 *
 * Rerolls are limited to three per offer. Cancelling is free but returns you to
 * the offer screen with the reroll count reset, because cancelling costs you the
 * progress you had, which is penalty enough.
 */
public final class QuestService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Random random = new Random();

    private Map<QuestTier, List<Quest>> pool = new EnumMap<>(QuestTier.class);

    /** Player -> the quest they are currently working on. */
    private final Map<UUID, Quest> active = new ConcurrentHashMap<>();
    /** Player -> the three quests currently on offer. */
    private final Map<UUID, List<Quest>> offers = new ConcurrentHashMap<>();
    /** Player -> free rerolls used since their last refresh. */
    private final Map<UUID, Integer> rerolls = new ConcurrentHashMap<>();
    /** Player -> paid rerolls bought since their last refresh, for the doubling price. */
    private final Map<UUID, Integer> paidRerolls = new ConcurrentHashMap<>();
    /** Player -> when their free rerolls last refreshed. */
    private final Map<UUID, Long> rerollRefreshedAt = new ConcurrentHashMap<>();
    /** Player -> completions today, for the daily cap. */
    private final Map<UUID, Integer> completedToday = new ConcurrentHashMap<>();
    /** Biome tracking for VISIT_BIOME quests. */
    private final Map<UUID, java.util.Set<String>> biomesSeen = new ConcurrentHashMap<>();

    public QuestService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    public void generatePool() {
        this.pool = new QuestPool(plugin.getLogger()).generate();
    }

    public int poolSize() {
        return pool.values().stream().mapToInt(List::size).sum();
    }

    // ==================================================================
    // Offers
    // ==================================================================

    public List<Quest> offersFor(Player player) {
        return offers.computeIfAbsent(player.getUniqueId(), k -> rollOffer());
    }

    /** Three quests, one from each of three different tiers where possible. */
    private List<Quest> rollOffer() {
        List<Quest> out = new ArrayList<>();
        QuestTier[] tiers = QuestTier.values();

        // Weighted so easy appears most often and For Real Competition rarely.
        for (int i = 0; i < 3; i++) {
            QuestTier tier = weightedTier();
            List<Quest> candidates = pool.get(tier);
            if (candidates == null || candidates.isEmpty()) continue;
            Quest picked = candidates.get(random.nextInt(candidates.size()));
            // Fresh instance so progress does not leak between players.
            out.add(copy(picked));
        }
        while (out.size() < 3) {
            List<Quest> easy = pool.get(QuestTier.EASY);
            if (easy == null || easy.isEmpty()) break;
            out.add(copy(easy.get(random.nextInt(easy.size()))));
        }
        return out;
    }

    private QuestTier weightedTier() {
        int roll = random.nextInt(100);
        if (roll < 50) return QuestTier.EASY;
        if (roll < 80) return QuestTier.MEDIUM;
        if (roll < 96) return QuestTier.HARD;
        return QuestTier.REAL;
    }

    private Quest copy(Quest source) {
        return new Quest(source.templateId(), source.tier(), source.type(), source.target(),
                source.required(), source.description(), source.rewardMoney(),
                new ArrayList<>(source.rewardItems()));
    }

    /**
     * Free rerolls refresh on a timer.
     *
     * Checked lazily rather than on a scheduled task: there is no point running
     * a timer over every player who has ever logged in when the answer can be
     * derived from a timestamp the moment somebody actually opens the menu.
     */
    private void refreshIfDue(UUID player) {
        long window = plugin.getConfig().getLong("quests.reroll-refresh-minutes", 10) * 60_000L;
        long last = rerollRefreshedAt.getOrDefault(player, 0L);
        if (System.currentTimeMillis() - last < window) return;

        rerollRefreshedAt.put(player, System.currentTimeMillis());
        rerolls.remove(player);
        // Paid price resets alongside the free allowance, as you asked.
        paidRerolls.remove(player);
    }

    public long rerollRefreshInMillis(UUID player) {
        long window = plugin.getConfig().getLong("quests.reroll-refresh-minutes", 10) * 60_000L;
        long last = rerollRefreshedAt.getOrDefault(player, 0L);
        return Math.max(0L, (last + window) - System.currentTimeMillis());
    }

    public int rerollsUsed(UUID player) {
        refreshIfDue(player);
        return rerolls.getOrDefault(player, 0);
    }

    /** Cost of the next PAID reroll: base, then doubling each time. */
    public BigDecimal nextRerollCost(UUID player) {
        refreshIfDue(player);
        long base = plugin.getConfig().getLong("quests.reroll-cost", 50_000L);
        int bought = paidRerolls.getOrDefault(player, 0);
        // Cap the exponent so a long session cannot overflow into nonsense.
        int exponent = Math.min(bought, 20);
        return BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(1L << exponent));
    }

    public boolean hasFreeReroll(UUID player) {
        return rerollsUsed(player) < rerollsAllowed();
    }

    /**
     * Pay for a reroll once the free ones are gone.
     * Returns false if they cannot afford it.
     */
    public boolean payForReroll(Player player) {
        BigDecimal cost = nextRerollCost(player.getUniqueId());
        if (!plugin.economy().withdraw(player, cost)) return false;

        paidRerolls.merge(player.getUniqueId(), 1, Integer::sum);
        offers.put(player.getUniqueId(), rollOffer());
        player.sendMessage(mm.deserialize(plugin.messages().get("quest.reroll-paid")
                .replace("%cost%", NumberFormatter.money(cost))));
        return true;
    }

    public int rerollsAllowed() {
        return plugin.getConfig().getInt("quests.max-rerolls", 3);
    }

    /** Returns false when the player is out of rerolls. */
    public boolean reroll(Player player) {
        int used = rerollsUsed(player.getUniqueId());
        if (used >= rerollsAllowed()) return false;
        rerolls.put(player.getUniqueId(), used + 1);
        offers.put(player.getUniqueId(), rollOffer());
        return true;
    }

    public List<Quest> peekOffers(UUID player) {
        return offers.get(player);
    }

    // ==================================================================
    // Accept / cancel
    // ==================================================================

    public Quest activeFor(UUID player) {
        return active.get(player);
    }

    public boolean hasActive(UUID player) {
        return active.containsKey(player);
    }

    public boolean accept(Player player, int offerIndex) {
        if (hasActive(player.getUniqueId())) return false;
        List<Quest> offered = offersFor(player);
        if (offerIndex < 0 || offerIndex >= offered.size()) return false;

        Quest quest = offered.get(offerIndex);
        active.put(player.getUniqueId(), quest);

        // Offers and the reroll count SURVIVE accepting. Abandoning a quest
        // returns you to the same three choices with the same allowance spent,
        // so taking a quest and changing your mind does not cost you a reroll.
        persist(player.getUniqueId(), quest);

        player.sendMessage(mm.deserialize(plugin.messages().get("quest.accepted")
                .replace("%tier%", quest.tier().coloured())
                .replace("%quest%", quest.description())
                .replace("%reward%", quest.rewardSummary())));
        plugin.sounds().confirm(player);
        return true;
    }

    public void cancel(Player player) {
        Quest quest = active.remove(player.getUniqueId());
        if (quest == null) return;
        clearPersisted(player.getUniqueId());

        // Put the abandoned quest back on the board at full progress reset, next
        // to the two the player did not choose.
        List<Quest> current = offers.get(player.getUniqueId());
        if (current == null) {
            current = new ArrayList<>();
            offers.put(player.getUniqueId(), current);
        }
        boolean present = current.stream()
                .anyMatch(q -> q.templateId().equals(quest.templateId()));
        if (!present) {
            if (current.size() >= 3) current.remove(current.size() - 1);
            current.add(0, copy(quest));
        }

        player.sendMessage(mm.deserialize(plugin.messages().get("quest.cancelled")));
        plugin.sounds().error(player);
    }

    // ==================================================================
    // Progress
    // ==================================================================

    /**
     * Advance a player's quest if it matches. Called from the event listener on
     * the main thread; deliberately cheap, since some of these fire very often.
     */
    public void progress(Player player, QuestType type, String target, int amount) {
        if (player == null || amount <= 0) return;
        Quest quest = active.get(player.getUniqueId());
        if (quest == null || quest.isComplete()) return;
        if (quest.type() != type) return;
        if (quest.target() != null && !quest.target().equalsIgnoreCase(target)) return;

        int before = quest.progress();
        boolean done = quest.advance(amount);
        if (quest.progress() != before) persist(player.getUniqueId(), quest);
        if (done) complete(player, quest);
    }

    /** Untargeted convenience overload. */
    public void progress(Player player, QuestType type, int amount) {
        progress(player, type, null, amount);
    }

    /** VISIT_BIOME counts distinct biomes, so it needs its own path. */
    public void noteBiome(Player player, String biome) {
        Quest quest = active.get(player.getUniqueId());
        if (quest == null || quest.type() != QuestType.VISIT_BIOME || quest.isComplete()) return;

        var seen = biomesSeen.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(biome)) return;
        quest.progress(seen.size());
        persist(player.getUniqueId(), quest);
        if (quest.isComplete()) complete(player, quest);
    }

    // ==================================================================
    // Completion
    // ==================================================================

    private void complete(Player player, Quest quest) {
        active.remove(player.getUniqueId());
        biomesSeen.remove(player.getUniqueId());
        clearPersisted(player.getUniqueId());
        // A completed quest earns a fresh board, unlike an abandoned one.
        offers.remove(player.getUniqueId());

        int done = completedToday.merge(player.getUniqueId(), 1, Integer::sum);

        // Money first: it can never fail for lack of space.
        plugin.economy().deposit(player, quest.rewardMoney());
        plugin.transactions().recordContractReward(
                player.getUniqueId(), quest.templateId(), quest.rewardMoney());

        List<String> granted = new ArrayList<>();
        for (Quest.RewardItem reward : quest.rewardItems()) {
            ItemStack stack = new ItemStack(reward.material(), reward.amount());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            // Quest rewards DO drop if the inventory is full, unlike purchases.
            // The player has already earned these; refusing them would be worse
            // than dropping them at their feet.
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            granted.add(reward.amount() + "x " + Quest.prettyName(reward.material().name()));
        }

        player.sendMessage(mm.deserialize(plugin.messages().get("quest.completed")
                .replace("%tier%", quest.tier().coloured())
                .replace("%quest%", quest.description())
                .replace("%money%", NumberFormatter.money(quest.rewardMoney()))
                .replace("%items%", granted.isEmpty() ? "nothing" : String.join(", ", granted))));
        plugin.sounds().promotion(player);
        plugin.announcements().checkPromotion(player);

        // Top-tier completions are worth telling the server about.
        if (quest.tier() == QuestTier.REAL
                && plugin.getConfig().getBoolean("quests.announce-top-tier", true)) {
            String broadcast = plugin.messages().get("quest.broadcast")
                    .replace("%player%", player.getName())
                    .replace("%quest%", quest.description())
                    .replace("%money%", NumberFormatter.money(quest.rewardMoney()));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) online.sendMessage(mm.deserialize(broadcast));
            }
        }

        int cap = plugin.getConfig().getInt("quests.daily-cap", 12);
        if (cap > 0 && done >= cap) {
            player.sendMessage(mm.deserialize(plugin.messages().get("quest.daily-cap")));
        }
    }

    public boolean atDailyCap(UUID player) {
        int cap = plugin.getConfig().getInt("quests.daily-cap", 12);
        return cap > 0 && completedToday.getOrDefault(player, 0) >= cap;
    }

    public int completedToday(UUID player) {
        return completedToday.getOrDefault(player, 0);
    }

    /** Called on the daily reset timer. */
    public void resetDaily() {
        completedToday.clear();
    }

    // ==================================================================
    // Persistence
    // ==================================================================

    private void persist(UUID player, Quest quest) {
        plugin.storage().execute("""
                INSERT INTO quest_progress
                    (player_uuid, template_id, tier, type, target, required,
                     progress, description, reward_money, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    template_id = ?, tier = ?, type = ?, target = ?, required = ?,
                    progress = ?, description = ?, reward_money = ?, updated_at = ?""",
                player.toString(), quest.templateId(), quest.tier().name(),
                quest.type().name(), quest.target(), quest.required(),
                quest.progress(), quest.description(),
                quest.rewardMoney().toPlainString(), System.currentTimeMillis(),
                quest.templateId(), quest.tier().name(), quest.type().name(),
                quest.target(), quest.required(), quest.progress(),
                quest.description(), quest.rewardMoney().toPlainString(),
                System.currentTimeMillis());
    }

    private void clearPersisted(UUID player) {
        plugin.storage().execute("DELETE FROM quest_progress WHERE player_uuid = ?",
                player.toString());
    }

    public void loadState() {
        plugin.storage().query("""
                SELECT player_uuid, template_id, tier, type, target, required,
                       progress, description, reward_money
                FROM quest_progress""",
                rs -> {
                    Map<UUID, Quest> loaded = new HashMap<>();
                    try {
                        while (rs.next()) {
                            QuestTier tier = QuestTier.valueOf(rs.getString("tier"));
                            QuestType type = QuestType.valueOf(rs.getString("type"));
                            Quest quest = new Quest(
                                    rs.getString("template_id"), tier, type,
                                    rs.getString("target"), rs.getInt("required"),
                                    rs.getString("description"),
                                    new BigDecimal(rs.getString("reward_money")),
                                    new ArrayList<>());
                            quest.progress(rs.getInt("progress"));
                            loaded.put(UUID.fromString(rs.getString("player_uuid")), quest);
                        }
                    } catch (Exception ignored) { }
                    return loaded;
                }).thenAccept(loaded -> {
                    if (loaded == null) return;
                    active.putAll(loaded);
                    plugin.getLogger().info("Restored " + loaded.size() + " active quests.");
                });
    }

    public void forget(UUID player) {
        offers.remove(player);
        rerolls.remove(player);
        paidRerolls.remove(player);
        rerollRefreshedAt.remove(player);
    }
}
