package com.sola.universalmarket.bedrock;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Detects Bedrock / PlayStation players via Floodgate.
 *
 * Bound reflectively on purpose. Floodgate is optional: on a Java-only server it
 * is simply absent, and a hard compile-time reference would make this class fail
 * to load rather than degrade gracefully. Bound once at startup, after which it
 * is a cheap Method.invoke.
 *
 * This is a SAFETY control, not a cosmetic one. Spec sections 15 and 52: the
 * fake-creative packet workflow must never be applied to a Geyser player,
 * because Bedrock's inventory handling does not match Java's and we would risk
 * corrupting their inventory.
 *
 * So when the lookup fails for any reason, this returns TRUE - treat them as
 * Bedrock. The safe failure is showing a Java player a form menu; the unsafe
 * failure is showing a Bedrock player the packet workflow.
 */
public final class BedrockService {

    private final Logger log;
    private Object floodgateApi;
    private Method isFloodgatePlayer;
    private boolean available = false;

    public BedrockService(Logger log) {
        this.log = log;
    }

    public void setup() {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            log.info("Floodgate not detected - running in Java-only mode.");
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            this.floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            this.isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            this.available = floodgateApi != null;
            log.info("Floodgate detected - Bedrock players will use form menus.");
        } catch (Throwable t) {
            log.warning("Floodgate is present but its API could not be bound: " + t
                    + " - treating all players as Java.");
            this.available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** True for Bedrock/PlayStation players. Fails safe by answering true on error. */
    public boolean isBedrock(UUID uuid) {
        if (!available || uuid == null) return false;
        try {
            Object result = isFloodgatePlayer.invoke(floodgateApi, uuid);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            log.warning("Floodgate lookup failed for " + uuid
                    + "; assuming Bedrock for safety: " + t);
            return true;
        }
    }
}
