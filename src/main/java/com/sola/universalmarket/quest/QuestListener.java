package com.sola.universalmarket.quest;

import com.sola.universalmarket.UniversalMarketPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns gameplay into quest progress.
 *
 * A note on movement: distance and biome quests do NOT hook PlayerMoveEvent.
 * That event fires several times per tick per player and hooking it would cost
 * real TPS on a survival server. Instead a task samples each player's position
 * once a second, which is accurate to within a second of walking and costs
 * effectively nothing.
 */
public final class QuestListener implements Listener {

    private final UniversalMarketPlugin plugin;

    /** Last sampled position per player, for the distance sampler. */
    private final Map<UUID, Location> lastPosition = new HashMap<>();

    public QuestListener(UniversalMarketPlugin plugin) {
        this.plugin = plugin;
    }

    private QuestService quests() {
        return plugin.quests();
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        quests().progress(event.getPlayer(), QuestType.MINE_BLOCK,
                event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        quests().progress(event.getPlayer(), QuestType.PLACE_BLOCK,
                event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        quests().progress(event.getPlayer(), QuestType.HARVEST_CROP,
                event.getHarvestedBlock().getType().name(), 1);
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        String type = event.getEntity().getType().name();
        quests().progress(killer, QuestType.KILL_MOB, type, 1);

        // Anything that can attack counts toward the "any hostile" quests.
        if (event.getEntity() instanceof org.bukkit.entity.Monster) {
            quests().progress(killer, QuestType.KILL_ANY_HOSTILE, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        int damage = (int) Math.round(event.getFinalDamage());
        if (damage > 0) quests().progress(player, QuestType.DAMAGE_TAKEN, damage);
    }

    // ------------------------------------------------------------------
    // Animals
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(org.bukkit.event.entity.EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            quests().progress(player, QuestType.BREED_ANIMAL, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            quests().progress(player, QuestType.TAME_ANIMAL, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        quests().progress(event.getPlayer(), QuestType.SHEAR_SHEEP, 1);
    }

    // ------------------------------------------------------------------
    // Fishing
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        quests().progress(event.getPlayer(), QuestType.FISH_CATCH, 1);

        // Treasure is anything that is not one of the four fish.
        if (event.getCaught() instanceof org.bukkit.entity.Item item) {
            String caught = item.getItemStack().getType().name();
            boolean isFish = caught.equals("COD") || caught.equals("SALMON")
                    || caught.equals("TROPICAL_FISH") || caught.equals("PUFFERFISH");
            if (!isFish) quests().progress(event.getPlayer(), QuestType.FISH_TREASURE, 1);
        }
    }

    // ------------------------------------------------------------------
    // Production
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var result = event.getRecipe().getResult();
        int amount = Math.max(1, result.getAmount());
        quests().progress(player, QuestType.CRAFT_ITEM, result.getType().name(), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent event) {
        quests().progress(event.getPlayer(), QuestType.SMELT_ITEM, event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        // Brewing stands have no player attached, so credit whoever is nearest.
        Location location = event.getBlock().getLocation();
        Player nearest = null;
        double best = 64;
        for (Player online : event.getBlock().getWorld().getPlayers()) {
            double distance = online.getLocation().distance(location);
            if (distance < best) { best = distance; nearest = online; }
        }
        if (nearest != null) quests().progress(nearest, QuestType.BREW_POTION, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent event) {
        quests().progress(event.getEnchanter(), QuestType.ENCHANT_ITEM, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(org.bukkit.event.inventory.TradeSelectEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            quests().progress(player, QuestType.VILLAGER_TRADE, 1);
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        quests().progress(event.getPlayer(), QuestType.EAT_FOOD,
                event.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onXp(PlayerExpChangeEvent event) {
        if (event.getAmount() > 0) {
            quests().progress(event.getPlayer(), QuestType.GAIN_XP, event.getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoneMeal(org.bukkit.event.block.BlockFertilizeEvent event) {
        if (event.getPlayer() != null) {
            quests().progress(event.getPlayer(), QuestType.BONE_MEAL, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        quests().progress(event.getPlayer(), QuestType.SLEEP, 1);
    }

    /**
     * Loot chests. Only counts naturally generated containers, identified by the
     * chest still having an unlooted loot table - that is what distinguishes a
     * dungeon chest from a player's storage.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof org.bukkit.loot.Lootable lootable)) return;
        if (lootable.getLootTable() == null) return;
        quests().progress(player, QuestType.LOOT_CHEST, 1);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        lastPosition.remove(event.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Sampled movement
    // ------------------------------------------------------------------

    /** Called once a second by the plugin, not per movement packet. */
    public void sampleMovement() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            Location now = player.getLocation();
            Location before = lastPosition.put(player.getUniqueId(), now.clone());

            quests().noteBiome(player, now.getBlock().getBiome().toString());

            if (before == null || !before.getWorld().equals(now.getWorld())) continue;
            int distance = (int) Math.round(before.distance(now));
            // Ignore teleports so a /home does not complete a travel quest.
            if (distance <= 0 || distance > 100) continue;

            QuestType mode;
            if (player.isGliding()) mode = QuestType.TRAVEL_ELYTRA;
            else if (player.isInsideVehicle()) {
                var vehicle = player.getVehicle();
                if (vehicle instanceof org.bukkit.entity.Boat) mode = QuestType.TRAVEL_BOAT;
                else if (vehicle instanceof org.bukkit.entity.Minecart) mode = QuestType.TRAVEL_MINECART;
                else continue;
            } else if (player.isOnGround() || player.isSwimming()) mode = QuestType.TRAVEL_WALK;
            else continue;

            quests().progress(player, mode, distance);
        }
    }

    public void forget(UUID player) {
        lastPosition.remove(player);
    }
}
