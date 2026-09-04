package com.sola.universalmarket.gamble;

import java.util.Random;

/**
 * The spinning reel.
 *
 * A strip of 20 segments, each with its OWN distinct multiplier, that scrolls
 * past a fixed marker. The strip is the wheel: the outcome is just which index
 * lands under the marker, drawn uniformly. That makes the odds legible - you
 * can literally count the segments and see your chances.
 *
 * SEVEN MODES, ONE EXPECTED VALUE
 *
 *   Mode 1 is nearly all small wins and no blanks. Mode 7 is mostly 0.0x with a
 *   handful of huge multipliers. After generation every strip is normalised so
 *   its AVERAGE payout equals (1 - house edge), so no mode is secretly better
 *   than another. Choosing a mode picks your variance, not your value - which
 *   is what makes it a real decision instead of a trap for the unwary.
 */
public final class WheelGame {

    /**
     * Strip length.
     *
     * This is what caps the jackpot. With a mean payout of (1 - houseEdge), no
     * single segment can pay more than SEGMENTS x that mean - if it did, the
     * average would exceed the target no matter what the other segments held.
     * At 30 segments and 97% return the ceiling is about 29x. Wanting bigger
     * headline numbers means a longer strip, not a steeper curve.
     */
    public static final int SEGMENTS = 30;

    private final int mode;
    private final double[] strip;
    private final int landedIndex;

    public WheelGame(int mode, double houseEdge) {
        this.mode = Math.max(1, Math.min(7, mode));
        this.strip = buildStrip(this.mode, houseEdge);
        this.landedIndex = new Random().nextInt(SEGMENTS);
    }

    public int mode() { return mode; }
    public double[] strip() { return strip; }
    public int landedIndex() { return landedIndex; }
    public double result() { return strip[landedIndex]; }
    public boolean won() { return result() > 0; }

    /**
     * Build a 20-segment strip for a mode.
     *
     * Blanks rise and the ceiling climbs steeply with the mode. Every non-zero
     * value gets a small unique offset so no two segments read the same, which
     * is what stops the reel looking like a repeating pattern as it scrolls.
     */
    public static double[] buildStrip(int mode, double houseEdge) {
        int blanks = switch (mode) {
            case 1 -> 0;
            case 2 -> 3;
            case 3 -> 7;
            case 4 -> 11;
            case 5 -> 15;
            case 6 -> 19;
            default -> 23;
        };
        // Shape of the non-blank segments: how top-heavy the payouts are.
        double peak = switch (mode) {
            case 1 -> 1.5;
            case 2 -> 3.0;
            case 3 -> 8.0;
            case 4 -> 30.0;
            case 5 -> 150.0;
            case 6 -> 900.0;
            default -> 8000.0;
        };

        double[] raw = new double[SEGMENTS];
        int payingSegments = SEGMENTS - blanks;
        Random random = new Random(mode * 7919L);   // stable shape per mode

        for (int i = 0; i < SEGMENTS; i++) {
            if (i < blanks) { raw[i] = 0.0; continue; }

            // Position within the paying segments, 0 at the low end.
            double t = payingSegments <= 1 ? 0 : (i - blanks) / (double) (payingSegments - 1);
            // Exponential curve: many small values, a few large ones.
            double value = Math.pow(peak, t);
            // Unique jitter so no two segments share a number.
            value *= 0.90 + random.nextDouble() * 0.20;
            raw[i] = Math.max(0.05, value);
        }

        // Normalise so the strip's mean payout is exactly (1 - houseEdge).
        double sum = 0;
        for (double v : raw) sum += v;
        double mean = sum / SEGMENTS;
        double scale = mean <= 0 ? 0 : (1.0 - houseEdge) / mean;

        double[] out = new double[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            out[i] = raw[i] == 0 ? 0.0 : Math.round(raw[i] * scale * 100.0) / 100.0;
        }

        // Shuffle so blanks are scattered rather than sitting in a block.
        Random shuffler = new Random(mode * 104729L);
        for (int i = out.length - 1; i > 0; i--) {
            int j = shuffler.nextInt(i + 1);
            double tmp = out[i]; out[i] = out[j]; out[j] = tmp;
        }
        return out;
    }

    /** Blanks in a mode's strip, for the menu description. */
    public static int blanksIn(double[] strip) {
        int count = 0;
        for (double v : strip) if (v <= 0) count++;
        return count;
    }

    public static double highestIn(double[] strip) {
        double best = 0;
        for (double v : strip) best = Math.max(best, v);
        return best;
    }

    public static String describeMode(int mode) {
        return switch (mode) {
            case 1 -> "Safest";
            case 2 -> "Cautious";
            case 3 -> "Steady";
            case 4 -> "Balanced";
            case 5 -> "Risky";
            case 6 -> "Reckless";
            default -> "Ruinous";
        };
    }
}
