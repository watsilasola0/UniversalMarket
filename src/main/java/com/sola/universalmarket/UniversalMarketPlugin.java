package com.sola.universalmarket;

import com.sola.universalmarket.bedrock.BedrockService;
import com.sola.universalmarket.catalog.MarketCatalog;
import com.sola.universalmarket.command.UMCommand;
import com.sola.universalmarket.config.Messages;
import com.sola.universalmarket.creative.CreativeMarketService;
import com.sola.universalmarket.economy.EconomyService;
import com.sola.universalmarket.listener.ProtectionListener;
import com.sola.universalmarket.market.PricingService;
import com.sola.universalmarket.market.RareGoodsService;
import com.sola.universalmarket.market.AnnouncementService;
import com.sola.universalmarket.market.ListingService;
import com.sola.universalmarket.market.SellFlowService;
import com.sola.universalmarket.market.LeaderboardService;
import com.sola.universalmarket.market.PurchaseService;
import com.sola.universalmarket.market.SellService;
import com.sola.universalmarket.shops.PlayerShopService;
import com.sola.universalmarket.shops.QuickShopAdapter;
import com.sola.universalmarket.shops.ShopNotificationService;
import com.sola.universalmarket.listener.SellChestListener;
import com.sola.universalmarket.ui.GuiListener;
import com.sola.universalmarket.ui.Sounds;
import com.sola.universalmarket.ui.MarketMenus;
import com.sola.universalmarket.storage.StorageService;
import com.sola.universalmarket.terminal.TerminalService;
import com.sola.universalmarket.transaction.TransactionService;
import com.sola.universalmarket.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point and service registry.
 *
 * Startup order matters and is not arbitrary:
 *   config -> economy -> storage -> catalog -> pricing -> creative -> listeners
 * Each stage depends on the one before it. Anything that fails a hard
 * requirement disables the plugin loudly rather than limping along with a
 * half-working economy, because a market that silently misprices things is
 * worse than a market that is switched off.
 */
public final class UniversalMarketPlugin extends JavaPlugin {

    private Messages messages;
    private EconomyService economy;
    private StorageService storage;
    private MarketCatalog catalog;
    private PricingService pricing;
    private RareGoodsService rareGoods;
    private TransactionService transactions;
    private PlayerShopService playerShops;
    private BedrockService bedrock;
    private TerminalService terminal;
    private CreativeMarketService creative;
    private SellService sell;
    private QuickShopAdapter quickShop;
    private MarketMenus menus;
    private PurchaseService purchases;
    private LeaderboardService leaderboard;
    private AnnouncementService announcements;
    private ShopNotificationService shopNotifications;
    private Sounds sounds;
    private ListingService listings;
    private SellFlowService sellFlow;

    private boolean packetEventsHooked = false;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        getLogger().info("Paper " + Bukkit.getMinecraftVersion() + " detected");

        // ---- configuration ----
        saveDefaultConfig();
        this.messages = new Messages(this);
        applyNumberFormatSettings();

        // ---- economy (hard requirement) ----
        this.economy = new EconomyService(this);
        if (!economy.setup()) {
            getLogger().severe("Economy unavailable - disabling UniversalMarket.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---- storage (hard requirement) ----
        this.storage = new StorageService(this);
        if (!storage.initialise()) {
            getLogger().severe("Storage unavailable - disabling UniversalMarket.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---- catalog ----
        this.catalog = new MarketCatalog(getLogger(), getDataFolder());
        int loaded = catalog.load();
        if (loaded == 0) {
            getLogger().severe("Market catalog is empty - disabling UniversalMarket.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---- market services ----
        this.transactions = new TransactionService(storage);
        this.pricing = new PricingService(this, catalog, storage);
        this.rareGoods = new RareGoodsService(this, storage);
        this.playerShops = new PlayerShopService();

        pricing.loadState();
        rareGoods.loadState();
        if (pricing.deals().isEmpty()) pricing.rollCycle();

        // ---- optional integrations ----
        this.bedrock = new BedrockService(getLogger());
        bedrock.setup();
        logQuickShopStatus();

        // ---- terminal ----
        this.terminal = new TerminalService(this);

        // ---- creative market (needs PacketEvents) ----
        this.packetEventsHooked = hookPacketEvents();
        if (packetEventsHooked && getConfig().getBoolean("creative-market.enabled", true)) {
            this.creative = new CreativeMarketService(this, economy, catalog);
            creative.register();
            getLogger().info("Java Creative Market ready");
        } else {
            getLogger().warning("Creative Market disabled - Buy Items will fall back to menus.");
        }

        // ---- selling, shops and menus ----
        this.sell = new SellService(this);
        this.sell.loadState();

        this.quickShop = new QuickShopAdapter(this);
        boolean shopsBound = quickShop.bind();
        playerShops.setAvailable(shopsBound);
        if (shopsBound) scheduleShopIndex();

        this.sounds = new Sounds(this);
        this.announcements = new AnnouncementService(this);
        this.purchases = new PurchaseService(this);
        this.leaderboard = new LeaderboardService(this);
        scheduleLeaderboard();
        scheduleTickers();
        this.listings = new ListingService(this);
        this.listings.load();
        this.sellFlow = new SellFlowService(this);
        this.menus = new MarketMenus(this);

        this.shopNotifications = new ShopNotificationService(this);
        this.shopNotifications.hook();

        // ---- listeners and commands ----
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new SellChestListener(this), this);
        UMCommand command = new UMCommand(this);
        var registered = getCommand("um");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'um' is missing from plugin.yml.");
        }
        var sellCommand = getCommand("sell");
        if (sellCommand != null) {
            sellCommand.setExecutor(new com.sola.universalmarket.command.SellCommand(this));
        } else {
            getLogger().severe("Command 'sell' is missing from plugin.yml.");
        }

        // ---- scheduled work ----
        scheduleCycleTask();
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, storage::pruneOldData, 200L);

        getLogger().info("Enabled in " + (System.currentTimeMillis() - start) + "ms.");
    }

    @Override
    public void onDisable() {
        // Order is the reverse of startup: get players out of fake creative
        // BEFORE tearing down anything they depend on.
        if (creative != null) creative.shutdown();
        if (storage != null) storage.shutdown();
        getLogger().info("Disabled.");
    }

    // ==================================================================
    // Startup helpers
    // ==================================================================

    private void applyNumberFormatSettings() {
        NumberFormatter.setSymbol(getConfig().getString("number-format.symbol", "$"));
        NumberFormatter.setAbbreviateAt(getConfig().getLong("number-format.abbreviate-at", 100_000L));
    }

    private boolean hookPacketEvents() {
        Plugin pe = Bukkit.getPluginManager().getPlugin("packetevents");
        if (pe == null || !pe.isEnabled()) {
            getLogger().warning("PacketEvents not found - the native Creative Market cannot run.");
            return false;
        }
        try {
            if (com.github.retrooper.packetevents.PacketEvents.getAPI() == null) {
                getLogger().warning("PacketEvents is present but its API is not initialised.");
                return false;
            }
            getLogger().info("PacketEvents " + pe.getPluginMeta().getVersion() + " hooked");
            return true;
        } catch (Throwable t) {
            getLogger().warning("Could not reach the PacketEvents API: " + t);
            return false;
        }
    }

    private void logQuickShopStatus() {
        Plugin qs = Bukkit.getPluginManager().getPlugin("QuickShop-Hikari");
        if (qs == null) qs = Bukkit.getPluginManager().getPlugin("QuickShop");
        if (qs == null || !qs.isEnabled()) {
            getLogger().info("QuickShop not detected - Player Shops will be unavailable.");
            playerShops.setAvailable(false);
            return;
        }
        // The adapter arrives in step 4; for now we only report presence and
        // leave the index empty rather than showing invented listings.
        getLogger().info("QuickShop-Hikari " + qs.getPluginMeta().getVersion()
                + " detected (shop indexing arrives in a later update).");
        playerShops.setAvailable(false);
    }

    private void scheduleCycleTask() {
        long hours = Math.max(1L, getConfig().getLong("market-cycle.hours", 24));
        long periodTicks = hours * 3_600L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (pricing.cycleEndsInMillis() <= 0) pricing.rollCycle();
        }, 1200L, Math.min(periodTicks, 72_000L)); // check at least every hour
    }

    /** Full reload used by /um reload. */
    public int reloadEverything() {
        reloadConfig();
        applyNumberFormatSettings();
        messages.reload();
        return catalog.load();
    }

    // ==================================================================
    // Service accessors
    // ==================================================================

    public Messages messages() { return messages; }
    public EconomyService economy() { return economy; }
    public StorageService storage() { return storage; }
    public MarketCatalog catalog() { return catalog; }
    public PricingService pricing() { return pricing; }
    public RareGoodsService rareGoods() { return rareGoods; }
    public TransactionService transactions() { return transactions; }
    public PlayerShopService playerShops() { return playerShops; }
    public BedrockService bedrock() { return bedrock; }
    public TerminalService terminal() { return terminal; }
    public CreativeMarketService creative() { return creative; }
    public SellService sell() { return sell; }
    public QuickShopAdapter quickShop() { return quickShop; }
    public MarketMenus menus() { return menus; }
    public PurchaseService purchases() { return purchases; }
    public LeaderboardService leaderboard() { return leaderboard; }
    public AnnouncementService announcements() { return announcements; }
    public ShopNotificationService shopNotifications() { return shopNotifications; }
    public Sounds sounds() { return sounds; }
    public ListingService listings() { return listings; }
    public SellFlowService sellFlow() { return sellFlow; }
    public boolean packetEventsHooked() { return packetEventsHooked; }

    /**
     * Rebuild the player-shop index on a timer.
     *
     * Deliberately NOT per click or per search. Spec section 48: never query the
     * QuickShop database on every interaction. We read its shop manager once per
     * interval and serve every lookup from the cached snapshot.
     */
    private void scheduleShopIndex() {
        long seconds = Math.max(15L, getConfig().getLong("player-shops.index-refresh-seconds", 60));
        long ticks = seconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                var fresh = quickShop.buildIndex();
                playerShops.replaceIndex(fresh);
            } catch (Throwable t) {
                getLogger().warning("Player shop index refresh failed: " + t);
            }
        }, 100L, ticks);
    }

    /** Rebuild rankings on a timer. Vault lookups are main-thread work. */
    private void scheduleLeaderboard() {
        long seconds = Math.max(30L, getConfig().getLong("leaderboard.refresh-seconds", 120));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                leaderboard.refresh();
            } catch (Throwable t) {
                getLogger().warning("Leaderboard refresh failed: " + t);
            }
        }, 120L, seconds * 20L);

        // Fast repaint using online players' live balances only.
        long live = Math.max(1L, getConfig().getLong("leaderboard.sidebar.live-refresh-seconds", 3));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                leaderboard.refreshLive();
            } catch (Throwable t) {
                getLogger().warning("Live leaderboard refresh failed: " + t);
            }
        }, 60L, live * 20L);
    }

    /**
     * Two light tickers.
     *
     * The cycle check runs every 10 seconds and rolls whichever of the deal /
     * demand clocks has expired. The menu ticker re-renders the countdown slot
     * of any open menu once a second, which is what makes the clocks actually
     * tick instead of freezing at whatever time the screen was opened.
     */
    private void scheduleTickers() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                pricing.tickCycles();
            } catch (Throwable t) {
                getLogger().warning("Cycle tick failed: " + t);
            }
        }, 200L, 200L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                var holder = player.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof com.sola.universalmarket.ui.Gui gui) gui.refreshLive();
            }
        }, 20L, 20L);
    }
}
