package com.sola.universalmarket.gamble;

import java.util.Random;

/**
 * Crash: a multiplier climbs from 1.00x and stops at a random point. Cash out
 * before it stops, or lose the stake.
 *
 * The crash point is drawn the instant the round starts and never changes. It
 * uses the standard inverse-uniform distribution, which gives the long tail
 * that makes the game interesting: most rounds die early, a rare few run to
 * 50x or beyond.
 *
 *   crashPoint = houseFactor / (1 - u),  u uniform in [0, 1)
 *
 * The house factor is what stops the expected value reaching 1.0.
 */
public final class CrashGame {

    private final double crashPoint;
    private double current = 1.00;
    private boolean cashedOut = false;
    private boolean crashed = false;

    public CrashGame(double houseEdge) {
        Random random = new Random();
        double u = random.nextDouble();
        double raw = 1.0 / Math.max(0.0001, 1.0 - u);
        // Clamp so a freak roll cannot pay out the entire server economy.
        this.crashPoint = Math.max(1.0, Math.min(1000.0, raw * (1.0 - houseEdge)));
    }

    public double crashPoint() { return crashPoint; }
    public double current() { return current; }
    public boolean isCashedOut() { return cashedOut; }
    public boolean hasCrashed() { return crashed; }
    public boolean isOver() { return cashedOut || crashed; }

    /** Advance the multiplier. Returns true if the round is still running. */
    public boolean tick() {
        if (isOver()) return false;
        // Accelerating growth: slow at first, then rapid, so the decision gets
        // harder the longer you hold.
        current += 0.01 + (current - 1.0) * 0.035;
        if (current >= crashPoint) {
            current = crashPoint;
            crashed = true;
            return false;
        }
        return true;
    }

    public double cashOut() {
        if (isOver()) return 0;
        cashedOut = true;
        return current;
    }
}
