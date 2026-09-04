package com.sola.universalmarket.gamble;

import java.util.Random;

/**
 * Towers: climb eight floors, picking one tile per floor.
 *
 * Each floor has one trap among {@code width} tiles, so survival odds per floor
 * are fixed and known. Unlike Mines the risk never changes as you climb, which
 * makes the decision purely "do I bank now or push one more floor" - the
 * tension is in the multiplier, not in tracking odds.
 */
public final class TowersGame {

    public static final int FLOORS = 8;

    private final int width;
    private final int[] traps = new int[FLOORS];
    private final int[] picks = new int[FLOORS];
    private int floor = 0;
    private boolean dead = false;
    private boolean cashedOut = false;

    public TowersGame(int width) {
        this.width = Math.max(2, Math.min(4, width));
        Random random = new Random();
        for (int i = 0; i < FLOORS; i++) {
            traps[i] = random.nextInt(this.width);
            picks[i] = -1;
        }
    }

    public int width() { return width; }
    public int floor() { return floor; }
    public boolean isDead() { return dead; }
    public boolean isCashedOut() { return cashedOut; }
    public boolean isOver() { return dead || cashedOut || floor >= FLOORS; }
    public int pickAt(int f) { return picks[f]; }
    public int trapAt(int f) { return traps[f]; }

    /** Returns true if the pick was safe. */
    public boolean pick(int tile) {
        if (isOver()) return false;
        picks[floor] = tile;
        if (tile == traps[floor]) { dead = true; return false; }
        floor++;
        return true;
    }

    public void cashOut() {
        if (!dead) cashedOut = true;
    }

    /** Fair multiplier after clearing {@code floors} floors, before house edge. */
    public double fairMultiplier(int floors) {
        double survival = (width - 1) / (double) width;
        return Math.pow(1.0 / survival, floors);
    }
}
