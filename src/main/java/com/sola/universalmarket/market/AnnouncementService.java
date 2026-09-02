package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide announcements: new market cycles, and wealth tier promotions.
 *
 * Both are OFF by default in config. A broadcast on a small friends server is
 * high-visibility by nature, and something that fires unexpectedly during a
 * build session is an annoyance rather than a feature - so you switch them on
 * deliberately after seeing what they look like.
 *
 * Tier promotions only ever announce going UP. Broadcasting that someone just
 * dropped from Tycoon to Wealthy after a bad trade would be a way of publicly
 * needling a player for losing money, which is not a thing a market plugin
 * should do.
 */
public final class AnnouncementService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    /** Last known tier index per player, so we only fire on an actual change. */
    private final Map<UUID, Integer> lastTier = new HashMap<>();

    public AnnouncementService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // 24 - market cycle
    // ==================================================================

    public void announceCycle(boolean force) {
        if (!force && !plugin.getConfig().getBoolean("announcements.market-cycle.enabled", false)) {
            return;
        }

        StringBuilder deals = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Double> entry : plugin.pricing().dailyDeals().entrySet()) {
            MarketItem item = plugin.catalog().byId(entry.getKey());
            if (item == null) continue;
            if (shown > 0) deals.append("<gray>, ");
            deals.append("<white>").append(item.displayName())
                 .append(" <yellow>-").append(NumberFormatter.percent(entry.getValue()));
            if (++shown >= 3) break;
        }
        if (shown == 0) deals.append("<gray>none today");

        StringBuilder demand = new StringBuilder();
        shown = 0;
        for (Map.Entry<String, Double> entry : plugin.pricing().highDemand().entrySet()) {
            MarketItem item = plugin.catalog().byId(entry.getKey());
            if (item == null) continue;
            if (shown > 0) demand.append("<gray>, ");
            demand.append("<white>").append(item.displayName())
                  .append(" <green>+").append(NumberFormatter.percent(entry.getValue()));
            if (++shown >= 3) break;
        }
        if (shown == 0) demand.append("<gray>none today");

        Component message = mm.deserialize("""
                <dark_gray>─────────────────────────────
                <gold><b>✦ THE MARKET HAS RESET ✦</b>
                <gray>Daily deals: %deals%
                <gray>High demand: %demand%
                <gray>Next reset in <yellow>%next%</yellow>  ·  <white>/um</white> to browse
                <dark_gray>─────────────────────────────"""
                .replace("%deals%", deals.toString())
                .replace("%demand%", demand.toString())
                .replace("%next%", NumberFormatter.duration(plugin.pricing().cycleEndsInMillis())));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            plugin.sounds().cycle(player);
        }
    }

    /** Short version shown to a player when they log in mid-cycle. */
    public void sendCycleDigest(Player player) {
        if (!plugin.getConfig().getBoolean("announcements.market-cycle.digest-on-join", false)) {
            return;
        }
        player.sendMessage(mm.deserialize(
                "<gray>Market: <yellow>" + plugin.pricing().dailyDeals().size()
                + "</yellow> deals, <green>" + plugin.pricing().highDemand().size()
                + "</green> in high demand. Resets in <white>"
                + NumberFormatter.duration(plugin.pricing().cycleEndsInMillis())
                + "</white>. <gray>Use <white>/um</white>."));
    }

    // ==================================================================
    // 25 - wealth tier promotion
    // ==================================================================

    /** Record a player's tier without announcing, used on join. */
    public void primeTier(Player player) {
        lastTier.put(player.getUniqueId(), tierIndexOf(plugin.economy().balance(player)));
    }

    /**
     * Check whether a player has climbed a tier and announce if so.
     * Called after any balance-changing action.
     */
    public void checkPromotion(Player player) {
        if (player == null || !player.isOnline()) return;

        int now = tierIndexOf(plugin.economy().balance(player));
        Integer before = lastTier.put(player.getUniqueId(), now);
        if (before == null || now <= before) return;   // never announce a fall

        if (!plugin.getConfig().getBoolean("announcements.wealth-tier.enabled", false)) return;
        broadcastPromotion(player, now);
    }

    public void broadcastPromotion(Player player, int tierIndex) {
        var tiers = plugin.getConfig().getMapList("wealth-tiers");
        if (tierIndex < 0 || tierIndex >= tiers.size()) return;

        Object raw = tiers.get(tierIndex);
        if (!(raw instanceof Map<?, ?> map)) return;
        String name = String.valueOf(map.get("name"));
        String colour = String.valueOf(map.get("color"));

        Component message = mm.deserialize("""
                <dark_gray>─────────────────────────────
                <white><b>%player%</b><gray> has reached %colour%<b>%tier%</b>
                <gray>Balance: <green>%balance%
                <dark_gray>─────────────────────────────"""
                .replace("%player%", player.getName())
                .replace("%colour%", colour)
                .replace("%tier%", name)
                .replace("%balance%", NumberFormatter.money(plugin.economy().balance(player))));

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
            plugin.sounds().promotion(online);
        }
    }

    /** Index into the configured wealth-tiers list, or 0. */
    public int tierIndexOf(BigDecimal balance) {
        var tiers = plugin.getConfig().getMapList("wealth-tiers");
        int index = 0;
        for (int i = 0; i < tiers.size(); i++) {
            if (!(tiers.get(i) instanceof Map<?, ?> map)) continue;
            try {
                long threshold = Long.parseLong(String.valueOf(map.get("threshold")));
                if (balance.compareTo(BigDecimal.valueOf(threshold)) >= 0) index = i;
            } catch (Exception ignored) { }
        }
        return index;
    }

    public int tierCount() {
        return plugin.getConfig().getMapList("wealth-tiers").size();
    }

    public void forget(UUID uuid) {
        lastTier.remove(uuid);
    }
}
