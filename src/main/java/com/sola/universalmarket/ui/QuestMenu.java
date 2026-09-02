package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.quest.Quest;
import com.sola.universalmarket.quest.QuestTier;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The quest board: your active quest, or three offers to choose from. */
public final class QuestMenu {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    public QuestMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    public void open(Player player) {
        if (plugin.quests().hasActive(player.getUniqueId())) openActive(player);
        else openOffers(player);
    }

    // ==================================================================
    // Active quest
    // ==================================================================

    private void openActive(Player player) {
        Quest quest = plugin.quests().activeFor(player.getUniqueId());
        if (quest == null) { openOffers(player); return; }

        Gui gui = new Gui("<dark_gray>\u2726 <gold>YOUR QUEST <dark_gray>\u2726", 5);

        gui.live(13, () -> {
            Quest current = plugin.quests().activeFor(player.getUniqueId());
            if (current == null) return Gui.icon(Material.PAPER, "<gray>No active quest");
            List<String> lore = new ArrayList<>();
            lore.add(current.tier().coloured());
            lore.add("");
            lore.add("<white>" + current.description());
            lore.add("");
            lore.add(current.progressBar());
            lore.add("<gray>Progress: <white>" + current.progressText());
            lore.add("");
            lore.add("<gray>Reward:");
            lore.add("<green>  " + NumberFormatter.money(current.rewardMoney()));
            for (Quest.RewardItem reward : current.rewardItems()) {
                lore.add("<gray>  " + reward.amount() + "x "
                        + Quest.prettyName(reward.material().name()));
            }
            return Gui.icon(tierIcon(current.tier()),
                    "<white><b>Active quest", lore);
        });

        gui.set(29, Gui.icon(Material.RED_CONCRETE,
                "<red><b>\u2715 Abandon quest",
                "<gray>You lose all progress on it.",
                "<gray>Your reroll allowance resets."),
                p -> { plugin.quests().cancel(p); open(p); });

        gui.set(33, Gui.icon(Material.PAPER,
                "<white>Completed today: <yellow>"
                        + plugin.quests().completedToday(player.getUniqueId()),
                "<gray>Daily cap: <white>"
                        + plugin.getConfig().getInt("quests.daily-cap", 12),
                "",
                "<gray>Progress shows live on your",
                "<gray>sidebar as you play."));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Offers
    // ==================================================================

    private void openOffers(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>NEW QUEST <dark_gray>\u2726", 5);

        if (plugin.quests().atDailyCap(player.getUniqueId())) {
            gui.set(22, Gui.icon(Material.WHITE_STAINED_GLASS_PANE,
                    "<red>Daily limit reached",
                    "<gray>You have completed <white>"
                            + plugin.quests().completedToday(player.getUniqueId())
                            + "</white> quests today.",
                    "",
                    "<gray>Come back tomorrow."));
            gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                    p -> { plugin.sounds().click(p); menus.openHome(p); });
            gui.fillEmpty().open(player);
            return;
        }

        List<Quest> offers = plugin.quests().offersFor(player);
        int[] slots = {11, 13, 15};

        for (int i = 0; i < offers.size() && i < slots.length; i++) {
            Quest quest = offers.get(i);
            final int index = i;

            List<String> lore = new ArrayList<>();
            lore.add(quest.tier().coloured());
            lore.add("");
            lore.add("<white>" + quest.description());
            lore.add("");
            lore.add("<gray>Reward:");
            lore.add("<green>  " + NumberFormatter.money(quest.rewardMoney()));
            for (Quest.RewardItem reward : quest.rewardItems()) {
                lore.add("<gray>  " + reward.amount() + "x "
                        + Quest.prettyName(reward.material().name()));
            }
            lore.add("");
            lore.add("<yellow>Click to accept");

            gui.set(slots[i], Gui.icon(tierIcon(quest.tier()),
                    quest.tier().colour() + "<b>" + quest.tier().display(), lore),
                    p -> {
                        if (plugin.quests().accept(p, index)) open(p);
                        else plugin.sounds().error(p);
                    });
        }

        int used = plugin.quests().rerollsUsed(player.getUniqueId());
        int allowed = plugin.quests().rerollsAllowed();
        boolean canReroll = used < allowed;

        gui.set(31, Gui.icon(canReroll ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                (canReroll ? "<green><b>" : "<dark_gray>") + "Reroll offers",
                "<gray>Draw three new quests.",
                "",
                "<gray>Used: <white>" + used + "</white> of <white>" + allowed,
                canReroll ? "<yellow>Click to reroll" : "<red>No rerolls left"),
                p -> {
                    if (plugin.quests().reroll(p)) { plugin.sounds().page(p); open(p); }
                    else plugin.sounds().error(p);
                });

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    private Material tierIcon(QuestTier tier) {
        return switch (tier) {
            case EASY -> Material.WOODEN_PICKAXE;
            case MEDIUM -> Material.IRON_PICKAXE;
            case HARD -> Material.DIAMOND_PICKAXE;
            case REAL -> Material.NETHERITE_PICKAXE;
        };
    }
}
