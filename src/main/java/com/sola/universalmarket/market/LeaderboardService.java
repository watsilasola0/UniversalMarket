package com.sola.universalmarket.market;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Balance leaderboard, plus the optional on-screen sidebar.
 *
 * Rankings are rebuilt on a timer and cached, never computed per lookup. Reading
 * a balance goes through Vault, and Vault providers are not guaranteed to be
 * thread safe, so the rebuild runs on the main thread - which is fine because it
 * is a handful of lookups every couple of minutes, not per tick.
 *
 * The sidebar is OPT-IN, off by default, toggled with /um board. Forcing a
 * scoreboard on everyone would stomp on any other plugin using the sidebar slot,
 * and a player only gets our scoreboard object while they have it switched on.
 */
public final class LeaderboardService {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final List<Entry> rankings = new ArrayList<>();
    /**
     * Opt-OUT set. The sidebar is on for everyone by default, so we track only
     * the people who chose to hide it. Storing hidden rather than shown means a
     * brand new player gets the board with no extra bookkeeping on join.
     */
    private final Set<UUID> sidebarHidden = new HashSet<>();

    public record Entry(UUID uuid, String name, BigDecimal balance) { }

    public LeaderboardService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Refresh
    // ==================================================================

    public void refresh() {
        List<Entry> fresh = new ArrayList<>();

        // Everyone online, plus anyone who has played before, deduplicated.
        Set<UUID> seen = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (seen.add(online.getUniqueId())) {
                fresh.add(new Entry(online.getUniqueId(), online.getName(),
                        plugin.economy().balance(online)));
            }
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() == null) continue;
            if (!offline.hasPlayedBefore()) continue;
            if (!seen.add(offline.getUniqueId())) continue;
            fresh.add(new Entry(offline.getUniqueId(), offline.getName(),
                    plugin.economy().balance(offline)));
        }

        fresh.sort(Comparator.comparing(Entry::balance).reversed());

        synchronized (rankings) {
            rankings.clear();
            rankings.addAll(fresh);
        }
        updateAllSidebars();
    }

    public List<Entry> top(int limit) {
        synchronized (rankings) {
            return new ArrayList<>(rankings.subList(0, Math.min(limit, rankings.size())));
        }
    }

    /** 1-based rank, or -1 when the player is not ranked yet. */
    public int rankOf(UUID uuid) {
        synchronized (rankings) {
            for (int i = 0; i < rankings.size(); i++) {
                if (rankings.get(i).uuid().equals(uuid)) return i + 1;
            }
        }
        return -1;
    }

    public int size() {
        synchronized (rankings) {
            return rankings.size();
        }
    }

    // ==================================================================
    // Sidebar
    // ==================================================================

    public boolean isSidebarOn(UUID uuid) {
        if (!plugin.getConfig().getBoolean("leaderboard.sidebar.enabled-by-default", true)) {
            return false;
        }
        return !sidebarHidden.contains(uuid);
    }

    /** Returns the new state. */
    public boolean toggleSidebar(Player player) {
        if (isSidebarOn(player.getUniqueId())) {
            sidebarHidden.add(player.getUniqueId());
            clearSidebar(player);
            return false;
        }
        sidebarHidden.remove(player.getUniqueId());
        updateSidebar(player);
        return true;
    }

    /** Show the board to a joining player, unless they hid it this session. */
    public void onJoin(Player player) {
        if (isSidebarOn(player.getUniqueId())) updateSidebar(player);
    }

    /**
     * Fast repaint for the live timer.
     *
     * Only refreshes balances of ONLINE players and re-sorts the cached list.
     * The expensive part - walking every offline player and asking Vault for
     * each balance - stays on the slow timer. That is what makes a 3 second
     * refresh affordable.
     */
    public void refreshLive() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        synchronized (rankings) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                BigDecimal balance = plugin.economy().balance(online);
                boolean found = false;
                for (int i = 0; i < rankings.size(); i++) {
                    if (rankings.get(i).uuid().equals(online.getUniqueId())) {
                        rankings.set(i, new Entry(online.getUniqueId(), online.getName(), balance));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    rankings.add(new Entry(online.getUniqueId(), online.getName(), balance));
                }
            }
            rankings.sort(Comparator.comparing(Entry::balance).reversed());
        }
        updateAllSidebars();
    }

    public void clearSidebar(Player player) {
        // Hand them back the server default rather than an empty board, so we do
        // not leave them without whatever scoreboard another plugin was using.
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Throwable ignored) { }
    }

    public void forget(UUID uuid) {
        sidebarHidden.remove(uuid);
    }

    private void updateAllSidebars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSidebarOn(player.getUniqueId())) updateSidebar(player);
        }
    }

    public void updateSidebar(Player player) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective(
                    "um_board", Criteria.DUMMY,
                    mm.deserialize("<gold><b>✦ RICHEST ✦"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            // Quests were removed, so the board gets the full ten rows back.

            List<Entry> top = top(10);
            int score = top.size() + 3;

            for (int i = 0; i < top.size(); i++) {
                Entry entry = top.get(i);
                String colour = switch (i) {
                    case 0 -> "<gold>";
                    case 1 -> "<white>";
                    case 2 -> "<yellow>";
                    default -> "<gray>";
                };
                // Every sidebar line must be a UNIQUE string or entries overwrite
                // each other, so the rank number is always part of the text.
                String line = colour + "#" + (i + 1) + " <white>" + entry.name()
                        + " <green>" + NumberFormatter.money(entry.balance());
                objective.getScore(legacy(line)).setScore(score--);
            }

            // Show the viewer their own standing even when outside the top ten.
            int rank = rankOf(player.getUniqueId());
            objective.getScore(legacy("<dark_gray>─────────────")).setScore(score--);
            objective.getScore(legacy("<gray>You: <white>"
                    + (rank > 0 ? "#" + rank : "unranked")
                    + " <green>" + NumberFormatter.money(plugin.economy().balance(player))))
                    .setScore(score);
            player.setScoreboard(board);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not render the sidebar for "
                    + player.getName() + ": " + t);
        }
    }

    /**
     * Sidebar entries are plain strings, not Components, so MiniMessage has to be
     * flattened to legacy section-sign colours here.
     */
    private String legacy(String miniMessage) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(mm.deserialize(miniMessage));
    }
}
