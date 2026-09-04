package com.sola.universalmarket.gamble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One round of Mines.
 *
 * The board is generated when the round STARTS, not as tiles are clicked. That
 * matters: a board decided on the fly could be nudged to blow up the moment a
 * player is about to cash out, and there would be no way for anyone to prove
 * otherwise. Fixing it up front makes the game verifiably honest.
 */
public final class MinesGame {

    public static final int TILES = 25;   // 5x5

    private final int mineCount;
    private final Set<Integer> mines = new HashSet<>();
    private final Set<Integer> revealed = new HashSet<>();
    private boolean exploded = false;
    private boolean cashedOut = false;

    public MinesGame(int mineCount) {
        this.mineCount = Math.max(1, Math.min(24, mineCount));

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < TILES; i++) positions.add(i);
        Collections.shuffle(positions);
        for (int i = 0; i < this.mineCount; i++) mines.add(positions.get(i));
    }

    public int mineCount() { return mineCount; }
    public int revealedCount() { return revealed.size(); }
    public boolean isExploded() { return exploded; }
    public boolean isCashedOut() { return cashedOut; }
    public boolean isOver() { return exploded || cashedOut; }

    public boolean isRevealed(int tile) { return revealed.contains(tile); }
    public boolean isMine(int tile) { return mines.contains(tile); }

    /** Reveal a tile. Returns true if it was safe. */
    public boolean reveal(int tile) {
        if (isOver() || revealed.contains(tile)) return !exploded;
        if (mines.contains(tile)) {
            exploded = true;
            return false;
        }
        revealed.add(tile);
        return true;
    }

    public void cashOut() {
        if (!exploded) cashedOut = true;
    }

    /** True once every safe tile has been found. */
    public boolean isCleared() {
        return revealed.size() >= TILES - mineCount;
    }

    public Set<Integer> mines() {
        return Collections.unmodifiableSet(mines);
    }
}
