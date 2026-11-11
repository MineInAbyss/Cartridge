package com.mineinabyss.cartridge.region;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
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
import org.bukkit.entity.Allay;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
    // Same world plugin teleports such as DeeperWorld section transitions must never be blocked by entry or exit, other causes only check entry when changing worlds
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

    private static final List<Case<?>> CASES = List.of(
        on(AsyncChatEvent.class, (regions, e) -> chat(regions, e)),
        on(PlayerInteractEvent.class, (regions, e) -> interact(regions, e)),
        on(PlayerInteractEntityEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getRightClicked().getLocation(), entityInteractFlag(e.getPlayer(), e.getRightClicked()))),
        when(EntityMountEvent.class, (regions, e) -> {
            if (!(e.getEntity() instanceof Player player)) return false;
            check(regions, e, player, e.getMount().getLocation(), RegionFlag.RIDE);
            return true;
        }),
        on(PlayerBedEnterEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBed(), RegionFlag.SLEEP)),
        on(PlayerTeleportEvent.class, (regions, e) -> teleport(regions, e)),
        on(PlayerMoveEvent.class, (regions, e) -> move(regions, e)),
        on(PlayerJoinEvent.class, (regions, e) -> RegionPlayerSettings.refresh(regions, e.getPlayer(), e.getPlayer().getLocation())),
        on(PlayerRespawnEvent.class, (regions, e) -> RegionPlayerSettings.moved(regions, e.getPlayer(), e.getRespawnLocation())),
        on(PlayerQuitEvent.class, (regions, e) -> RegionPlayerSettings.quit(e.getPlayer())),
        on(PlayerAttemptPickupItemEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getItem().getLocation(), RegionFlag.ITEM_PICKUP)),
        on(PlayerDropItemEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getPlayer().getLocation(), RegionFlag.ITEM_DROP)),

        on(BlockPlaceEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_PLACE)),
        on(BlockBreakEvent.class, (regions, e) -> {
            check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_BREAK);
            if (regions.isDenied(e.getBlock(), RegionFlag.EXP_DROPS)) e.setExpToDrop(0);
        }),
        on(BlockExpEvent.class, (regions, e) -> {
            if (regions.isDenied(e.getBlock(), RegionFlag.EXP_DROPS)) e.setExpToDrop(0);
        }),
        on(PlayerBucketEmptyEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_PLACE)),
        on(PlayerBucketFillEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_BREAK)),
        on(SignChangeEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_PLACE)),
        on(BlockFertilizeEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getBlock(), RegionFlag.BLOCK_PLACE)),
        on(BlockIgniteEvent.class, (regions, e) -> ignite(regions, e)),
        on(BlockBurnEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), RegionFlag.FIRE_SPREAD)),
        on(EntityChangeBlockEvent.class, (regions, e) -> changeBlock(regions, e)),
        on(HangingPlaceEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getEntity().getLocation(), RegionFlag.BLOCK_PLACE)),
        on(EntityPlaceEvent.class, (regions, e) -> check(regions, e, e.getPlayer(), e.getEntity().getLocation(), e.getEntity() instanceof Vehicle ? RegionFlag.VEHICLE_PLACE : RegionFlag.BLOCK_PLACE)),
        on(HangingBreakByEntityEvent.class, (regions, e) -> {
            final Player player = attackingPlayer(e.getRemover());
            if (player != null) {
                check(regions, e, player, e.getEntity().getLocation(), RegionFlag.BLOCK_BREAK);
            } else if (e.getRemover() != null) {
                deny(regions, e, e.getEntity().getLocation(), hangingDestroyFlag(e.getEntity()));
            }
        }),
        on(VehicleDamageEvent.class, (regions, e) -> check(regions, e, attackingPlayer(e.getAttacker()), e.getVehicle().getLocation(), RegionFlag.VEHICLE_DESTROY)),
        on(VehicleDestroyEvent.class, (regions, e) -> check(regions, e, attackingPlayer(e.getAttacker()), e.getVehicle().getLocation(), RegionFlag.VEHICLE_DESTROY)),

        on(EntityDamageEvent.class, (regions, e) -> damage(regions, e)),
        when(EntityRegainHealthEvent.class, (regions, e) -> {
            if (!(e.getEntity() instanceof Player player)
                || e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED && e.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) return false;
            deny(regions, e, player.getLocation(), RegionFlag.NATURAL_HEALTH_REGEN);
            return true;
        }),
        when(FoodLevelChangeEvent.class, (regions, e) -> {
            if (e.getItem() != null || e.getFoodLevel() >= e.getEntity().getFoodLevel()) return false;
            deny(regions, e, e.getEntity().getLocation(), RegionFlag.NATURAL_HUNGER_DRAIN);
            return true;
        }),
        when(EntityDeathEvent.class, (regions, e) -> {
            if (e.getEntity() instanceof Player) return false;
            if (regions.isDenied(e.getEntity().getLocation(), RegionFlag.EXP_DROPS)) e.setDroppedExp(0);
            return true;
        }),
        on(PotionSplashEvent.class, (regions, e) -> check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.POTION_SPLASH)),
        on(LingeringPotionSplashEvent.class, (regions, e) -> check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.POTION_SPLASH)),
        when(ProjectileHitEvent.class, (regions, e) -> {
            if (e.getEntity() instanceof BreezeWindCharge) {
                deny(regions, e, e.getEntity().getLocation(), RegionFlag.BREEZE_CHARGE_EXPLOSION);
            } else if (e.getEntity() instanceof WindCharge) {
                check(regions, e, attackingPlayer(e.getEntity()), e.getEntity().getLocation(), RegionFlag.WIND_CHARGE_BURST);
            } else {
                return false;
            }
            return true;
        }),
        when(CreatureSpawnEvent.class, (regions, e) -> {
            if (!(e.getEntity() instanceof Mob) || PLUGIN_SPAWNS.contains(e.getSpawnReason())) return false;
            final Location location = e.getLocation();
            if (regions.isDenied(location, RegionFlag.MOB_SPAWNING) || regions.denySpawn(location).contains(e.getEntityType())) e.setCancelled(true);
            return true;
        }),

        on(EntityExplodeEvent.class, (regions, e) -> filterExplosion(regions, e.blockList(), explosionFlag(e.getEntity()))),
        on(BlockExplodeEvent.class, (regions, e) -> filterExplosion(regions, e.blockList(), RegionFlag.OTHER_EXPLOSION)),
        when(LightningStrikeEvent.class, (regions, e) -> {
            if (e.getCause() == LightningStrikeEvent.Cause.COMMAND || e.getCause() == LightningStrikeEvent.Cause.CUSTOM) return false;
            deny(regions, e, e.getLightning().getLocation(), RegionFlag.LIGHTNING);
            return true;
        }),
        on(BlockPistonExtendEvent.class, (regions, e) -> pistons(regions, e, e.getBlock(), e.getBlocks(), e.getDirection())),
        on(BlockPistonRetractEvent.class, (regions, e) -> pistons(regions, e, e.getBlock(), e.getBlocks(), e.getDirection())),
        on(EntityBlockFormEvent.class, (regions, e) -> {
            if (e.getEntity() instanceof Snowman) {
                deny(regions, e, e.getBlock(), RegionFlag.SNOWMAN_TRAILS);
            } else if (e.getNewState().getType() == Material.FROSTED_ICE) {
                deny(regions, e, e.getBlock(), RegionFlag.FROSTED_ICE_FORM);
            }
        }),
        on(BlockSpreadEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), spreadFlag(e.getSource().getType(), e.getNewState().getType()))),
        on(BlockFormEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), formFlag(e.getBlock().getType(), e.getNewState().getType()))),
        on(BlockGrowEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), growFlag(e.getNewState().getType()))),
        on(BlockFadeEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), fadeFlag(e.getBlock().getType()))),
        on(LeavesDecayEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), RegionFlag.LEAF_DECAY)),
        on(MoistureChangeEvent.class, (regions, e) -> deny(regions, e, e.getBlock(), RegionFlag.MOISTURE_CHANGE)),
        on(BlockFromToEvent.class, (regions, e) -> deny(regions, e, e.getToBlock(), flowFlag(e.getBlock())))
    );

    private static final Case<?>[] NO_CASES = new Case<?>[0];
    // Same order as CASES, so the first case whose guard passes wins as it would in a pattern switch
    private static final ClassValue<Case<?>[]> CASES_BY_TYPE = new ClassValue<>() {
        @Override
        protected Case<?>[] computeValue(final Class<?> type) {
            final Case<?>[] cases = CASES.stream().filter(entry -> entry.type().isAssignableFrom(type)).toArray(Case<?>[]::new);
            return cases.length == 0 ? NO_CASES : cases;
        }
    };

    private RegionProtection() {
    }

    public static void handle(final Event event) {
        final CartridgeRegions regions = CartridgeRegions.instance();
        if (regions.isEmpty()) return;

        for (final Case<?> entry : CASES_BY_TYPE.get(event.getClass())) {
            if (entry.handle(regions, event)) return;
        }
    }

    private static <E extends Event> Case<E> on(final Class<E> type, final BiConsumer<CartridgeRegions, E> handler) {
        return new Case<>(type, (regions, event) -> {
            handler.accept(regions, event);
            return true;
        });
    }

    /**
     * The handler returns false when the event does not match its guard, so later cases are tried
     */
    private static <E extends Event> Case<E> when(final Class<E> type, final BiPredicate<CartridgeRegions, E> handler) {
        return new Case<>(type, handler);
    }

    private record Case<E extends Event>(Class<E> type, BiPredicate<CartridgeRegions, E> handler) {

        boolean handle(final CartridgeRegions regions, final Event event) {
            return this.handler.test(regions, this.type.cast(event));
        }
    }

    private static void check(final CartridgeRegions regions, final Cancellable event, final @Nullable Player player, final Location location, final @Nullable RegionFlag flag) {
        if (player != null && flag != null && !regions.isAllowed(player, location, flag)) {
            event.setCancelled(true);
        }
    }

    private static void check(final CartridgeRegions regions, final Cancellable event, final @Nullable Player player, final Block block, final @Nullable RegionFlag flag) {
        if (player != null && flag != null && !regions.isAllowed(player, block, flag)) {
            event.setCancelled(true);
        }
    }

    private static void deny(final CartridgeRegions regions, final Cancellable event, final Location location, final @Nullable RegionFlag flag) {
        if (flag != null && regions.isDenied(location, flag)) {
            event.setCancelled(true);
        }
    }

    private static void deny(final CartridgeRegions regions, final Cancellable event, final Block block, final @Nullable RegionFlag flag) {
        if (flag != null && regions.isDenied(block, flag)) {
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
        if (!event.isCancelled()) RegionPlayerSettings.moved(regions, player, to);
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
        final boolean playerTeleport = PLAYER_TELEPORTS.contains(event.getCause());
        if (playerTeleport || !from.getWorld().equals(to.getWorld())) {
            if (!regions.isAllowed(player, to, RegionFlag.ENTRY) && regions.isAllowed(player, from, RegionFlag.ENTRY)) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("You can't enter this area", NamedTextColor.RED));
            } else if (playerTeleport && !regions.isAllowed(player, from, RegionFlag.EXIT_VIA_TELEPORT) && regions.isAllowed(player, to, RegionFlag.EXIT_VIA_TELEPORT)) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("You can't teleport out of this area", NamedTextColor.RED));
            }
        }
        if (!event.isCancelled()) RegionPlayerSettings.moved(regions, player, to);
    }

    private static void interact(final CartridgeRegions regions, final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null) return;

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                final RegionFlag flag = blockInteractFlag(event.getPlayer(), block);
                if (flag != null && !regions.isAllowed(event.getPlayer(), block, flag)) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
            }
            case PHYSICAL -> check(regions, event, event.getPlayer(), block, physicalFlag(block.getType()));
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
        if (entity instanceof ItemFrame frame) return frame.getItem().isEmpty() ? RegionFlag.ENTITY_EQUIPMENT : RegionFlag.ITEM_FRAME_ROTATION;
        if (entity instanceof ArmorStand || entity instanceof Allay) return RegionFlag.ENTITY_EQUIPMENT;
        if (entity instanceof Vehicle) return RegionFlag.RIDE;
        return RegionFlag.INTERACT;
    }

    private static void ignite(final CartridgeRegions regions, final BlockIgniteEvent event) {
        final Block location = event.getBlock();
        switch (event.getCause()) {
            case SPREAD -> deny(regions, event, location, RegionFlag.FIRE_SPREAD);
            case LAVA -> deny(regions, event, location, RegionFlag.LAVA_FIRE);
            case LIGHTNING -> deny(regions, event, location, RegionFlag.LIGHTNING);
            default -> check(regions, event, event.getPlayer(), location, RegionFlag.LIGHTER);
        }
    }

    private static void changeBlock(final CartridgeRegions regions, final EntityChangeBlockEvent event) {
        final Block location = event.getBlock();
        final Material type = location.getType();
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
                    final boolean dropsItem = hanging instanceof ItemFrame frame && !frame.getItem().isEmpty();
                    check(regions, event, source, location, dropsItem ? RegionFlag.ENTITY_EQUIPMENT : RegionFlag.BLOCK_BREAK);
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
        blocks.removeIf(block -> regions.isDenied(block, flag));
    }

    private static void pistons(final CartridgeRegions regions, final Cancellable event, final Block piston, final List<Block> blocks, final BlockFace direction) {
        if (regions.isDenied(piston, RegionFlag.PISTONS)) {
            event.setCancelled(true);
            return;
        }
        final boolean pistonMayBreak = !regions.isDenied(piston, RegionFlag.BLOCK_BREAK);
        final boolean pistonMayPlace = !regions.isDenied(piston, RegionFlag.BLOCK_PLACE);
        for (final Block block : blocks) {
            final Block destination = block.getRelative(direction);
            if (regions.isDenied(block, RegionFlag.PISTONS) || regions.isDenied(destination, RegionFlag.PISTONS)
                || pistonMayBreak && regions.isDenied(block, RegionFlag.BLOCK_BREAK)
                || pistonMayPlace && regions.isDenied(destination, RegionFlag.BLOCK_PLACE)) {
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
