package com.sola.universalmarket.gamble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One hand of 21.
 *
 * A real shuffled 52-card deck rather than random numbers, because card counting
 * only makes sense against a deck and the odds change as cards come out. Aces
 * are soft: they count 11 until that would bust, then drop to 1.
 */
public final class BlackjackGame {

    private final List<Integer> deck = new ArrayList<>();
    private final List<Integer> playerHand = new ArrayList<>();
    private final List<Integer> dealerHand = new ArrayList<>();
    private boolean playerStood = false;
    private boolean finished = false;

    public BlackjackGame() {
        // 1 = ace, 11-13 = face cards worth 10.
        for (int suit = 0; suit < 4; suit++) {
            for (int rank = 1; rank <= 13; rank++) deck.add(rank);
        }
        Collections.shuffle(deck);

        playerHand.add(draw());
        dealerHand.add(draw());
        playerHand.add(draw());
        dealerHand.add(draw());
    }

    private int draw() {
        return deck.remove(deck.size() - 1);
    }

    /** Best total for a hand, treating aces as 11 where it does not bust. */
    public static int score(List<Integer> hand) {
        int total = 0;
        int aces = 0;
        for (int card : hand) {
            if (card == 1) { aces++; total += 11; }
            else total += Math.min(card, 10);
        }
        while (total > 21 && aces > 0) { total -= 10; aces--; }
        return total;
    }

    public int playerScore() { return score(playerHand); }
    public int dealerScore() { return score(dealerHand); }

    public List<Integer> playerHand() { return Collections.unmodifiableList(playerHand); }
    public List<Integer> dealerHand() { return Collections.unmodifiableList(dealerHand); }

    public boolean playerBust() { return playerScore() > 21; }
    public boolean dealerBust() { return dealerScore() > 21; }
    public boolean isFinished() { return finished; }
    public boolean playerStood() { return playerStood; }

    /** Natural 21 on the first two cards pays extra. */
    public boolean isPlayerBlackjack() {
        return playerHand.size() == 2 && playerScore() == 21;
    }

    public boolean isDealerBlackjack() {
        return dealerHand.size() == 2 && dealerScore() == 21;
    }

    public void hit() {
        if (finished || playerStood) return;
        playerHand.add(draw());
        if (playerBust()) finished = true;
    }

    /** Stand, then play out the dealer: hits until 17 or better. */
    public void stand() {
        if (finished) return;
        playerStood = true;
        while (dealerScore() < 17) dealerHand.add(draw());
        finished = true;
    }

    public enum Outcome { PLAYER_BLACKJACK, PLAYER_WIN, DEALER_WIN, PUSH, IN_PROGRESS }

    public Outcome outcome() {
        if (!finished) return Outcome.IN_PROGRESS;
        if (playerBust()) return Outcome.DEALER_WIN;
        if (isPlayerBlackjack() && !isDealerBlackjack()) return Outcome.PLAYER_BLACKJACK;
        if (dealerBust()) return Outcome.PLAYER_WIN;
        int player = playerScore();
        int dealer = dealerScore();
        if (player > dealer) return Outcome.PLAYER_WIN;
        if (player < dealer) return Outcome.DEALER_WIN;
        return Outcome.PUSH;
    }

    public static String cardName(int card) {
        return switch (card) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> String.valueOf(card);
        };
    }
}
