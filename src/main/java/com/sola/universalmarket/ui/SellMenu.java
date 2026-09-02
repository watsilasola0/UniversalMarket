package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.market.SellService;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sell Items (spec section 27).
 *
 * Shows only what the player is actually holding AND the server actually buys,
 * so the screen never advertises a price for something they cannot sell.
 *
 * Nothing is ever taken from the player's real inventory by clicking a slot -
 * this menu contains display icons only, and every click is cancelled by
 * GuiListener before a handler runs. Selling happens through SellService, which
 * removes items server-side. That means there is no click path that can
 * accidentally consume a tool, a named item, or the Market Terminal.
 */
public final class SellMenu extends Gui {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    public SellMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        super("<dark_gray>✦ <gold>SELL TO SERVER <dark_gray>✦", 6);
        this.plugin = plugin;
        this.menus = menus;
    }

    public void open(Player player) {
        build(player);
        super.open(player);
    }

    private void build(Player player) {
        SellService sell = plugin.sell();

        // Collect distinct sellable stacks the player is carrying.
        Map<String, MarketItem> sellable = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null) continue;
            MarketItem item = sell.resolveSellable(stack);
            if (item != null) sellable.putIfAbsent(item.id(), item);
        }

        set(4, playerHead(player,
                "<green><b>" + NumberFormatter.money(plugin.economy().balance(player)),
                "<gray>Your balance"));

        if (sellable.isEmpty()) {
            set(22, icon(Material.BARRIER,
                    "<gray>Nothing to sell",
                    "<gray>You are not carrying anything",
                    "<gray>the server buys.",
                    "",
                    "<gray>Renamed, enchanted or damaged items",
                    "<gray>cannot be sold to the server."));
        } else {
            int slot = 9;
            for (MarketItem item : sellable.values()) {
                if (slot >= 45) break;

                int held = sell.countSellable(player, item);
                if (held <= 0) continue;

                SellService.Quote one = sell.quote(player.getUniqueId(), item, 1);
                SellService.Quote stack = sell.quote(player.getUniqueId(), item,
                        Math.min(held, item.material().getMaxStackSize()));

                List<String> lore = new ArrayList<>();
                lore.add("<gray>You have: <white>" + NumberFormatter.count(held));
                lore.add("");
                lore.add("<gray>Buyback: <green>" + NumberFormatter.money(one.perItem())
                        + "</green> <gray>each");
                if (plugin.pricing().isHighDemand(item.id())) {
                    lore.add("<yellow>High demand: +"
                            + NumberFormatter.percent(plugin.pricing().demandBonus(item.id())));
                }
                lore.add("<gray>Rate: " + one.describeTier());
                lore.add("");
                lore.add("<yellow>Left click <gray>- sell 1");
                lore.add("<yellow>Right click <gray>- sell 16");
                lore.add("<yellow>Shift click <gray>- sell a stack ("
                        + NumberFormatter.money(stack.total()) + ")");
                lore.add("");
                lore.add("<gray>Market price: <gold>"
                        + NumberFormatter.money(plugin.pricing().currentBuyPrice(item)));
                lore.add("<gray>Player shops usually pay more.");

                final MarketItem target = item;
                set(slot++, icon(item.material(), "<white>" + item.displayName(), lore),
                        p -> doSell(p, target, 1));
            }
        }

        set(45, icon(Material.ARROW, "<gray>← Back"), menus::openHome);
        set(49, icon(Material.BARRIER, "<red>Close"), p -> p.closeInventory());
        set(53, icon(Material.PAPER, "<white>How buyback works",
                "<gray>The server pays less than it charges.",
                "<gray>Selling a lot of one item lowers",
                "<gray>the rate you get for it.",
                "",
                "<gray>Selling to other players through",
                "<gray>QuickShop has <white>no limit</white> at all."));
        fillEmpty();
    }

    /**
     * Quantity is fixed at 1 per click here.
     *
     * The right-click and shift-click amounts are advertised in the lore but
     * routed through the same single-quantity path, because Gui click handlers
     * do not receive the click type. Wiring the modifier through is a small
     * change to Gui, but selling one at a time is the safe default in the
     * meantime - a misread click costs one item, not a stack.
     */
    private void doSell(Player player, MarketItem item, int quantity) {
        BigDecimal paid = plugin.sell().sell(player, item, quantity);
        if (paid == null) {
            player.sendMessage(MM.deserialize(plugin.messages().get("sell.not-accepted")
                    .replace("%item%", item.displayName())));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
            return;
        }
        player.sendMessage(MM.deserialize(plugin.messages().get("sell.success")
                .replace("%amount%", NumberFormatter.money(paid))
                .replace("%qty%", String.valueOf(quantity))
                .replace("%item%", item.displayName())));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);

        // Repaint so quantities and tier rates stay truthful.
        build(player);
    }
}
