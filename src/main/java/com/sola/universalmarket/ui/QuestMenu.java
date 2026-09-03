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

        gui.set(31, rerollButton(player), p -> {
            if (plugin.quests().hasFreeReroll(p.getUniqueId())) {
                if (plugin.quests().reroll(p)) { plugin.sounds().page(p); open(p); }
                else plugin.sounds().error(p);
                return;
            }
            if (plugin.quests().payForReroll(p)) { plugin.sounds().page(p); open(p); }
            else {
                p.sendMessage(Gui.MM.deserialize(plugin.messages().get("quest.reroll-broke")));
                plugin.sounds().broke(p);
            }
        });

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    /**
     * The reroll control, rebuilt each second so the refresh timer ticks.
     *
     * Free rerolls come back on a timer; once they are gone you can buy more,
     * and each purchase doubles the price. The doubling resets when the free
     * allowance refreshes, so paying is a burst option rather than something
     * you can lean on indefinitely.
     */
    private org.bukkit.inventory.ItemStack rerollButton(Player player) {
        var quests = plugin.quests();
        java.util.UUID id = player.getUniqueId();

        int used = quests.rerollsUsed(id);
        int allowed = quests.rerollsAllowed();
        boolean free = quests.hasFreeReroll(id);
        java.math.BigDecimal cost = quests.nextRerollCost(id);
        java.math.BigDecimal balance = plugin.economy().balance(player);
        boolean affordable = balance.compareTo(cost) >= 0;

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Draw three new quests.");
        lore.add("");
        lore.add("<gray>Free rerolls: <white>" + (allowed - used) + "</white> of " + allowed);
        lore.add("<gray>Refreshes in <yellow>"
                + NumberFormatter.duration(quests.rerollRefreshInMillis(id)) + "</yellow>");
        lore.add("");
        if (free) {
            lore.add("<green>Click to reroll for free");
        } else {
            lore.add("<gray>Next reroll costs <gold>" + NumberFormatter.money(cost));
            lore.add("<dark_gray>The price doubles each time you buy one.");
            lore.add("");
            lore.add(affordable
                    ? "<yellow>Click to buy a reroll"
                    : "<red>You cannot afford this");
        }

        Material icon = free ? Material.LIME_CONCRETE
                : (affordable ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
        String title = free ? "<green><b>Reroll offers"
                : (affordable ? "<yellow><b>Buy a reroll" : "<dark_gray>Reroll offers");

        return Gui.icon(icon, title, lore);
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
