package com.sola.universalmarket.util;

import java.util.Locale;

/**
 * Turns registry names into readable ones: OAK_LOG becomes "Oak Log".
 *
 * Lived on the quest class until quests were removed, which is exactly why it
 * belongs here - it was never quest-specific, and three unrelated menus were
 * reaching across a package boundary to use it.
 */
public final class Names {

    private Names() {}

    public static String pretty(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)))
              .append(part.substring(1))
              .append(' ');
        }
        return sb.toString().trim();
    }
}
