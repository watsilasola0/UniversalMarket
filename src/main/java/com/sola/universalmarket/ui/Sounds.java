package com.sola.universalmarket.ui;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sound feedback for every menu action.
 *
 * Sounds are addressed by their namespaced STRING key ("ui.button.click") rather
 * than the Sound enum. Paper turned Sound into a registry-backed interface in
 * recent versions, so Sound.valueOf() is no longer dependable across builds - but
 * the String overload of playSound has been stable for years and lets every
 * sound be swapped from config without touching Java.
 *
 * Volumes are deliberately low. Menu sounds that cut through combat audio get
 * irritating fast, and this plugin is something players will click through
 * hundreds of times a session.
 */
public final class Sounds {

    private final JavaPlugin plugin;

    public Sounds(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sounds.enabled", true);
    }

    private void play(Player player, String configKey, String fallback,
                      float defaultVolume, float defaultPitch) {
        if (player == null || !enabled()) return;
        String sound = plugin.getConfig().getString("sounds." + configKey + ".sound", fallback);
        double volume = plugin.getConfig().getDouble("sounds." + configKey + ".volume", defaultVolume);
        double pitch = plugin.getConfig().getDouble("sounds." + configKey + ".pitch", defaultPitch);
        try {
            player.playSound(player.getLocation(), sound, (float) volume, (float) pitch);
        } catch (Throwable ignored) {
            // A bad sound name in config must never break a purchase.
        }
    }

    /** Menu opened. */
    public void open(Player p)      { play(p, "menu-open", "block.ender_chest.open", 0.4f, 1.4f); }
    /** Any ordinary button. */
    public void click(Player p)     { play(p, "click", "ui.button.click", 0.3f, 1.2f); }
    /** Page turned. */
    public void page(Player p)      { play(p, "page", "item.book.page_turn", 0.6f, 1.0f); }
    /** Purchase completed. */
    public void buy(Player p)       { play(p, "buy", "entity.experience_orb.pickup", 0.6f, 1.6f); }
    /** A large or rare purchase completed. */
    public void bigBuy(Player p)    { play(p, "big-buy", "entity.player.levelup", 0.5f, 1.2f); }
    /** Sale to the server completed. */
    public void sell(Player p)      { play(p, "sell", "entity.villager.yes", 0.5f, 1.3f); }
    /** Something was refused. */
    public void error(Player p)     { play(p, "error", "block.note_block.bass", 0.4f, 0.7f); }
    /** Not enough money. */
    public void broke(Player p)     { play(p, "broke", "entity.villager.no", 0.5f, 1.0f); }
    /** A confirmation prompt appeared. */
    public void confirm(Player p)   { play(p, "confirm", "block.note_block.pling", 0.5f, 0.8f); }
    /** Money arrived. */
    public void money(Player p)     { play(p, "money", "entity.experience_orb.pickup", 0.7f, 1.2f); }
    /** Wealth tier promotion. */
    public void promotion(Player p) { play(p, "promotion", "ui.toast.challenge_complete", 0.7f, 1.0f); }
    /** New market cycle. */
    public void cycle(Player p)     { play(p, "cycle", "block.bell.use", 0.5f, 1.4f); }
}
