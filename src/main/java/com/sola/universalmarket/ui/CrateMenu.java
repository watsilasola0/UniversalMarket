package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.crate.CrateTier;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** The crate shop: seven tiers, cheapest to most expensive. */
public final class CrateMenu {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    public CrateMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    public void open(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>CRATES <dark_gray>\u2726", 5);
        BigDecimal balance = plugin.economy().balance(player);

        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        CrateTier[] tiers = CrateTier.values();

        for (int i = 0; i < tiers.length && i < slots.length; i++) {
            CrateTier tier = tiers[i];
            BigDecimal price = BigDecimal.valueOf(tier.price());
            boolean affordable = balance.compareTo(price) >= 0;

            List<String> lore = new ArrayList<>();
            lore.add("<gold><b>" + NumberFormatter.money(price));
            lore.add("");
            lore.add("<gray>Contents are worth anywhere from");
            lore.add("<red>a fraction</red> <gray>to</gray> <green>several times</green> <gray>the price.");
            lore.add("");
            lore.add("<gray>Possible loot:");
            String[] pool = tier.lootPool();
            for (int j = 0; j < Math.min(4, pool.length); j++) {
                lore.add("<dark_gray>  " + com.sola.universalmarket.util.Names.pretty(pool[j]));
            }
            if (pool.length > 4) lore.add("<dark_gray>  and more...");
            lore.add("");
            lore.add("<gray>YOUR BALANCE: <green>" + NumberFormatter.money(balance));
            lore.add("");
            lore.add(affordable
                    ? "<yellow>Click to buy one"
                    : "<red>Short by " + NumberFormatter.money(price.subtract(balance)));

            gui.set(slots[i], Gui.glowingIcon(tier.icon(),
                    tier.colour() + "<b>" + tier.display().toUpperCase() + " CRATE",
                    lore.toArray(new String[0])),
                    p -> { if (plugin.crates().buy(p, tier)) open(p); });
        }

        gui.set(31, Gui.icon(Material.PAPER,
                "<white><b>How crates work",
                "<gray>Buy a crate, place it down,",
                "<gray>and watch it roll.",
                "",
                "<gray>Most crates pay out a little under",
                "<gray>what you paid. A minority beat it,",
                "<gray>and a rare few pay many times over.",
                "",
                "<dark_gray>The result is decided the moment",
                "<dark_gray>you open it, not by the animation."));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }
}
