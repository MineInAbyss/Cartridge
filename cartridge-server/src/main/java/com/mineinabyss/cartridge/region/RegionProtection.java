package com.mineinabyss.cartridge.region;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Runs before any plugin listener so plugins see protected actions as already cancelled.
 * Also called for asynchronous events, of which only chat is handled
 */
@NullMarked
public final class RegionProtection {

    // Denying a block that has no interaction would also stop placing blocks against it and using the held item
    private static final ClassValue<Boolean> INTERACTIVE_BLOCKS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            for (Class<?> current = type; current != null && current != net.minecraft.world.level.block.Block.class && current != BlockBehaviour.class; current = current.getSuperclass()) {
                for (final Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals("useWithoutItem") || method.getName().equals("useItemOn")) return true;
                }
            }
            return false;
        }
    };

    private static final Set<CreatureSpawnEvent.SpawnReason> PLUGIN_SPAWNS = Set.of(CreatureSpawnEvent.SpawnReason.CUSTOM, CreatureSpawnEvent.SpawnReason.COMMAND);
    // Plugin teleports such as DeeperWorld section transitions must never be blocked by entry or exit
    private static final Set<PlayerTeleportEvent.TeleportCause> PLAYER_TELEPORTS = Set.of(
        PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
        PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT,
        PlayerTeleportEvent.TeleportCause.COMMAND,
        PlayerTeleportEvent.TeleportCause.SPECTATE,
        PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
        PlayerTeleportEvent.TeleportCause.END_PORTAL,
        PlayerTeleportEvent.TeleportCause.END_GATEWAY
    );
    private static final Set<Material> ROCK_GROWTH = Set.of(
        Material.BUDDING_AMETHYST, Material.SMALL_AMETHYST_BUD, Material.MEDIUM_AMETHYST_BUD,
        Material.LARGE_AMETHYST_BUD, Material.AMETHYST_CLUSTER, Material.POINTED_DRIPSTONE
    );
    private static final Set<Material> VINES = Set.of(
        Material.VINE, Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.TWISTING_VINES,
        Material.TWISTING_VINES_PLANT, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.GLOW_LICHEN
    );
    private static final Set<Material> REDSTONE = Set.of(
        Material.LEVER, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR, Material.NOTE_BLOCK
    );
    private static final Set<Material> LAVA_HARDEN = Set.of(Material.OBSIDIAN, Material.COBBLESTONE, Material.STONE, Material.BASALT);

    private RegionProtection() {
    }

    public static void handle(final Event event) {
        final CartridgeRegions regions = CartridgeRegions.instance();
        if (regions.isEmpty()) return;

        switch (event) {
            case AsyncChatEvent e -> chat(regions, e);
            case PlayerInteractEvent e -> interact(regions, e);
            case PlayerInteractEntityEvent e -> check(regions, e, e.getPlayer(), e.getRightClicked().getLocation(), entityInteractFlag(e.getPlayer(), e.getRightClicked()));
            case EntityMountEvent e when e.getEntity() instanceof Player player -> check(regions, e, player, e.getMount().getLocation(), RegionFlag.RIDE);
            case PlayerBedEnterEvent e -> check(regions, e, e.getPlayer(), e.getBed().getLocation(), RegionFlag.SLEEP);
            case PlayerTeleportEvent e -> teleport(regions, e);
            case PlayerMoveEvent e -> move(regions, e);
            case PlayerAttemptPickupItemEvent e -> check(regions, e, e.getPlayer(), e.getItem().getLocation(), RegionFlag.ITEM_PICKUP);
            case PlayerDropItemEvent e -> check(regions, e, e.getPlayer(), e.getPlayer().getLocation(), RegionFlag.ITEM_DROP);

            case BlockPlaceEvent e -> check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_PLACE);
            case BlockBreakEvent e -> {
                check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_BREAK);
                if (regions.isDenied(e.getBlock().getLocation(), RegionFlag.EXP_DROPS)) e.setExpToDrop(0);
            }
            case BlockExpEvent e -> {
                if (regions.isDenied(e.getBlock().getLocation(), RegionFlag.EXP_DROPS)) e.setExpToDrop(0);
            }
            case PlayerBucketEmptyEvent e -> check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_PLACE);
            case PlayerBucketFillEvent e -> check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_BREAK);
            case SignChangeEvent e -> check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_PLACE);
            case BlockFertilizeEvent e -> check(regions, e, e.getPlayer(), e.getBlock().getLocation(), RegionFlag.BLOCK_PLACE);
            case BlockIgniteEvent e -> ignite(regions, e);
            case BlockBurnEvent e -> deny(regions, e, e.getBlock().getLocation(), RegionFlag.FIRE_SPREAD);
            case EntityChangeBlockEvent e -> changeBlock(regions, e);
            case HangingPlaceEvent e -> check(regions, e, e.getPlayer(), e.getEntity().getLocation(), RegionFlag.BLOCK_PLACE);
            case EntityPlaceEvent e -> check(regions, e, e.getPlayer(), e.getEntity().getLocation(), e.getEntity() instanceof Vehicle ? RegionFlag.VEHICLE_PLACE : RegionFlag.BLOCK_PLACE);
            case HangingBreakByEntityEvent e -> {
                final Player player = attackingPlayer(e.getRemover());
                if (player != null) {
                    check(regions, e, player, e.getEntity().getLocation(), RegionFlag.BLOCK_BREAK);
                } else if (e.getRemover() != null) {
                    deny(regions, e, e.getEntity().getLocation(), hangingDestroyFlag(e.getEntity()));
                }
            }
            case VehicleDamageEvent e -> check(regions, e, attackingPlayer(e.getAttacker()), e.getVehicle().getLocation(), RegionFlag.VEHICLE_DESTROY);
            case VehicleDestroyEvent e -> check(regions, e, attackingPlayer(e.getAttacker()), e.getVehicle().getLocation(), RegionFlag.VEHICLE_DESTROY);

            case EntityDamageEvent e -> damage(regions, e);
            case EntityRegainHealthEvent e when e.getEntity() instanceof Player player
                && (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) ->
                deny(regions, e, player.getLocation(), RegionFlag.NATURAL_HEALTH_REGEN);
            case FoodLevelChangeEvent e when e.getItem() == null && e.getFoodLevel() < e.getEntity().getFoodLevel() ->
                deny(regions, e, e.getEntity().getLocation(), RegionFlag.NATURAL_HUNGER_DRAIN);
            case EntityDeathEvent e when !(e.getEntity() instanceof Player) -> {
                if (regions.isDenied(e.getEntity().getLocation(), RegionFlag.EXP_DROPS)) e.setDroppedExp(0);
            }
            case PotionSplashEvent e -> check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.POTION_SPLASH);
            case LingeringPotionSplashEvent e -> check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.POTION_SPLASH);
            case ProjectileHitEvent e when e.getEntity() instanceof BreezeWindCharge -> deny(regions, e, e.getEntity().getLocation(), RegionFlag.BREEZE_CHARGE_EXPLOSION);
            case ProjectileHitEvent e when e.getEntity() instanceof WindCharge -> check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.WIND_CHARGE_BURST);
            case CreatureSpawnEvent e when e.getEntity() instanceof Mob && !PLUGIN_SPAWNS.contains(e.getSpawnReason()) -> {
                final Location location = e.getLocation();
                if (regions.isDenied(location, RegionFlag.MOB_SPAWNING) || regions.denySpawn(location).contains(e.getEntityType())) e.setCancelled(true);
            }

            case EntityExplodeEvent e -> filterExplosion(regions, e.blockList(), explosionFlag(e.getEntity()));
            case BlockExplodeEvent e -> filterExplosion(regions, e.blockList(), RegionFlag.OTHER_EXPLOSION);
            case LightningStrikeEvent e when e.getCause() != LightningStrikeEvent.Cause.COMMAND && e.getCause() != LightningStrikeEvent.Cause.CUSTOM ->
                deny(regions, e, e.getLightning().getLocation(), RegionFlag.LIGHTNING);
            case BlockPistonExtendEvent e -> pistons(regions, e, e.getBlock(), e.getBlocks(), e.getDirection());
            case BlockPistonRetractEvent e -> pistons(regions, e, e.getBlock(), e.getBlocks(), e.getDirection());
            case EntityBlockFormEvent e -> {
                if (e.getEntity() instanceof Snowman) {
                    deny(regions, e, e.getBlock().getLocation(), RegionFlag.SNOWMAN_TRAILS);
                } else if (e.getNewState().getType() == Material.FROSTED_ICE) {
                    deny(regions, e, e.getBlock().getLocation(), RegionFlag.FROSTED_ICE_FORM);
                }
            }
            case BlockSpreadEvent e -> deny(regions, e, e.getBlock().getLocation(), spreadFlag(e.getSource().getType(), e.getNewState().getType()));
            case BlockFormEvent e -> deny(regions, e, e.getBlock().getLocation(), formFlag(e.getBlock().getType(), e.getNewState().getType()));
            case BlockGrowEvent e -> deny(regions, e, e.getBlock().getLocation(), growFlag(e.getNewState().getType()));
            case BlockFadeEvent e -> deny(regions, e, e.getBlock().getLocation(), fadeFlag(e.getBlock().getType()));
            case LeavesDecayEvent e -> deny(regions, e, e.getBlock().getLocation(), RegionFlag.LEAF_DECAY);
            case MoistureChangeEvent e -> deny(regions, e, e.getBlock().getLocation(), RegionFlag.MOISTURE_CHANGE);
            case BlockFromToEvent e -> deny(regions, e, e.getToBlock().getLocation(), flowFlag(e.getBlock()));
            default -> {
            }
        }
    }

    private static void check(final CartridgeRegions regions, final Cancellable event, final @Nullable Player player, final Location location, final @Nullable RegionFlag flag) {
        if (player != null && flag != null && !regions.isAllowed(player, location, flag)) {
            event.setCancelled(true);
        }
    }

    private static void deny(final CartridgeRegions regions, final Cancellable event, final Location location, final @Nullable RegionFlag flag) {
        if (flag != null && regions.isDenied(location, flag)) {
            event.setCancelled(true);
        }
    }

    private static void chat(final CartridgeRegions regions, final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        if (!regions.isAllowed(player, player.getLocation(), RegionFlag.SEND_CHAT)) {
            event.setCancelled(true);
            return;
        }
        event.viewers().removeIf(viewer -> viewer instanceof Player receiver && regions.isDenied(receiver.getLocation(), RegionFlag.RECEIVE_CHAT));
    }

    private static void move(final CartridgeRegions regions, final PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        final Player player = event.getPlayer();
        final Location from = event.getFrom();
        final Location to = event.getTo();

        if (!regions.isAllowed(player, to, RegionFlag.ENTRY) && regions.isAllowed(player, from, RegionFlag.ENTRY)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't enter this area", NamedTextColor.RED));
        } else if (!regions.isAllowed(player, from, RegionFlag.EXIT) && regions.isAllowed(player, to, RegionFlag.EXIT)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't leave this area", NamedTextColor.RED));
        }
    }

    private static void teleport(final CartridgeRegions regions, final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        final Location from = event.getFrom();
        final Location to = event.getTo();
        final RegionFlag teleportFlag = switch (event.getCause()) {
            case ENDER_PEARL -> RegionFlag.ENDERPEARL;
            case CONSUMABLE_EFFECT -> RegionFlag.CHORUS_FRUIT_TELEPORT;
            default -> null;
        };

        if (teleportFlag != null && (!regions.isAllowed(player, from, teleportFlag) || !regions.isAllowed(player, to, teleportFlag))) {
            event.setCancelled(true);
            return;
        }
        if (!PLAYER_TELEPORTS.contains(event.getCause())) return;

        if (!regions.isAllowed(player, to, RegionFlag.ENTRY) && regions.isAllowed(player, from, RegionFlag.ENTRY)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't enter this area", NamedTextColor.RED));
        } else if (!regions.isAllowed(player, from, RegionFlag.EXIT_VIA_TELEPORT) && regions.isAllowed(player, to, RegionFlag.EXIT_VIA_TELEPORT)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't teleport out of this area", NamedTextColor.RED));
        }
    }

    private static void interact(final CartridgeRegions regions, final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null) return;

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                final RegionFlag flag = blockInteractFlag(event.getPlayer(), block);
                if (flag != null && !regions.isAllowed(event.getPlayer(), block.getLocation(), flag)) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
            }
            case PHYSICAL -> check(regions, event, event.getPlayer(), block.getLocation(), physicalFlag(block.getType()));
            default -> {
            }
        }
    }

    private static @Nullable RegionFlag blockInteractFlag(final Player player, final Block block) {
        if (player.isSneaking() && (!player.getInventory().getItemInMainHand().isEmpty() || !player.getInventory().getItemInOffHand().isEmpty())) {
            return null;
        }

        final Material type = block.getType();
        if (type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR) return null;
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) return RegionFlag.USE_OPENABLES;
        if (Tag.BUTTONS.isTagged(type) || REDSTONE.contains(type)) return RegionFlag.USE_REDSTONE;
        if (Tag.ANVIL.isTagged(type)) return RegionFlag.USE_ANVIL;
        if (Tag.BEDS.isTagged(type)) return RegionFlag.SLEEP;
        if (type == Material.RESPAWN_ANCHOR) return RegionFlag.RESPAWN_ANCHORS;
        if (block.getState(false) instanceof InventoryHolder) return RegionFlag.CHEST_ACCESS;

        final net.minecraft.world.level.block.Block nmsBlock = ((CraftBlock) block).getBlockState().getBlock();
        return INTERACTIVE_BLOCKS.get(nmsBlock.getClass()) ? RegionFlag.INTERACT : null;
    }

    private static @Nullable RegionFlag physicalFlag(final Material type) {
        if (Tag.PRESSURE_PLATES.isTagged(type) || type == Material.TRIPWIRE) return RegionFlag.TRIGGER_PLATES;
        if (type == Material.BIG_DRIPLEAF) return RegionFlag.USE_DRIPLEAF;
        if (type == Material.FARMLAND || type == Material.TURTLE_EGG) return RegionFlag.BLOCK_TRAMPLING;
        return null;
    }

    private static RegionFlag entityInteractFlag(final Player player, final Entity entity) {
        if (entity instanceof StorageMinecart || entity instanceof HopperMinecart) return RegionFlag.CHEST_ACCESS;
        if (entity instanceof ChestBoat && player.isSneaking()) return RegionFlag.CHEST_ACCESS;
        if (entity instanceof ItemFrame frame) return frame.getItem().isEmpty() ? RegionFlag.BLOCK_PLACE : RegionFlag.ITEM_FRAME_ROTATION;
        if (entity instanceof Vehicle) return RegionFlag.RIDE;
        return RegionFlag.INTERACT;
    }

    private static void ignite(final CartridgeRegions regions, final BlockIgniteEvent event) {
        final Location location = event.getBlock().getLocation();
        switch (event.getCause()) {
            case SPREAD -> deny(regions, event, location, RegionFlag.FIRE_SPREAD);
            case LAVA -> deny(regions, event, location, RegionFlag.LAVA_FIRE);
            case LIGHTNING -> deny(regions, event, location, RegionFlag.LIGHTNING);
            default -> check(regions, event, event.getPlayer(), location, RegionFlag.LIGHTER);
        }
    }

    private static void changeBlock(final CartridgeRegions regions, final EntityChangeBlockEvent event) {
        final Location location = event.getBlock().getLocation();
        final Material type = event.getBlock().getType();
        final boolean trampling = type == Material.FARMLAND || type == Material.TURTLE_EGG;
        switch (event.getEntity()) {
            case Player player -> check(regions, event, player, location, trampling ? RegionFlag.BLOCK_TRAMPLING : RegionFlag.BLOCK_PLACE);
            case Enderman ignored -> deny(regions, event, location, RegionFlag.ENDERMAN_GRIEF);
            case Ravager ignored -> deny(regions, event, location, RegionFlag.RAVAGER_GRIEF);
            case Wither ignored -> deny(regions, event, location, RegionFlag.WITHER_DAMAGE);
            case WitherSkull ignored -> deny(regions, event, location, RegionFlag.WITHER_DAMAGE);
            default -> {
                if (trampling) deny(regions, event, location, RegionFlag.BLOCK_TRAMPLING);
            }
        }
    }

    private static void damage(final CartridgeRegions regions, final EntityDamageEvent event) {
        final Entity victim = event.getEntity();
        final Entity attacker = event.getDamageSource().getCausingEntity();
        final Location location = victim.getLocation();

        if (event.getDamageSource().getDirectEntity() instanceof Firework && regions.isDenied(location, RegionFlag.FIREWORK_DAMAGE)) {
            event.setCancelled(true);
            return;
        }

        switch (victim) {
            case Player player -> {
                if (attacker instanceof Player source && source != player
                    && (!regions.isAllowed(source, location, RegionFlag.PVP) || !regions.isAllowed(source, source.getLocation(), RegionFlag.PVP))) {
                    event.setCancelled(true);
                    return;
                }

                final RegionFlag flag;
                if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                    flag = RegionFlag.FALL_DAMAGE;
                } else if (attacker != null && !(attacker instanceof Player)) {
                    flag = RegionFlag.MOB_DAMAGE;
                } else {
                    flag = RegionFlag.PLAYER_DAMAGE;
                }
                deny(regions, event, location, flag);
            }
            case ArmorStand ignored -> check(regions, event, attacker instanceof Player source ? source : null, location, RegionFlag.BLOCK_BREAK);
            case Hanging hanging -> {
                if (attacker instanceof Player source) {
                    check(regions, event, source, location, RegionFlag.BLOCK_BREAK);
                } else if (attacker != null) {
                    deny(regions, event, location, hangingDestroyFlag(hanging));
                }
            }
            case Animals ignored -> check(regions, event, attacker instanceof Player source ? source : null, location, RegionFlag.DAMAGE_ANIMALS);
            case WaterMob ignored -> check(regions, event, attacker instanceof Player source ? source : null, location, RegionFlag.DAMAGE_ANIMALS);
            case Ambient ignored -> check(regions, event, attacker instanceof Player source ? source : null, location, RegionFlag.DAMAGE_ANIMALS);
            default -> {
            }
        }
    }

    private static @Nullable RegionFlag hangingDestroyFlag(final Hanging hanging) {
        if (hanging instanceof Painting) return RegionFlag.ENTITY_PAINTING_DESTROY;
        if (hanging instanceof ItemFrame) return RegionFlag.ENTITY_ITEM_FRAME_DESTROY;
        return null;
    }

    private static RegionFlag explosionFlag(final Entity entity) {
        return switch (entity) {
            case TNTPrimed ignored -> RegionFlag.TNT;
            case ExplosiveMinecart ignored -> RegionFlag.TNT;
            case Creeper ignored -> RegionFlag.CREEPER_EXPLOSION;
            case EnderDragon ignored -> RegionFlag.ENDERDRAGON_BLOCK_DAMAGE;
            case Wither ignored -> RegionFlag.WITHER_DAMAGE;
            case WitherSkull ignored -> RegionFlag.WITHER_DAMAGE;
            case BreezeWindCharge ignored -> RegionFlag.BREEZE_CHARGE_EXPLOSION;
            case Fireball fireball when fireball.getShooter() instanceof Ghast -> RegionFlag.GHAST_FIREBALL;
            default -> RegionFlag.OTHER_EXPLOSION;
        };
    }

    private static void filterExplosion(final CartridgeRegions regions, final List<Block> blocks, final RegionFlag flag) {
        blocks.removeIf(block -> regions.isDenied(block.getLocation(), flag));
    }

    private static void pistons(final CartridgeRegions regions, final Cancellable event, final Block piston, final List<Block> blocks, final BlockFace direction) {
        if (regions.isDenied(piston.getLocation(), RegionFlag.PISTONS)) {
            event.setCancelled(true);
            return;
        }
        for (final Block block : blocks) {
            if (regions.isDenied(block.getLocation(), RegionFlag.PISTONS) || regions.isDenied(block.getRelative(direction).getLocation(), RegionFlag.PISTONS)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static RegionFlag spreadFlag(final Material source, final Material result) {
        if (source == Material.FIRE || source == Material.SOUL_FIRE) return RegionFlag.FIRE_SPREAD;
        if (source == Material.BROWN_MUSHROOM || source == Material.RED_MUSHROOM) return RegionFlag.MUSHROOM_GROWTH;
        if (source == Material.GRASS_BLOCK) return RegionFlag.GRASS_GROWTH;
        if (source == Material.MYCELIUM) return RegionFlag.MYCELIUM_SPREAD;
        if (VINES.contains(source)) return RegionFlag.VINE_GROWTH;
        if (source == Material.SCULK_CATALYST || source == Material.SCULK || result == Material.SCULK || result == Material.SCULK_VEIN) return RegionFlag.SCULK_GROWTH;
        if (ROCK_GROWTH.contains(source) || ROCK_GROWTH.contains(result)) return RegionFlag.ROCK_GROWTH;
        return RegionFlag.CROP_GROWTH;
    }

    private static @Nullable RegionFlag formFlag(final Material current, final Material result) {
        if (result == Material.SNOW) return RegionFlag.SNOW_FALL;
        if (result == Material.ICE) return RegionFlag.ICE_FORM;
        if (LAVA_HARDEN.contains(result)) return RegionFlag.LAVA_HARDEN;
        if (current.name().contains("COPPER")) return RegionFlag.COPPER_FADE;
        return null;
    }

    private static RegionFlag growFlag(final Material result) {
        if (ROCK_GROWTH.contains(result)) return RegionFlag.ROCK_GROWTH;
        if (VINES.contains(result)) return RegionFlag.VINE_GROWTH;
        return RegionFlag.CROP_GROWTH;
    }

    private static @Nullable RegionFlag fadeFlag(final Material type) {
        if (type == Material.SNOW || type == Material.SNOW_BLOCK) return RegionFlag.SNOW_MELT;
        if (type == Material.ICE) return RegionFlag.ICE_MELT;
        if (type == Material.FROSTED_ICE) return RegionFlag.FROSTED_ICE_MELT;
        if (type == Material.FARMLAND) return RegionFlag.SOIL_DRY;
        if (Tag.CORALS.isTagged(type) || Tag.CORAL_BLOCKS.isTagged(type) || Tag.WALL_CORALS.isTagged(type)) return RegionFlag.CORAL_FADE;
        return null;
    }

    private static @Nullable RegionFlag flowFlag(final Block source) {
        final Material type = source.getType();
        if (type == Material.LAVA) return RegionFlag.LAVA_FLOW;
        if (type == Material.WATER || source.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) return RegionFlag.WATER_FLOW;
        return null;
    }

    private static @Nullable Player attackingPlayer(final @Nullable Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
