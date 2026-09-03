package com.sola.universalmarket.ui;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.gamble.BlackjackGame;
import com.sola.universalmarket.gamble.CrashGame;
import com.sola.universalmarket.gamble.MinesGame;
import com.sola.universalmarket.gamble.TowersGame;
import com.sola.universalmarket.gamble.WheelGame;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All five gambling games.
 *
 * None of them is a coin toss. Every game either lets the player choose their
 * own risk profile (Mines, Towers, Wheel), rewards a decision made under
 * pressure (Crash), or involves real skill (21). That is what makes them worth
 * playing more than once.
 *
 * Betting is entirely click-driven: ten increment buttons, no chat input.
 */
public final class GambleMenu {

    private final UniversalMarketPlugin plugin;
    private final MarketMenus menus;

    private final Map<UUID, MinesGame> mines = new HashMap<>();
    private final Map<UUID, Integer> mineChoice = new HashMap<>();
    private final Map<UUID, TowersGame> towers = new HashMap<>();
    private final Map<UUID, Integer> towerWidth = new HashMap<>();
    private final Map<UUID, BlackjackGame> blackjack = new HashMap<>();
    private final Map<UUID, CrashGame> crash = new HashMap<>();
    private final Map<UUID, BigDecimal> stakes = new HashMap<>();

    public GambleMenu(UniversalMarketPlugin plugin, MarketMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    // ==================================================================
    // Lobby
    // ==================================================================

    public void open(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>GAMBLING <dark_gray>\u2726", 6);

        gui.set(10, Gui.icon(Material.TNT, "<red><b>MINES",
                "<gray>Reveal safe tiles for a rising",
                "<gray>multiplier. Cash out any time.",
                "",
                "<gray>You choose how many mines,",
                "<gray>so you choose the risk."),
                p -> { plugin.sounds().click(p); openMinesSetup(p); });

        gui.set(12, Gui.icon(Material.BRICKS, "<gold><b>TOWERS",
                "<gray>Climb eight floors, one tile",
                "<gray>per floor. One is a trap.",
                "",
                "<gray>Bank at any floor or push on."),
                p -> { plugin.sounds().click(p); openTowersSetup(p); });

        gui.set(14, Gui.icon(Material.PAPER, "<white><b>21",
                "<gray>Beat the dealer without",
                "<gray>going over twenty-one.",
                "",
                "<gray>Real deck, real odds.",
                "<gray>Natural 21 pays <yellow>2.5x</yellow>."),
                p -> { plugin.sounds().click(p); startBlackjack(p); });

        gui.set(16, Gui.icon(Material.FIREWORK_ROCKET, "<aqua><b>CRASH",
                "<gray>The multiplier climbs. Cash out",
                "<gray>before it crashes.",
                "",
                "<gray>Most rounds die early.",
                "<gray>A rare few run a long way."),
                p -> { plugin.sounds().click(p); openCrashSetup(p); });

        gui.set(22, Gui.icon(Material.SUNFLOWER, "<yellow><b>WHEEL",
                "<gray>Spin for a multiplier.",
                "",
                "<gray>Three risk profiles, same",
                "<gray>expected value - pick your variance."),
                p -> { plugin.sounds().click(p); openWheelSetup(p); });

        addBetControls(gui, player, () -> open(player));
        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); menus.openHome(p); });
        gui.fillEmpty().open(player);
        plugin.sounds().open(player);
    }

    // ==================================================================
    // Bet controls
    // ==================================================================

    /** The ten increments, laid out five negative then five positive. */
    private static final long[] STEPS = {
            -10_000_000L, -1_000_000L, -100_000L, -10_000L, -1_000L,
            1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L
    };
    private static final int[] STEP_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 37, 43};

    /**
     * Ten buttons across the two rows above the footer.
     *
     * Each one shows the CURRENT bet and what this button would make it, so the
     * effect of a click is visible before committing to it.
     */
    private void addBetControls(Gui gui, Player player, Runnable repaint) {
        BigDecimal bet = plugin.gambling().betOf(player);
        BigDecimal balance = plugin.economy().balance(player);

        for (int i = 0; i < STEPS.length; i++) {
            long step = STEPS[i];
            boolean negative = step < 0;
            BigDecimal delta = BigDecimal.valueOf(step);
            BigDecimal preview = bet.add(delta).max(BigDecimal.valueOf(plugin.gambling().minBet()));
            if (preview.compareTo(balance) > 0) preview = balance;

            String label = (negative ? "<red>\u2212 " : "<green>+ ")
                    + NumberFormatter.money(BigDecimal.valueOf(Math.abs(step)));

            gui.set(STEP_SLOTS[i], Gui.icon(
                    negative ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    label + "</green>",
                    "<gray>CURRENT BET: <gold>" + NumberFormatter.money(bet),
                    "<gray>After this click: <yellow>" + NumberFormatter.money(preview),
                    "",
                    "<gray>Balance: <green>" + NumberFormatter.money(balance)),
                    p -> {
                        plugin.gambling().adjustBet(p, delta);
                        plugin.sounds().click(p);
                        repaint.run();
                    });
        }

        gui.set(40, Gui.icon(Material.GOLD_INGOT,
                "<gold><b>CURRENT BET: " + NumberFormatter.money(bet),
                "<gray>Balance: <green>" + NumberFormatter.money(balance),
                "",
                "<gray>Min <white>"
                        + NumberFormatter.money(BigDecimal.valueOf(plugin.gambling().minBet()))
                        + "</white>   Max <white>"
                        + NumberFormatter.money(plugin.gambling().maxBet())));
    }

    private boolean takeStake(Player player) {
        if (!plugin.gambling().canAfford(player)) {
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.broke")));
            plugin.sounds().broke(player);
            return false;
        }
        BigDecimal bet = plugin.gambling().betOf(player);
        if (!plugin.gambling().takeStake(player, bet)) {
            plugin.sounds().error(player);
            return false;
        }
        stakes.put(player.getUniqueId(), bet);
        return true;
    }

    private BigDecimal stakeOf(Player player) {
        return stakes.getOrDefault(player.getUniqueId(), BigDecimal.ZERO);
    }

    private void announceWin(Player player, BigDecimal payout, BigDecimal stake) {
        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.won")
                .replace("%amount%", NumberFormatter.money(payout.subtract(stake)))
                .replace("%balance%", NumberFormatter.money(plugin.economy().balance(player)))));
        plugin.sounds().bigBuy(player);
    }

    private void announceLoss(Player player, BigDecimal stake) {
        plugin.gambling().recordLoss(player, stake);
        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.lost")
                .replace("%amount%", NumberFormatter.money(stake))
                .replace("%balance%", NumberFormatter.money(plugin.economy().balance(player)))));
        plugin.sounds().error(player);
    }

    // ==================================================================
    // Mines
    // ==================================================================

    public void openMinesSetup(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <red>MINES <dark_gray>\u2726", 6);
        int chosen = mineChoice.getOrDefault(player.getUniqueId(), 3);

        int[] options = {1, 3, 5, 10, 15, 20, 24};
        int[] slots = {10, 11, 12, 13, 14, 15, 16};

        for (int i = 0; i < options.length; i++) {
            int count = options[i];
            double one = plugin.gambling().minesMultiplier(MinesGame.TILES, count, 1);
            double five = plugin.gambling().minesMultiplier(MinesGame.TILES, count,
                    Math.min(5, MinesGame.TILES - count));
            gui.set(slots[i], Gui.icon(
                    count == chosen ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                    (count == chosen ? "<green><b>" : "<white>") + count + " mines",
                    "<gray>Per tile: <yellow>" + String.format("%.2f", one) + "x",
                    "<gray>After 5: <gold>" + String.format("%.2f", five) + "x",
                    "",
                    count <= 3 ? "<green>Low risk"
                            : count <= 10 ? "<yellow>Fair risk" : "<red>High risk"),
                    p -> { mineChoice.put(p.getUniqueId(), count);
                           plugin.sounds().click(p); openMinesSetup(p); });
        }

        gui.set(22, Gui.icon(Material.TNT, "<red><b>START",
                "<gray>Mines: <white>" + chosen,
                "<gray>Stake: <gold>" + plugin.gambling().formatBet(player)),
                p -> {
                    if (!takeStake(p)) return;
                    mines.put(p.getUniqueId(),
                            new MinesGame(mineChoice.getOrDefault(p.getUniqueId(), 3)));
                    plugin.sounds().confirm(p);
                    openMines(p);
                });

        addBetControls(gui, player, () -> openMinesSetup(player));
        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void openMines(Player player) {
        MinesGame game = mines.get(player.getUniqueId());
        if (game == null) { openMinesSetup(player); return; }

        BigDecimal stake = stakeOf(player);
        double multiplier = plugin.gambling().minesMultiplier(
                MinesGame.TILES, game.mineCount(), game.revealedCount());
        BigDecimal cashValue = stake.multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, java.math.RoundingMode.DOWN);

        Gui gui = new Gui("<dark_gray>\u2726 <red>MINES <dark_gray>\u2726 <gray>"
                + game.mineCount() + " mines", 6);

        for (int tile = 0; tile < MinesGame.TILES; tile++) {
            int slot = (tile / 5) * 9 + (tile % 5) + 2;
            final int index = tile;
            if (game.isOver()) {
                gui.set(slot, Gui.icon(game.isMine(tile) ? Material.TNT
                                : game.isRevealed(tile) ? Material.LIME_STAINED_GLASS_PANE
                                                        : Material.GRAY_STAINED_GLASS_PANE,
                        game.isMine(tile) ? "<red>Mine" : "<green>Safe"));
            } else if (game.isRevealed(tile)) {
                gui.set(slot, Gui.icon(Material.LIME_STAINED_GLASS_PANE, "<green>Safe"));
            } else {
                gui.set(slot, Gui.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<gray>Hidden", "<yellow>Click to reveal"),
                        p -> {
                            MinesGame g = mines.get(p.getUniqueId());
                            if (g == null || g.isOver()) return;
                            if (!g.reveal(index)) announceLoss(p, stakeOf(p));
                            else {
                                plugin.sounds().click(p);
                                if (g.isCleared()) cashOutMines(p);
                            }
                            openMines(p);
                        });
            }
        }

        gui.set(16, Gui.icon(Material.PAPER, "<white><b>Round",
                "<gray>Stake: <gold>" + NumberFormatter.money(stake),
                "<gray>Revealed: <white>" + game.revealedCount(),
                "<gray>Multiplier: <yellow>" + String.format("%.2f", multiplier) + "x"));

        if (!game.isOver() && game.revealedCount() > 0) {
            gui.set(25, Gui.icon(Material.EMERALD_BLOCK, "<green><b>CASH OUT",
                    "<gray>Collect <green>" + NumberFormatter.money(cashValue)),
                    this::cashOutMines);
        }
        if (game.isOver()) {
            gui.set(25, Gui.icon(Material.LIME_CONCRETE, "<green><b>Play again"),
                    p -> { mines.remove(p.getUniqueId()); openMinesSetup(p); });
        }

        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back",
                game.isOver() ? "<gray>Round finished."
                              : "<red>Leaving forfeits your stake."),
                p -> {
                    MinesGame g = mines.get(p.getUniqueId());
                    if (g != null && !g.isOver()) announceLoss(p, stakeOf(p));
                    mines.remove(p.getUniqueId());
                    open(p);
                });
        gui.fillEmpty().open(player);
    }

    private void cashOutMines(Player player) {
        MinesGame game = mines.get(player.getUniqueId());
        if (game == null || game.isOver()) return;
        BigDecimal stake = stakeOf(player);
        double multiplier = plugin.gambling().minesMultiplier(
                MinesGame.TILES, game.mineCount(), game.revealedCount());
        game.cashOut();
        BigDecimal payout = plugin.gambling().payWin(player, stake, multiplier);
        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.cashout")
                .replace("%amount%", NumberFormatter.money(payout))
                .replace("%multiplier%", String.format("%.2f", multiplier))
                .replace("%balance%", NumberFormatter.money(plugin.economy().balance(player)))));
        plugin.sounds().bigBuy(player);
        openMines(player);
    }

    // ==================================================================
    // Towers
    // ==================================================================

    public void openTowersSetup(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <gold>TOWERS <dark_gray>\u2726", 6);
        int chosen = towerWidth.getOrDefault(player.getUniqueId(), 3);
        int[] widths = {2, 3, 4};
        int[] slots = {11, 13, 15};
        String[] labels = {"Hard", "Normal", "Easy"};

        for (int i = 0; i < widths.length; i++) {
            int width = widths[i];
            TowersGame preview = new TowersGame(width);
            double perFloor = preview.fairMultiplier(1) * (1 - plugin.gambling().houseEdge());
            double full = preview.fairMultiplier(TowersGame.FLOORS)
                    * (1 - plugin.gambling().houseEdge());
            gui.set(slots[i], Gui.icon(
                    width == chosen ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                    (width == chosen ? "<green><b>" : "<white>") + labels[i]
                            + " <gray>(" + width + " tiles)",
                    "<gray>One trap per floor.",
                    "",
                    "<gray>Per floor: <yellow>" + String.format("%.2f", perFloor) + "x",
                    "<gray>All 8 floors: <gold>" + String.format("%.1f", full) + "x"),
                    p -> { towerWidth.put(p.getUniqueId(), width);
                           plugin.sounds().click(p); openTowersSetup(p); });
        }

        gui.set(22, Gui.icon(Material.BRICKS, "<gold><b>START",
                "<gray>Stake: <gold>" + plugin.gambling().formatBet(player)),
                p -> {
                    if (!takeStake(p)) return;
                    towers.put(p.getUniqueId(),
                            new TowersGame(towerWidth.getOrDefault(p.getUniqueId(), 3)));
                    plugin.sounds().confirm(p);
                    openTowers(p);
                });

        addBetControls(gui, player, () -> openTowersSetup(player));
        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void openTowers(Player player) {
        TowersGame game = towers.get(player.getUniqueId());
        if (game == null) { openTowersSetup(player); return; }

        BigDecimal stake = stakeOf(player);
        double multiplier = game.fairMultiplier(game.floor())
                * (1 - plugin.gambling().houseEdge());
        if (game.floor() == 0) multiplier = 1.0;

        Gui gui = new Gui("<dark_gray>\u2726 <gold>TOWERS <dark_gray>\u2726 <gray>floor "
                + Math.min(game.floor() + 1, TowersGame.FLOORS), 6);

        // Floor 0 at the bottom, so climbing feels like climbing.
        for (int f = 0; f < TowersGame.FLOORS; f++) {
            int row = TowersGame.FLOORS - 1 - f;
            if (row > 5) continue;
            for (int tile = 0; tile < game.width(); tile++) {
                int slot = row * 9 + tile + 2;
                final int floorIndex = f;
                final int tileIndex = tile;

                boolean isCurrent = f == game.floor() && !game.isOver();
                boolean passed = f < game.floor();
                boolean revealed = game.isOver() || passed;

                if (revealed) {
                    boolean trap = game.trapAt(f) == tile;
                    boolean picked = game.pickAt(f) == tile;
                    gui.set(slot, Gui.icon(trap ? Material.TNT
                                    : picked ? Material.LIME_CONCRETE
                                             : Material.GRAY_STAINED_GLASS_PANE,
                            trap ? "<red>Trap" : picked ? "<green>Your pick" : "<gray>Safe"));
                } else if (isCurrent) {
                    gui.set(slot, Gui.icon(Material.YELLOW_STAINED_GLASS_PANE,
                            "<yellow>Floor " + (f + 1), "<yellow>Click to climb"),
                            p -> {
                                TowersGame g = towers.get(p.getUniqueId());
                                if (g == null || g.isOver()) return;
                                if (!g.pick(tileIndex)) announceLoss(p, stakeOf(p));
                                else {
                                    plugin.sounds().click(p);
                                    if (g.floor() >= TowersGame.FLOORS) cashOutTowers(p);
                                }
                                openTowers(p);
                            });
                } else {
                    gui.set(slot, Gui.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            "<dark_gray>Floor " + (f + 1)));
                }
            }
        }

        BigDecimal cashValue = stake.multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, java.math.RoundingMode.DOWN);

        gui.set(16, Gui.icon(Material.PAPER, "<white><b>Climb",
                "<gray>Stake: <gold>" + NumberFormatter.money(stake),
                "<gray>Floors cleared: <white>" + game.floor() + "/" + TowersGame.FLOORS,
                "<gray>Multiplier: <yellow>" + String.format("%.2f", multiplier) + "x"));

        if (!game.isOver() && game.floor() > 0) {
            gui.set(25, Gui.icon(Material.EMERALD_BLOCK, "<green><b>BANK IT",
                    "<gray>Collect <green>" + NumberFormatter.money(cashValue)),
                    this::cashOutTowers);
        }
        if (game.isOver()) {
            gui.set(25, Gui.icon(Material.LIME_CONCRETE, "<green><b>Play again"),
                    p -> { towers.remove(p.getUniqueId()); openTowersSetup(p); });
        }

        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back",
                game.isOver() ? "<gray>Round finished."
                              : "<red>Leaving forfeits your stake."),
                p -> {
                    TowersGame g = towers.get(p.getUniqueId());
                    if (g != null && !g.isOver()) announceLoss(p, stakeOf(p));
                    towers.remove(p.getUniqueId());
                    open(p);
                });
        gui.fillEmpty().open(player);
    }

    private void cashOutTowers(Player player) {
        TowersGame game = towers.get(player.getUniqueId());
        if (game == null || game.isOver()) return;
        double multiplier = game.fairMultiplier(game.floor())
                * (1 - plugin.gambling().houseEdge());
        game.cashOut();
        BigDecimal payout = plugin.gambling().payWin(player, stakeOf(player), multiplier);
        player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.cashout")
                .replace("%amount%", NumberFormatter.money(payout))
                .replace("%multiplier%", String.format("%.2f", multiplier))
                .replace("%balance%", NumberFormatter.money(plugin.economy().balance(player)))));
        plugin.sounds().bigBuy(player);
        openTowers(player);
    }

    // ==================================================================
    // 21
    // ==================================================================

    private void startBlackjack(Player player) {
        if (!takeStake(player)) return;
        blackjack.put(player.getUniqueId(), new BlackjackGame());
        plugin.sounds().confirm(player);
        openBlackjack(player);
    }

    private void openBlackjack(Player player) {
        BlackjackGame game = blackjack.get(player.getUniqueId());
        if (game == null) { open(player); return; }

        Gui gui = new Gui("<dark_gray>\u2726 <white>21 <dark_gray>\u2726", 5);
        BigDecimal stake = stakeOf(player);

        gui.set(11, Gui.icon(Material.PLAYER_HEAD, "<green><b>Your hand: "
                        + game.playerScore(),
                "<white>" + describeHand(game.playerHand()),
                "",
                game.playerBust() ? "<red>Bust" : "<gray>"));

        boolean hideHole = !game.isFinished();
        gui.set(15, Gui.icon(Material.SKELETON_SKULL, "<red><b>Dealer: "
                        + (hideHole ? "?" : String.valueOf(game.dealerScore())),
                "<white>" + (hideHole
                        ? BlackjackGame.cardName(game.dealerHand().get(0)) + " + ?"
                        : describeHand(game.dealerHand())),
                "",
                game.dealerBust() ? "<green>Dealer bust" : "<gray>"));

        if (!game.isFinished()) {
            gui.set(29, Gui.icon(Material.LIME_CONCRETE, "<green><b>HIT",
                    "<gray>Take another card."),
                    p -> {
                        BlackjackGame g = blackjack.get(p.getUniqueId());
                        if (g == null) return;
                        g.hit();
                        plugin.sounds().click(p);
                        if (g.isFinished()) settleBlackjack(p);
                        openBlackjack(p);
                    });
            gui.set(33, Gui.icon(Material.RED_CONCRETE, "<red><b>STAND",
                    "<gray>Let the dealer play."),
                    p -> {
                        BlackjackGame g = blackjack.get(p.getUniqueId());
                        if (g == null) return;
                        g.stand();
                        plugin.sounds().click(p);
                        settleBlackjack(p);
                        openBlackjack(p);
                    });
        } else {
            gui.set(31, Gui.icon(Material.LIME_CONCRETE, "<green><b>Play again",
                    "<gray>Stake: <gold>" + plugin.gambling().formatBet(player)),
                    p -> { blackjack.remove(p.getUniqueId()); startBlackjack(p); });
        }

        gui.set(22, Gui.icon(Material.GOLD_INGOT, "<gold>Stake: "
                + NumberFormatter.money(stake)));
        gui.set(36, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { blackjack.remove(p.getUniqueId()); open(p); });
        gui.fillEmpty().open(player);
    }

    private String describeHand(List<Integer> hand) {
        List<String> names = new ArrayList<>();
        for (int card : hand) names.add(BlackjackGame.cardName(card));
        return String.join(" ", names);
    }

    private void settleBlackjack(Player player) {
        BlackjackGame game = blackjack.get(player.getUniqueId());
        if (game == null || !game.isFinished()) return;
        BigDecimal stake = stakeOf(player);
        double edge = 1.0 - plugin.gambling().houseEdge();

        switch (game.outcome()) {
            case PLAYER_BLACKJACK -> {
                BigDecimal payout = plugin.gambling().payWin(player, stake, 2.5 * edge);
                announceWin(player, payout, stake);
            }
            case PLAYER_WIN -> {
                BigDecimal payout = plugin.gambling().payWin(player, stake, 2.0 * edge);
                announceWin(player, payout, stake);
            }
            case PUSH -> {
                // A tie returns the stake untouched, with no edge applied.
                plugin.gambling().payWin(player, stake, 1.0);
                player.sendMessage(Gui.MM.deserialize(
                        "<gray>Push - your stake is returned."));
                plugin.sounds().click(player);
            }
            default -> announceLoss(player, stake);
        }
    }

    // ==================================================================
    // Crash
    // ==================================================================

    public void openCrashSetup(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <aqua>CRASH <dark_gray>\u2726", 6);

        gui.set(22, Gui.icon(Material.FIREWORK_ROCKET, "<aqua><b>LAUNCH",
                "<gray>Stake: <gold>" + plugin.gambling().formatBet(player),
                "",
                "<gray>The multiplier climbs from 1.00x.",
                "<gray>Cash out before it crashes.",
                "",
                "<dark_gray>The crash point is drawn when the",
                "<dark_gray>round starts and never changes."),
                p -> {
                    if (!takeStake(p)) return;
                    crash.put(p.getUniqueId(), new CrashGame(plugin.gambling().houseEdge()));
                    plugin.sounds().confirm(p);
                    runCrash(p);
                });

        addBetControls(gui, player, () -> openCrashSetup(player));
        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void runCrash(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <aqua>CRASH <dark_gray>\u2726", 3);
        BigDecimal stake = stakeOf(player);

        gui.live(13, () -> {
            CrashGame game = crash.get(player.getUniqueId());
            if (game == null) return Gui.icon(Material.BARRIER, "<gray>No round");
            BigDecimal value = stake.multiply(BigDecimal.valueOf(game.current()))
                    .setScale(0, java.math.RoundingMode.DOWN);
            if (game.hasCrashed()) {
                return Gui.icon(Material.TNT,
                        "<red><b>CRASHED AT " + String.format("%.2f", game.crashPoint()) + "x",
                        "<red>Lost " + NumberFormatter.money(stake));
            }
            if (game.isCashedOut()) {
                return Gui.icon(Material.EMERALD_BLOCK,
                        "<green><b>CASHED OUT " + String.format("%.2f", game.current()) + "x",
                        "<green>Won " + NumberFormatter.money(value));
            }
            return Gui.icon(Material.FIREWORK_ROCKET,
                    "<aqua><b>" + String.format("%.2f", game.current()) + "x",
                    "<gray>Worth <green>" + NumberFormatter.money(value),
                    "",
                    "<yellow>Cash out before it goes.");
        });

        gui.set(11, Gui.icon(Material.EMERALD_BLOCK, "<green><b>CASH OUT",
                "<gray>Take whatever it is worth now."),
                p -> {
                    CrashGame game = crash.get(p.getUniqueId());
                    if (game == null || game.isOver()) return;
                    double multiplier = game.cashOut();
                    BigDecimal payout = plugin.gambling().payWin(p, stakeOf(p), multiplier);
                    p.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.cashout")
                            .replace("%amount%", NumberFormatter.money(payout))
                            .replace("%multiplier%", String.format("%.2f", multiplier))
                            .replace("%balance%",
                                    NumberFormatter.money(plugin.economy().balance(p)))));
                    plugin.sounds().bigBuy(p);
                });

        gui.set(15, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back",
                "<red>Leaving mid-round forfeits your stake."),
                p -> {
                    CrashGame game = crash.get(p.getUniqueId());
                    if (game != null && !game.isOver()) announceLoss(p, stakeOf(p));
                    crash.remove(p.getUniqueId());
                    open(p);
                });

        gui.fillEmpty().open(player);

        // The round ticks server-side whether or not the menu is open, so
        // closing the window cannot pause a losing round.
        new BukkitRunnable() {
            @Override
            public void run() {
                CrashGame game = crash.get(player.getUniqueId());
                if (game == null || !player.isOnline()) { cancel(); return; }
                if (!game.tick()) {
                    if (game.hasCrashed()) {
                        announceLoss(player, stakeOf(player));
                        plugin.sounds().error(player);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 10L, 3L);
    }

    // ==================================================================
    // Wheel
    // ==================================================================

    public void openWheelSetup(Player player) {
        Gui gui = new Gui("<dark_gray>\u2726 <yellow>WHEEL <dark_gray>\u2726", 6);
        int[] slots = {11, 13, 15};
        WheelGame.Risk[] risks = WheelGame.Risk.values();

        for (int i = 0; i < risks.length; i++) {
            WheelGame.Risk risk = risks[i];
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Segments:");
            double[] multipliers = risk.multipliers();
            int[] weights = risk.weights();
            int total = 0;
            for (int w : weights) total += w;
            for (int j = 0; j < multipliers.length; j++) {
                String label = multipliers[j] == 0 ? "<red>nothing"
                        : "<yellow>" + String.format("%.1f", multipliers[j]) + "x";
                lore.add("<dark_gray>  " + label + " <gray>- "
                        + Math.round(weights[j] * 100.0 / total) + "%");
            }
            lore.add("");
            lore.add("<gray>Stake: <gold>" + plugin.gambling().formatBet(player));
            lore.add("<yellow>Click to spin");

            gui.set(slots[i], Gui.icon(
                    risk == WheelGame.Risk.LOW ? Material.LIME_CONCRETE
                            : risk == WheelGame.Risk.MEDIUM ? Material.YELLOW_CONCRETE
                                                            : Material.RED_CONCRETE,
                    "<b>" + risk.display() + " risk", lore),
                    p -> spinWheel(p, risk));
        }

        addBetControls(gui, player, () -> openWheelSetup(player));
        gui.set(45, Gui.icon(Material.RED_CONCRETE, "<red><b>\u2190 Back"),
                p -> { plugin.sounds().click(p); open(p); });
        gui.fillEmpty().open(player);
    }

    private void spinWheel(Player player, WheelGame.Risk risk) {
        if (!takeStake(player)) return;
        BigDecimal stake = stakeOf(player);
        WheelGame game = new WheelGame(risk, plugin.gambling().houseEdge());

        if (game.won()) {
            BigDecimal payout = plugin.gambling().payWin(player, stake, game.result());
            player.sendMessage(Gui.MM.deserialize(plugin.messages().get("gamble.cashout")
                    .replace("%amount%", NumberFormatter.money(payout))
                    .replace("%multiplier%", String.format("%.2f", game.result()))
                    .replace("%balance%",
                            NumberFormatter.money(plugin.economy().balance(player)))));
            plugin.sounds().bigBuy(player);
        } else {
            announceLoss(player, stake);
        }
        openWheelSetup(player);
    }

    // ==================================================================

    public void forget(UUID player) {
        mines.remove(player);
        mineChoice.remove(player);
        towers.remove(player);
        towerWidth.remove(player);
        blackjack.remove(player);
        crash.remove(player);
        stakes.remove(player);
    }
}
