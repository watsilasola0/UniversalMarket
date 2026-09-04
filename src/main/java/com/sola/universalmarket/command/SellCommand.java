package com.sola.universalmarket.command;

import com.sola.universalmarket.UniversalMarketPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * /sell - hands the player a Market Stall chest.
 *
 * Placing it, filling it with one item type and closing it starts the pricing
 * flow. Everything after that lives in SellFlowService.
 */
public final class SellCommand implements CommandExecutor {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SellCommand(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm.deserialize(plugin.messages().get("general.player-only")));
            return true;
        }
        if (!player.hasPermission("universalmarket.sell")) {
            player.sendMessage(mm.deserialize(plugin.messages().get("general.no-permission")));
            return true;
        }

        ItemStack stall = plugin.sellFlow().createSellChest();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stall);
        if (!leftover.isEmpty()) {
            // Don't drop it on the floor where it could be lost or griefed.
            player.sendMessage(mm.deserialize(plugin.messages().get("terminal.no-space")));
            plugin.sounds().error(player);
            return true;
        }

        player.sendMessage(mm.deserialize(plugin.messages().get("sell.stall-given")));
        plugin.sounds().click(player);
        return true;
    }
}
