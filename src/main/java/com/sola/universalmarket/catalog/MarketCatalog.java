package com.sola.universalmarket.catalog;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The approved catalog. Once loaded, this is the ONLY authority on what may be
 * bought or sold and at what price.
 *
 * Two lookup paths exist and they are deliberately different:
 *
 *   byMaterial(Material)   used by the creative buy flow. The client only ever
 *                          tells us a Material, so this resolves to the plain
 *                          no-variant entry. A client can never reach a variant
 *                          entry, which means it can never conjure a Mending
 *                          book or a Strength II potion out of a packet.
 *
 *   byKey(Key)             used by menus, selling and commands, where the server
 *                          already knows exactly which approved variant it means.
 */
public final class MarketCatalog {

    private final Logger log;
    private final File file;

    private final Map<String, MarketItem> byId = new HashMap<>();
    private final Map<Material, MarketItem> plainByMaterial = new HashMap<>();
    private final Map<String, List<MarketItem>> byCategory = new HashMap<>();

    public MarketCatalog(Logger log, File dataFolder) {
        this.log = log;
        this.file = new File(dataFolder, "market.yml");
    }

    // ==================================================================
    // Load / generate
    // ==================================================================

    /** Loads market.yml, generating it from scratch the first time. */
    public int load() {
        byId.clear();
        plainByMaterial.clear();
        byCategory.clear();

        if (!file.exists()) {
            log.info("No market.yml found - generating the default catalog. This happens once.");
            List<MarketItem> generated = new CatalogGenerator(log).generate();
            save(generated);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            log.severe("market.yml has no 'items' section. Rename it and restart to regenerate.");
            return 0;
        }

        int broken = 0;
        for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            if (s == null) continue;
            MarketItem item = read(id, s);
            if (item == null) { broken++; continue; }
            index(item);
        }
        if (broken > 0) {
            log.warning(broken + " market.yml entries were skipped as unreadable "
                    + "(unknown material on this server version?).");
        }
        log.info("Loaded " + byId.size() + " approved market entries.");
        return byId.size();
    }

    private void index(MarketItem item) {
        byId.put(item.id(), item);
        if (!item.key().hasVariant()) {
            Material m = item.material();
            if (m != null) plainByMaterial.put(m, item);
        }
        byCategory.computeIfAbsent(item.category(), k -> new ArrayList<>()).add(item);
    }

    private MarketItem read(String id, ConfigurationSection s) {
        // ids are stored exactly as save() wrote them, so parse directly
        MarketItem.Key parsed = MarketItem.Key.parse(id);
        if (parsed == null) return null;
        Material material = parsed.material();
        if (material == null || !material.isItem()) return null;

        try {
            return MarketItem.builder(parsed)
                    .displayName(s.getString("display-name", parsed.toString()))
                    .category(s.getString("category", "misc"))
                    .umBuyPrice(bd(s.getString("um-buy-price", "0")))
                    .serverBuybackBase(bd(s.getString("server-buyback-base", "0")))
                    .suggested(bd(s.getString("player-shop-suggested-min", "0")),
                               bd(s.getString("player-shop-suggested-max", "0")))
                    .dynamicPricing(s.getBoolean("dynamic-pricing-enabled", true))
                    .floorCeiling(bd(s.getString("price-floor", "0")),
                                  bd(s.getString("price-ceiling", "0")))
                    .sellTiers(s.getInt("sell-limit-tier1", 128),
                               s.getInt("sell-limit-tier2", 384),
                               s.getInt("sell-limit-tier3", 1024))
                    .tierRates(s.getDouble("tier2-rate", 0.66),
                               s.getDouble("tier3-rate", 0.33),
                               s.getDouble("tier-floor-rate", 0.10))
                    .dailyDeal(s.getBoolean("daily-deal-eligible", true),
                               s.getDouble("daily-deal-weight", 1.0),
                               s.getDouble("max-discount", 0.30))
                    .highDemand(s.getBoolean("high-demand-eligible", true),
                                s.getDouble("high-demand-weight", 1.0),
                                s.getDouble("max-demand-bonus", 0.45))
                    .contractEligible(s.getBoolean("contract-eligible", true))
                    .rare(s.getBoolean("rare", false),
                          s.getInt("purchase-limit", 0),
                          s.getLong("purchase-reset-ticks", 0L))
                    .blacklisted(s.getBoolean("blacklisted", false))
                    .build();
        } catch (Throwable t) {
            log.warning("Bad market.yml entry '" + id + "': " + t);
            return null;
        }
    }

    private BigDecimal bd(String raw) {
        try { return new BigDecimal(raw.replace(",", "").trim()); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Write the catalog out in a human-editable, commented form. */
    public void save(Collection<MarketItem> items) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "UniversalMarket item catalog.",
                "",
                "Prices are PER SINGLE ITEM, in whole dollars. The UI multiplies",
                "up for stack prices, so 64 Dirt at 312 each shows as ~$20,000.",
                "",
                "Edit anything here freely and run /um reload - no rebuild needed.",
                "Set blacklisted: true to remove an item from the market entirely.",
                "",
                "This file is generated once. It is never overwritten afterwards,",
                "so your edits are safe across restarts and plugin updates."
        ));
        for (MarketItem item : items) {
            String path = "items." + item.id();
            yaml.set(path + ".display-name", item.displayName());
            yaml.set(path + ".category", item.category());
            yaml.set(path + ".um-buy-price", item.umBuyPrice().toPlainString());
            yaml.set(path + ".server-buyback-base", item.serverBuybackBase().toPlainString());
            yaml.set(path + ".player-shop-suggested-min", item.suggestedShopMin().toPlainString());
            yaml.set(path + ".player-shop-suggested-max", item.suggestedShopMax().toPlainString());
            yaml.set(path + ".dynamic-pricing-enabled", item.dynamicPricing());
            yaml.set(path + ".price-floor", item.priceFloor().toPlainString());
            yaml.set(path + ".price-ceiling", item.priceCeiling().toPlainString());
            yaml.set(path + ".sell-limit-tier1", item.sellLimitTier1());
            yaml.set(path + ".sell-limit-tier2", item.sellLimitTier2());
            yaml.set(path + ".sell-limit-tier3", item.sellLimitTier3());
            yaml.set(path + ".tier2-rate", item.tier2Rate());
            yaml.set(path + ".tier3-rate", item.tier3Rate());
            yaml.set(path + ".tier-floor-rate", item.tierFloorRate());
            yaml.set(path + ".daily-deal-eligible", item.dailyDealEligible());
            yaml.set(path + ".daily-deal-weight", item.dailyDealWeight());
            yaml.set(path + ".max-discount", item.maxDiscount());
            yaml.set(path + ".high-demand-eligible", item.highDemandEligible());
            yaml.set(path + ".high-demand-weight", item.highDemandWeight());
            yaml.set(path + ".max-demand-bonus", item.maxDemandBonus());
            yaml.set(path + ".contract-eligible", item.contractEligible());
            yaml.set(path + ".rare", item.rare());
            yaml.set(path + ".purchase-limit", item.purchaseLimit());
            yaml.set(path + ".blacklisted", item.blacklisted());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            yaml.save(file);
            log.info("Wrote " + items.size() + " entries to market.yml");
        } catch (IOException e) {
            log.severe("Could not write market.yml: " + e);
        }
    }

    // ==================================================================
    // Lookups
    // ==================================================================

    public MarketItem byId(String id) {
        MarketItem item = byId.get(id.toLowerCase(Locale.ROOT));
        return (item == null || item.blacklisted()) ? null : item;
    }

    public MarketItem byKey(MarketItem.Key key) {
        return key == null ? null : byId(key.toString());
    }

    /**
     * Plain, no-variant entry for a Material. This is the only lookup the
     * creative buy flow uses, which is why a packet can never reach a variant.
     */
    public MarketItem byMaterial(Material material) {
        if (material == null) return null;
        MarketItem item = plainByMaterial.get(material);
        return (item == null || item.blacklisted()) ? null : item;
    }

    public Collection<MarketItem> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<MarketItem> category(String category) {
        return Collections.unmodifiableList(
                byCategory.getOrDefault(category, Collections.emptyList()));
    }

    public List<String> categories() {
        List<String> out = new ArrayList<>(byCategory.keySet());
        Collections.sort(out);
        return out;
    }

    /** Simple name search, used by /um price. */
    public List<MarketItem> search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<MarketItem> exact = new ArrayList<>();
        List<MarketItem> partial = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.blacklisted()) continue;
            String name = item.displayName().toLowerCase(Locale.ROOT);
            if (name.equals(q)) exact.add(item);
            else if (name.contains(q) || item.id().contains(q)) partial.add(item);
            if (exact.size() >= limit) break;
        }
        exact.addAll(partial);
        return exact.size() > limit ? exact.subList(0, limit) : exact;
    }

    // ==================================================================
    // Approved stack construction
    // ==================================================================

    /**
     * Build a clean ItemStack from an approved catalog entry.
     *
     * Everything the player receives is created here, from scratch, on the
     * server. Nothing from a client packet ever reaches this method beyond a
     * Material lookup, so a spoofed enchantment or custom NBT has no path in.
     */
    public ItemStack createApprovedStack(MarketItem item, int amount) {
        if (item == null || amount <= 0) return null;
        Material material = item.material();
        if (material == null || !material.isItem()) return null;

        int capped = Math.min(amount, material.getMaxStackSize());
        ItemStack stack = new ItemStack(material, capped);
        if (!item.key().hasVariant()) return stack;

        String variant = item.key().variant();
        try {
            switch (material) {
                case POTION, SPLASH_POTION, LINGERING_POTION, TIPPED_ARROW -> {
                    PotionType type = matchPotion(variant);
                    if (type == null) return null;
                    if (stack.getItemMeta() instanceof PotionMeta meta) {
                        meta.setBasePotionType(type);
                        stack.setItemMeta(meta);
                    }
                }
                case ENCHANTED_BOOK -> {
                    int underscore = variant.lastIndexOf('_');
                    if (underscore <= 0) return null;
                    String enchId = variant.substring(0, underscore);
                    int level = Integer.parseInt(variant.substring(underscore + 1));
                    Enchantment ench = Registry.ENCHANTMENT.get(
                            NamespacedKey.minecraft(enchId));
                    if (ench == null) return null;
                    if (stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                        meta.addStoredEnchant(ench, level, true);
                        stack.setItemMeta(meta);
                    }
                }
                case GOAT_HORN -> {
                    org.bukkit.MusicInstrument instrument =
                            Registry.INSTRUMENT.get(NamespacedKey.minecraft(variant));
                    if (instrument == null) return null;
                    ItemMeta raw = stack.getItemMeta();
                    if (raw instanceof org.bukkit.inventory.meta.MusicInstrumentMeta meta) {
                        meta.setInstrument(instrument);
                        stack.setItemMeta(meta);
                    }
                }
                default -> { /* no variant handling needed */ }
            }
        } catch (Throwable t) {
            log.warning("Could not build approved stack for " + item.id() + ": " + t);
            return null;
        }
        return stack;
    }

    private PotionType matchPotion(String variant) {
        try {
            return PotionType.valueOf(variant.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Convenience: the stack price shown in menus. */
    public BigDecimal stackPrice(MarketItem item) {
        Material m = item.material();
        int size = m == null ? 64 : m.getMaxStackSize();
        return item.umBuyPrice().multiply(BigDecimal.valueOf(size));
    }

    public Optional<MarketItem> optional(String id) {
        return Optional.ofNullable(byId(id));
    }
}
