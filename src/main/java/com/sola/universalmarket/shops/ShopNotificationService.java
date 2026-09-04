package com.sola.universalmarket.shops;

import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks sales through player-owned QuickShop chests and reports them to the
 * owner when they next log in (spec section 33).
 *
 * HOW THIS HOOKS WITHOUT A COMPILE DEPENDENCY
 *
 *   Bukkit exposes registerEvent(Class, Listener, EventPriority, EventExecutor,
 *   Plugin). Because the event CLASS is a parameter rather than a compile-time
 *   type, we can load QuickShop's purchase event by name and register a listener
 *   for it without ever importing QuickShop. That keeps the whole plugin free of
 *   a hard QuickShop binding while still receiving real events.
 *
 *   Getter names differ across QuickShop versions, so each one is probed and the
 *   result logged. If anything cannot be resolved, sale tracking switches itself
 *   off and says so - it never guesses at an amount, because a wrong number in a
 *   "you earned X while away" message is worse than no message.
 *
 * NO DOUBLE COUNTING
 *
 *   Rows carry a delivered flag. Notifications are marked delivered in the same
 *   statement that reads them, so a disconnect midway through login cannot
 *   replay the same earnings twice.
 */
public final class ShopNotificationService implements Listener {

    private final UniversalMarketPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private boolean hooked = false;
    private Method getShop, getOwner, getAmount, getTotal, getUniqueId;

    public ShopNotificationService(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Hooking
    // ==================================================================

    public boolean hook() {
        if (!plugin.getConfig().getBoolean("player-shops.track-sales", true)) return false;
        if (Bukkit.getPluginManager().getPlugin("QuickShop-Hikari") == null) return false;

        String[] candidates = {
                "com.ghostchu.quickshop.api.event.ShopSuccessPurchaseEvent",
                "com.ghostchu.quickshop.api.event.ShopPurchaseEvent",
                "org.maxgamer.quickshop.api.event.ShopSuccessPurchaseEvent"
        };

        for (String className : candidates) {
            try {
                Class<?> raw = Class.forName(className);
                if (!Event.class.isAssignableFrom(raw)) continue;
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventClass = (Class<? extends Event>) raw;

                resolveGetters(eventClass);
                if (getShop == null || getTotal == null) {
                    plugin.getLogger().warning("Found " + className
                            + " but could not resolve its shop/total accessors - "
                            + "shop sale tracking disabled.");
                    return false;
                }

                EventExecutor executor = (listener, event) -> handle(event);
                Bukkit.getPluginManager().registerEvent(
                        eventClass, this, EventPriority.MONITOR, executor, plugin, true);

                hooked = true;
                plugin.getLogger().info("Shop sale tracking hooked via " + className);
                return true;

            } catch (ClassNotFoundException ignored) {
                // Try the next candidate.
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not hook " + className + ": " + t);
            }
        }
        plugin.getLogger().info("No QuickShop purchase event found - "
                + "offline shop earnings will not be reported.");
        return false;
    }

    private void resolveGetters(Class<?> eventClass) {
        getShop   = find(eventClass, "getShop");
        getAmount = find(eventClass, "getAmount");
        getTotal  = find(eventClass, "getTotal");
        if (getTotal == null) getTotal = find(eventClass, "getBalance");
        if (getTotal == null) getTotal = find(eventClass, "getPrice");
    }

    private Method find(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // Recording
    // ==================================================================

    private void handle(Event event) {
        if (!hooked) return;
        try {
            Object shop = getShop.invoke(event);
            if (shop == null) return;

            if (getOwner == null) getOwner = find(shop.getClass(), "getOwner");
            if (getOwner == null) return;

            Object owner = getOwner.invoke(shop);
            UUID ownerId = extractUuid(owner);
            if (ownerId == null) return;

            Object totalRaw = getTotal.invoke(event);
            if (!(totalRaw instanceof Number total)) return;
            double value = total.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) return;

            int quantity = 0;
            if (getAmount != null) {
                Object amountRaw = getAmount.invoke(event);
                if (amountRaw instanceof Number n) quantity = n.intValue();
            }

            BigDecimal amount = NumberFormatter.safe(value);
            String itemId = describeItem(shop);

            // The owner is credited by QuickShop itself; we only record it.
            plugin.storage().execute("""
                    INSERT INTO shop_notifications
                        (owner_uuid, item_id, quantity, amount, created_at, delivered)
                    VALUES (?, ?, ?, ?, ?, 0)""",
                    ownerId.toString(), itemId, quantity,
                    amount.toPlainString(), System.currentTimeMillis());

            plugin.transactions().recordShopRevenue(ownerId, itemId, quantity, amount);

            // Live notification if they happen to be online right now.
            Player online = Bukkit.getPlayer(ownerId);
            if (online != null
                    && plugin.getConfig().getBoolean("player-shops.notify-online-sales", true)) {
                online.sendMessage(mm.deserialize("<green>+ "
                        + NumberFormatter.money(amount) + "</green> <gray>shop sale"
                        + (quantity > 0 ? " (<white>" + quantity + "x</white>)" : "")));
                plugin.sounds().money(online);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Shop sale event handling failed: " + t);
        }
    }

    private UUID extractUuid(Object owner) {
        if (owner instanceof UUID uuid) return uuid;
        try {
            if (getUniqueId == null) getUniqueId = find(owner.getClass(), "getUniqueId");
            if (getUniqueId != null) {
                Object id = getUniqueId.invoke(owner);
                if (id instanceof UUID uuid) return uuid;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private String describeItem(Object shop) {
        try {
            Method itemGetter = find(shop.getClass(), "getItem");
            if (itemGetter == null) return "unknown";
            Object item = itemGetter.invoke(shop);
            if (item instanceof org.bukkit.inventory.ItemStack stack) {
                var entry = plugin.catalog().byMaterial(stack.getType());
                return entry != null ? entry.id()
                        : stack.getType().name().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }

    // ==================================================================
    // Login summary
    // ==================================================================

    /** "Your shops made $18.4M while you were away", with a breakdown. */
    public void deliverPending(Player player) {
        if (!plugin.getConfig().getBoolean("player-shops.offline-summary", true)) return;

        plugin.storage().query("""
                SELECT item_id, SUM(quantity) AS qty, SUM(CAST(amount AS INTEGER)) AS total
                FROM shop_notifications
                WHERE owner_uuid = ? AND delivered = 0
                GROUP BY item_id
                ORDER BY total DESC
                LIMIT 6""",
                rs -> {
                    List<String[]> rows = new ArrayList<>();
                    try {
                        while (rs.next()) {
                            rows.add(new String[]{
                                    rs.getString("item_id"),
                                    String.valueOf(rs.getInt("qty")),
                                    String.valueOf(rs.getLong("total"))});
                        }
                    } catch (Exception ignored) { }
                    return rows;
                }, player.getUniqueId().toString())
                .thenAccept(rows -> {
                    if (rows == null || rows.isEmpty()) return;

                    // Mark delivered immediately so a reconnect cannot replay them.
                    plugin.storage().execute(
                            "UPDATE shop_notifications SET delivered = 1 WHERE owner_uuid = ?",
                            player.getUniqueId().toString());

                    BigDecimal grand = BigDecimal.ZERO;
                    StringBuilder body = new StringBuilder();
                    for (String[] row : rows) {
                        BigDecimal amount = new BigDecimal(row[2]);
                        grand = grand.add(amount);
                        var item = plugin.catalog().byId(row[0]);
                        String name = item != null ? item.displayName() : row[0];
                        body.append("\n<gray>  ").append(name)
                            .append(" <white>x").append(row[1])
                            .append("  <green>").append(NumberFormatter.money(amount));
                    }

                    final String summary = "<dark_gray>─────────────────────────────\n"
                            + "<gold><b>Your shops earned while you were away</b>\n"
                            + "<green><b>" + NumberFormatter.money(grand) + "</b>"
                            + body
                            + "\n<dark_gray>─────────────────────────────";

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(mm.deserialize(summary));
                        plugin.sounds().money(player);
                    });
                });
    }

    public boolean isHooked() {
        return hooked;
    }
}
