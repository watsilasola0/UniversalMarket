package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.gamble.MinesGame;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The gambling menus.
 *
 * Betting is entirely click-driven, as you asked: preset amounts, doubling and
 * halving buttons, and all-in. Nothing is ever typed in chat.
 */
public final class GambleMenu {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    /** Mines rounds in progress, and the stake locked into each. */
    private final Map<UUID, MinesGame> minesGames = new HashMap<>();
    private final Map<UUID, BigDecimal> minesStakes = new HashMap<>();
    private final Map<UUID, Integer> minesChoice = new HashMap<>();

    public GambleMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    // ==================================================================
    // Lobby
    // ==================================================================

    public void open(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>GAMBLING <dark_gray>\u2726", 5);

        gui.set(11, Gui.icon(Material.GOLD_INGOT,
                "<yellow><b>COINFLIP",
                "<gray>Double your bet, or lose it.",
                "",
                "<gray>Roughly a coin toss, slightly",
                "<gray>in the house's favour."),
                p -> { plugin.sounds().click(p); openCoinflip(p); });

        gui.set(13, Gui.icon(Material.TNT,
                "<red><b>MINES",
                "<gray>Reveal safe tiles for a rising",
                "<gray>multiplier. Cash out any time.",
                "",
                "<gray>More mines means bigger payouts",
                "<gray>and a shorter life expectancy."),
                p -> { plugin.sounds().click(p); openMinesSetup(p); });

        gui.set(15, Gui.icon(Material.ENDER_EYE,
                "<aqua><b>HIGH OR LOW",
                "<gray>Guess whether the next number",
                "<gray>beats the last one.",
                "",
                "<gray>Pays about 2x."),
                p -> { plugin.sounds().click(p); openHighLow(p, -1); });

        gui.set(31, betDisplay(player));
        addBetControls(gui, player, () -> open(player));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Bet controls, shared by every game
    // ==================================================================

    private org.bukkit.inventory.ItemStack betDisplay(Player player) {
        return Gui.icon(Material.SUNFLOWER,
                "<gold><b>Bet: " + plugin.gambling().formatBet(player),
                "<gray>Balance: <green>"
                        + NumberFormatter.money(plugin.economy().balance(player)),
                "",
                "<gray>Min <white>"
                        + NumberFormatter.money(BigDecimal.valueOf(plugin.gambling().minBet()))
                        + "</white>  Max <white>"
                        + NumberFormatter.money(plugin.gambling().maxBet()));
    }

    private void addBetControls(Gui gui, Player player, Runnable repaint) {
        gui.set(28, Gui.icon(Material.RED_DYE, "<red>\u2212 Halve bet"),
                p -> {
                    plugin.gambling().setBet(p,
                            plugin.gambling().betOf(p).divide(BigDecimal.valueOf(2),
                                    0, java.math.RoundingMode.DOWN));
                    plugin.sounds().click(p);
                    repaint.run();
                });
        gui.set(29, Gui.icon(Material.ORANGE_DYE, "<gold>\u2212 10K"),
                p -> { plugin.gambling().adjustBet(p, BigDecimal.valueOf(-10_000L));
                       plugin.sounds().click(p); repaint.run(); });
        gui.set(33, Gui.icon(Material.LIME_DYE, "<green>+ 10K"),
                p -> { plugin.gambling().adjustBet(p, BigDecimal.valueOf(10_000L));
                       plugin.sounds().click(p); repaint.run(); });
        gui.set(34, Gui.icon(Material.GREEN_DYE, "<green>+ Double bet"),
                p -> {
                    plugin.gambling().setBet(p,
                            plugin.gambling().betOf(p).multiply(BigDecimal.valueOf(2)));
                    plugin.sounds().click(p);
                    repaint.run();
                });
        gui.set(40, Gui.icon(Material.NETHER_STAR, "<dark_purple><b>ALL IN",
                "<gray>Bet everything you have."),
                p -> { plugin.gambling().allIn(p); plugin.sounds().confirm(p); repaint.run(); });
    }

    // ==================================================================
    // Coinflip
    // ==================================================================

    public void openCoinflip(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <yellow>COINFLIP <dark_gray>\u2726", 5);

        gui.set(11, Gui.icon(Material.GOLD_NUGGET,
                "<yellow><b>HEADS",
                "<gray>Bet: <gold>" + plugin.gambling().formatBet(player),
                "",
                "<yellow>Click to flip"),
                p -> flip(p, true));

        gui.set(15, Gui.icon(Material.IRON_NUGGET,
                "<white><b>TAILS",
                "<gray>Bet: <gold>" + plugin.gambling().formatBet(player),
                "",
                "<yellow>Click to flip"),
                p -> flip(p, false));

        gui.set(31, betDisplay(player));
        addBetControls(gui, player, () -> openCoinflip(player));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void flip(Player player, boolean heads) {
        BigDecimal bet = plugin.gambling().betOf(player);
        if (!plugin.gambling().canAfford(player)) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.broke")));
            plugin.sounds().broke(player);
            return;
        }
        if (!plugin.gambling().takeStake(player, bet)) { plugin.sounds().error(player); return; }

        boolean won = plugin.gambling().coinflipWins();
        if (won) {
            BigDecimal payout = plugin.gambling().payWin(player, bet, 2.0);
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.won")
                    .replace("%amount%", NumberFormatter.money(payout.subtract(bet)))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().bigBuy(player);
        } else {
            plugin.gambling().recordLoss(player, bet);
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.lost")
                    .replace("%amount%", NumberFormatter.money(bet))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().error(player);
        }
        openCoinflip(player);
    }

    // ==================================================================
    // Mines
    // ==================================================================

    public void openMinesSetup(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <red>MINES <dark_gray>\u2726", 5);
        int chosen = minesChoice.getOrDefault(player.getUniqueId(), 3);

        int[] options = {1, 3, 5, 10, 15, 20, 24};
        int[] slots = {10, 11, 12, 13, 14, 15, 16};

        for (int i = 0; i < options.length; i++) {
            int mines = options[i];
            double oneTile = plugin.gambling().minesMultiplier(MinesGame.TILES, mines, 1);
            double fiveTile = plugin.gambling().minesMultiplier(MinesGame.TILES, mines,
                    Math.min(5, MinesGame.TILES - mines));

            gui.set(slots[i], Gui.icon(mines == chosen ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                    (mines == chosen ? "<green><b>" : "<white>") + mines + " mines",
                    "<gray>Per safe tile: <yellow>"
                            + String.format("%.2f", oneTile) + "x",
                    "<gray>After 5 tiles: <gold>"
                            + String.format("%.2f", fiveTile) + "x",
                    "",
                    mines <= 3 ? "<green>Low risk" : mines <= 10 ? "<yellow>Fair risk" : "<red>High risk",
                    "",
                    "<yellow>Click to select"),
                    p -> { minesChoice.put(p.getUniqueId(), mines);
                           plugin.sounds().click(p); openMinesSetup(p); });
        }

        gui.set(22, Gui.icon(Material.TNT, "<red><b>START ROUND",
                "<gray>Mines: <white>" + chosen,
                "<gray>Stake: <gold>" + plugin.gambling().formatBet(player),
                "",
                "<yellow>Click to begin"),
                p -> startMines(p, minesChoice.getOrDefault(p.getUniqueId(), 3)));

        gui.set(31, betDisplay(player));
        addBetControls(gui, player, () -> openMinesSetup(player));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void startMines(Player player, int mineCount) {
        if (!plugin.gambling().canAfford(player)) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.broke")));
            plugin.sounds().broke(player);
            return;
        }
        BigDecimal bet = plugin.gambling().betOf(player);
        if (!plugin.gambling().takeStake(player, bet)) { plugin.sounds().error(player); return; }

        minesGames.put(player.getUniqueId(), new MinesGame(mineCount));
        minesStakes.put(player.getUniqueId(), bet);
        plugin.sounds().confirm(player);
        openMinesBoard(player);
    }

    private void openMinesBoard(Player player) {
        MinesGame game = minesGames.get(player.getUniqueId());
        if (game == null) { openMinesSetup(player); return; }

        BigDecimal stake = minesStakes.getOrDefault(player.getUniqueId(), BigDecimal.ZERO);
        double multiplier = plugin.gambling().minesMultiplier(
                MinesGame.TILES, game.mineCount(), game.revealedCount());
        BigDecimal cashValue = stake.multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, java.math.RoundingMode.DOWN);

        Gui gui = new Gui("<dark_gray>\u2726 <red>MINES <dark_gray>\u2726 <gray>"
                + game.mineCount() + " mines", 6);

        // 5x5 board inset from the left edge.
        for (int tile = 0; tile < MinesGame.TILES; tile++) {
            int row = tile / 5;
            int col = tile % 5;
            int slot = row * 9 + col + 2;
            final int index = tile;

            if (game.isOver()) {
                boolean mine = game.isMine(tile);
                gui.set(slot, Gui.icon(mine ? Material.TNT
                                : (game.isRevealed(tile) ? Material.LIME_STAINED_GLASS_PANE
                                                         : Material.GRAY_STAINED_GLASS_PANE),
                        mine ? "<red>Mine" : "<green>Safe"));
            } else if (game.isRevealed(tile)) {
                gui.set(slot, Gui.icon(Material.LIME_STAINED_GLASS_PANE, "<green>Safe"));
            } else {
                gui.set(slot, Gui.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<gray>Hidden tile", "<yellow>Click to reveal"),
                        p -> revealTile(p, index));
            }
        }

        gui.set(16, Gui.icon(Material.PAPER,
                "<white><b>Round",
                "<gray>Stake: <gold>" + NumberFormatter.money(stake),
                "<gray>Revealed: <white>" + game.revealedCount(),
                "<gray>Multiplier: <yellow>" + String.format("%.2f", multiplier) + "x"));

        if (!game.isOver() && game.revealedCount() > 0) {
            gui.set(25, Gui.icon(Material.EMERALD_BLOCK,
                    "<green><b>CASH OUT",
                    "<gray>Collect <green>" + NumberFormatter.money(cashValue),
                    "<gray>Profit: <green>"
                            + NumberFormatter.money(cashValue.subtract(stake))),
                    p -> cashOutMines(p));
        }
        if (game.isOver()) {
            gui.set(25, Gui.icon(Material.LIME_CONCRETE, "<green><b>Play again"),
                    p -> { minesGames.remove(p.getUniqueId());
                           minesStakes.remove(p.getUniqueId());
                           openMinesSetup(p); });
        }

        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back",
                game.isOver() ? "<gray>Round finished."
                              : "<red>Leaving forfeits your stake."),
                p -> {
                    MinesGame current = minesGames.get(p.getUniqueId());
                    if (current != null && !current.isOver()) {
                        // Walking away mid-round loses the stake, exactly as if
                        // the next tile had been a mine. Otherwise a player could
                        // escape every losing board for free.
                        plugin.gambling().recordLoss(p,
                                minesStakes.getOrDefault(p.getUniqueId(), BigDecimal.ZERO));
                    }
                    minesGames.remove(p.getUniqueId());
                    minesStakes.remove(p.getUniqueId());
                    open(p);
                });
        gui.fillEmpty().open(player);
    }

    private void revealTile(Player player, int tile) {
        MinesGame game = minesGames.get(player.getUniqueId());
        if (game == null || game.isOver()) return;

        boolean safe = game.reveal(tile);
        if (!safe) {
            BigDecimal stake = minesStakes.getOrDefault(player.getUniqueId(), BigDecimal.ZERO);
            plugin.gambling().recordLoss(player, stake);
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.lost")
                    .replace("%amount%", NumberFormatter.money(stake))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().error(player);
        } else {
            plugin.sounds().click(player);
            if (game.isCleared()) cashOutMines(player);
        }
        openMinesBoard(player);
    }

    private void cashOutMines(Player player) {
        MinesGame game = minesGames.get(player.getUniqueId());
        if (game == null || game.isOver()) return;

        BigDecimal stake = minesStakes.getOrDefault(player.getUniqueId(), BigDecimal.ZERO);
        double multiplier = plugin.gambling().minesMultiplier(
                MinesGame.TILES, game.mineCount(), game.revealedCount());
        game.cashOut();

        BigDecimal payout = plugin.gambling().payWin(player, stake, multiplier);
        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.cashout")
                .replace("%amount%", NumberFormatter.money(payout))
                .replace("%multiplier%", String.format("%.2f", multiplier))
                .replace("%balance%",
                        NumberFormatter.money(plugin.economy().balance(player)))));
        plugin.sounds().bigBuy(player);
    }

    // ==================================================================
    // High or Low
    // ==================================================================

    private final Map<UUID, Integer> highLowNumber = new HashMap<>();

    public void openHighLow(Player player, int current) {
        int number = current >= 0 ? current
                : highLowNumber.computeIfAbsent(player.getUniqueId(),
                        k -> 1 + (int) (Math.random() * 100));
        highLowNumber.put(player.getUniqueId(), number);

        Gui gui = new Gui("<dark_gray>\u2726 <aqua>HIGH OR LOW <dark_gray>\u2726", 5);

        gui.set(13, Gui.icon(Material.PAPER,
                "<white><b>Current number: <yellow>" + number,
                "<gray>Will the next one be higher",
                "<gray>or lower? (1-100)",
                "",
                "<gray>Ties lose."));

        gui.set(11, Gui.icon(Material.LIME_CONCRETE, "<green><b>HIGHER",
                "<gray>Bet: <gold>" + plugin.gambling().formatBet(player)),
                p -> playHighLow(p, true));
        gui.set(15, Gui.icon(Material.RED_CONCRETE, "<red><b>LOWER",
                "<gray>Bet: <gold>" + plugin.gambling().formatBet(player)),
                p -> playHighLow(p, false));

        gui.set(31, betDisplay(player));
        addBetControls(gui, player, () -> openHighLow(player, number));

        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void playHighLow(Player player, boolean higher) {
        if (!plugin.gambling().canAfford(player)) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.broke")));
            plugin.sounds().broke(player);
            return;
        }
        BigDecimal bet = plugin.gambling().betOf(player);
        if (!plugin.gambling().takeStake(player, bet)) { plugin.sounds().error(player); return; }

        int current = highLowNumber.getOrDefault(player.getUniqueId(), 50);
        int next = 1 + (int) (Math.random() * 100);
        boolean won = higher ? next > current : next < current;

        // The multiplier reflects the real odds of the guess, so betting
        // "higher" on 95 pays far more than betting "higher" on 5.
        double chance = higher ? (100 - current) / 100.0 : (current - 1) / 100.0;
        double multiplier = chance <= 0 ? 1.0
                : Math.max(1.05, (1.0 / chance) * (1.0 - plugin.gambling().houseEdge()));

        if (won) {
            BigDecimal payout = plugin.gambling().payWin(player, bet, multiplier);
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.highlow-won")
                    .replace("%number%", String.valueOf(next))
                    .replace("%multiplier%", String.format("%.2f", multiplier))
                    .replace("%amount%", NumberFormatter.money(payout))));
            plugin.sounds().bigBuy(player);
        } else {
            plugin.gambling().recordLoss(player, bet);
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.highlow-lost")
                    .replace("%number%", String.valueOf(next))
                    .replace("%amount%", NumberFormatter.money(bet))));
            plugin.sounds().error(player);
        }
        highLowNumber.put(player.getUniqueId(), next);
        openHighLow(player, next);
    }

    public void forget(UUID player) {
        minesGames.remove(player);
        minesStakes.remove(player);
        minesChoice.remove(player);
        highLowNumber.remove(player);
    }
}
