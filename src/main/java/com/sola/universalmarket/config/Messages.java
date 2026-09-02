package com.sola.universalmarket.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads messages.yml. Every player-facing string goes through here so the whole
 * plugin can be re-worded without a rebuild.
 *
 * Missing keys fall back to the jar's bundled copy rather than printing null, so
 * adding new messages in a future update cannot break an existing config.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private YamlConfiguration user;
    private YamlConfiguration bundled;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        this.user = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                this.bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** MiniMessage-formatted string for a key, with prefix already applied where sensible. */
    public String get(String path) {
        String value = user.getString(path);
        if (value == null && bundled != null) value = bundled.getString(path);
        return value == null ? "<red>[missing message: " + path + "]" : value;
    }

    public String prefix() {
        return get("prefix");
    }
}
