package com.sola.universalmarket.gamble;

import java.util.Random;

/**
 * Wheel of fortune with three risk profiles.
 *
 * Low risk pays small and often; high risk mostly pays nothing but carries a
 * genuine jackpot. All three have the same expected value once the house edge
 * is applied - the choice is variance, not value, which is what makes picking
 * one an actual decision rather than an obvious best option.
 */
public final class WheelGame {

    public enum Risk {
        LOW("Low", new double[]{0.0, 1.2, 1.5, 1.8, 2.0},
                   new int[]{20, 30, 25, 15, 10}),
        MEDIUM("Medium", new double[]{0.0, 1.5, 2.0, 3.0, 7.0},
                         new int[]{40, 25, 20, 10, 5}),
        HIGH("High", new double[]{0.0, 2.0, 5.0, 15.0, 50.0},
                     new int[]{62, 20, 12, 5, 1});

        private final String display;
        private final double[] multipliers;
        private final int[] weights;

        Risk(String display, double[] multipliers, int[] weights) {
            this.display = display;
            this.multipliers = multipliers;
            this.weights = weights;
        }

        public String display() { return display; }
        public double[] multipliers() { return multipliers; }
        public int[] weights() { return weights; }
    }

    private final Risk risk;
    private final double result;

    public WheelGame(Risk risk, double houseEdge) {
        this.risk = risk;
        this.result = spin(risk) * (1.0 - houseEdge);
    }

    private static double spin(Risk risk) {
        int total = 0;
        for (int weight : risk.weights()) total += weight;

        int roll = new Random().nextInt(Math.max(1, total));
        int running = 0;
        for (int i = 0; i < risk.weights().length; i++) {
            running += risk.weights()[i];
            if (roll < running) return risk.multipliers()[i];
        }
        return 0.0;
    }

    public Risk risk() { return risk; }
    public double result() { return result; }
    public boolean won() { return result > 0; }
}
