package com.mineinabyss.cartridge.region;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@NullMarked
public final class CartridgeRegions implements Regions {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String README_NAME = "README.txt";
    public static final String GLOBAL_REGION = "global";
    public static final String DENY_SPAWN = "denySpawn";
    public static final String SETTINGS = "settings";
    public static final String BYPASS_PERMISSION = "bypassPermission";
    public static final String BYPASS_FLAGS = "bypassFlags";
    private static final List<RegionSetting<?>> BUILT_IN_SETTINGS = List.of(
        RegionSetting.TIME_LOCK, RegionSetting.WEATHER_LOCK, RegionSetting.GREETING, RegionSetting.FAREWELL,
        RegionSetting.GREETING_TITLE, RegionSetting.FAREWELL_TITLE, RegionSetting.SPAWN_OVERRIDE
    );

    private volatile Map<String, WorldRegions> worlds = Map.of();
    private @Nullable Path directory;

    public static CartridgeRegions instance() {
        return (CartridgeRegions) Regions.regions();
    }

    public static void init(final Path configDir) {
        registerPermissions(Bukkit.getPluginManager());
        final CartridgeRegions regions = instance();
        regions.directory = configDir.resolve("cartridge").resolve("regions");
        try {
            regions.reload();
        } catch (final IOException | InvalidConfigurationException | IllegalArgumentException e) {
            LOGGER.error("Could not load regions from {}, no regions are active", regions.directory, e);
        }
    }

    private static void registerPermissions(final PluginManager pluginManager) {
        final Map<String, Boolean> children = new HashMap<>();
        for (final RegionFlag flag : RegionFlag.values()) {
            if (!flag.bypassable()) continue;
            children.put(flag.bypassPermission(), true);
            if (pluginManager.getPermission(flag.bypassPermission()) == null) {
                pluginManager.addPermission(new Permission(flag.bypassPermission(), PermissionDefault.OP));
            }
        }
        if (pluginManager.getPermission("cartridge.region.bypass.*") == null) {
            pluginManager.addPermission(new Permission("cartridge.region.bypass.*", PermissionDefault.OP, children));
        }
    }

    private static String readme() {
        final String flags = Arrays.stream(RegionFlag.values())
            .map(flag -> flag.parent() == null ? flag.key() : flag.key() + " (" + flag.parent().key() + ")")
            .collect(Collectors.joining(", "));
        final String bypassable = Arrays.stream(RegionFlag.values())
            .filter(RegionFlag::bypassable)
            .map(RegionFlag::key)
            .collect(Collectors.joining(", "));
        return """
            Each <world>.yml in this folder holds the regions of the world with that exact name, as returned by World#getName.
            Flags are allow or deny. An unset flag falls back to its parent (in brackets) and then to lower priority regions.
            When overlapping regions of the same priority disagree, deny wins.
            Flags: %s
            A region without from and to is a template, it never applies by itself but its children inherit its flags.
            Children inherit every flag of their parent region and override only what they set, their priority defaults to one above the parent.
            denySpawn takes a list of entity types and blocks them from spawning, like mobSpawning it ignores plugin and command spawns.
            A settings section next to flags holds values read by Cartridge or plugins, inherited by children like flags. On global it goes inside the global section.
            Cartridge reads timeLock (player time in ticks while inside), weatherLock (clear or rain while inside), greeting and farewell (chat on entry and exit), greetingTitle and farewellTitle (title on entry and exit, \\n starts the subtitle).
            playerCollision: deny stops players inside from pushing or being pushed, for clients too, by putting them in the cartridge_nocollide team. A player already on another team keeps it and keeps colliding.
            Texts use & or section sign colour codes. spawnOverride: true is read by Geary spawning and drops spawns of lower priority regions.
            Players with cartridge.region.bypass.<flag> ignore a denied flag, ops have every bypass permission by default.
            A region can name a bypassPermission, players holding it are members of the region and its children and ignore the region's own bypassable flags, or only those listed in bypassFlags.
            Inside a region they are members of, those flags are allowed for them even when lower priority regions or global deny them, like WorldGuard membership.
            A child without its own bypassFlags uses the list of its nearest parent that has one.
            Bypassable flags: %s

            Example world.yml:
            global:
              pvp: deny
            regions:
              spawn:
                priority: 10
                from: "-100, -64, -100"
                to: "100, 320, 100"
                flags:
                  useOpenables: deny
                  blockBreak: deny
                  denySpawn: [zombie, skeleton]
                children:
                  market:
                    from: "10, 60, 10"
                    to: "30, 80, 30"
                    flags:
                      chestAccess: allow
            """.formatted(flags, bypassable);
    }

    /**
     * Keeps the previously loaded regions when any world file fails to parse
     */
    public List<String> reload() throws IOException, InvalidConfigurationException {
        if (this.directory == null) throw new IllegalStateException("Regions are not initialized");
        if (Files.notExists(this.directory)) {
            Files.createDirectories(this.directory);
            Files.writeString(this.directory.resolve(README_NAME), readme());
        }

        final List<Path> files;
        try (Stream<Path> stream = Files.list(this.directory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }

        final List<String> warnings = new ArrayList<>();
        final Map<String, WorldRegions> worlds = new HashMap<>();
        for (final Path path : files) {
            final String fileName = path.getFileName().toString();
            final String worldName = fileName.substring(0, fileName.length() - ".yml".length());
            final YamlConfiguration config = new YamlConfiguration();
            try {
                config.loadFromString(Files.readString(path));
            } catch (final InvalidConfigurationException e) {
                throw new InvalidConfigurationException(fileName + ": " + e.getMessage(), e);
            }
            worlds.put(worldName, parseWorld(worldName, config, warnings));
        }

        this.worlds = Map.copyOf(worlds);
        warnings.forEach(LOGGER::warn);
        return warnings;
    }

    private static WorldRegions parseWorld(final String worldName, final ConfigurationSection section, final List<String> warnings) {
        final ConfigurationSection globalSection = section.getConfigurationSection(GLOBAL_REGION);
        final RegionImpl global = globalSection == null ? null
            : parseRegion(GLOBAL_REGION, worldName, Integer.MIN_VALUE, null, null, null, globalSection, globalSection.getConfigurationSection(SETTINGS), worldName + "." + GLOBAL_REGION, warnings);

        final List<RegionImpl> regions = new ArrayList<>();
        final List<RegionImpl> templates = new ArrayList<>();
        parseRegions(worldName, section.getConfigurationSection("regions"), worldName + ".regions", null, regions, templates, warnings);

        regions.sort(Comparator.comparingInt(RegionImpl::priority).reversed());
        return new WorldRegions(List.copyOf(regions), List.copyOf(templates), global);
    }

    private static void parseRegions(
        final String worldName, final @Nullable ConfigurationSection section, final String path,
        final @Nullable RegionImpl parent, final List<RegionImpl> regions, final List<RegionImpl> templates, final List<String> warnings
    ) {
        if (section == null) return;
        for (final String name : section.getKeys(false)) {
            final String regionPath = path + "." + name;
            final ConfigurationSection regionSection = section.getConfigurationSection(name);
            if (regionSection == null) continue;
            if (name.equals(GLOBAL_REGION) || Stream.concat(regions.stream(), templates.stream()).anyMatch(region -> region.name().equals(name))) {
                warnings.add(regionPath + ": region name '" + name + "' is reserved or already used in this world, skipped");
                continue;
            }

            final boolean template = !regionSection.contains("from") && !regionSection.contains("to");
            final int[] from;
            final int[] to;
            if (template) {
                from = null;
                to = null;
            } else {
                from = parseCorner(regionSection.getString("from"));
                to = parseCorner(regionSection.getString("to"));
                if (from == null || to == null) {
                    warnings.add(regionPath + ": from and to must both be \"x, y, z\", or both be left out for a template region, skipped");
                    continue;
                }
            }

            final int priority = regionSection.getInt("priority", parent == null ? 0 : parent.priority() + 1);
            final String bypassPermission = regionSection.getString(BYPASS_PERMISSION);
            final Set<RegionFlag> bypassFlags = EnumSet.noneOf(RegionFlag.class);
            for (final String key : regionSection.getStringList(BYPASS_FLAGS)) {
                final RegionFlag flag = RegionFlag.byKey(key);
                if (flag == null || !flag.bypassable()) {
                    warnings.add(regionPath + "." + BYPASS_FLAGS + ": '" + key + "' is not a bypassable flag");
                } else {
                    bypassFlags.add(flag);
                }
            }
            final RegionImpl region = parseRegion(
                name, worldName, priority, from, to, parent,
                regionSection.getConfigurationSection("flags"), regionSection.getConfigurationSection(SETTINGS), regionPath, warnings
            ).withBypass(bypassPermission == null || bypassPermission.isBlank() ? null : bypassPermission.trim(), Collections.unmodifiableSet(bypassFlags));
            (template ? templates : regions).add(region);
            parseRegions(worldName, regionSection.getConfigurationSection("children"), regionPath + ".children", region, regions, templates, warnings);
        }
    }

    private static int @Nullable [] parseCorner(final @Nullable String value) {
        if (value == null) return null;
        final String[] parts = value.trim().split("[,\\s]+");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static RegionImpl parseRegion(
        final String name, final String worldName, final int priority, final int @Nullable [] from, final int @Nullable [] to,
        final @Nullable RegionImpl parent, final @Nullable ConfigurationSection section, final @Nullable ConfigurationSection settingsSection,
        final String regionPath, final List<String> warnings
    ) {
        final Map<RegionFlag, Boolean> flags = new EnumMap<>(RegionFlag.class);
        Set<EntityType> denySpawn = null;
        final String path = name.equals(GLOBAL_REGION) ? regionPath : regionPath + ".flags";
        if (section != null) {
            for (final String key : section.getKeys(false)) {
                if (key.equals(SETTINGS) && section.isConfigurationSection(key)) continue;
                if (key.equals(DENY_SPAWN)) {
                    denySpawn = parseEntityTypes(path + "." + key, section.getStringList(key), warnings);
                    continue;
                }

                final RegionFlag flag = RegionFlag.byKey(key);
                if (flag == null) {
                    warnings.add(path + ": unknown flag '" + key + "'");
                    continue;
                }

                final String value = String.valueOf(section.get(key));
                switch (value) {
                    case "allow", "true" -> flags.put(flag, true);
                    case "deny", "false" -> flags.put(flag, false);
                    default -> warnings.add(path + "." + key + ": expected allow or deny but got '" + value + "'");
                }
            }
        }
        return new RegionImpl(name, worldName, priority, from, to, parent, Collections.unmodifiableMap(flags), denySpawn, parseSettings(settingsSection, regionPath + "." + SETTINGS, warnings), null, Set.of());
    }

    private static Map<String, Object> parseSettings(final @Nullable ConfigurationSection section, final String path, final List<String> warnings) {
        if (section == null) return Map.of();
        final Map<String, Object> settings = new HashMap<>();
        for (final String key : section.getKeys(false)) {
            final Object value = section.get(key);
            if (value == null) continue;
            settings.put(key, value instanceof ConfigurationSection nested ? nested.getValues(true) : value);
        }
        for (final RegionSetting<?> setting : BUILT_IN_SETTINGS) {
            final Object value = settings.get(setting.key());
            if (value != null && setting.convert(value) == null) {
                warnings.add(path + "." + setting.key() + ": could not read '" + value + "', ignored");
                settings.remove(setting.key());
            }
        }
        return Map.copyOf(settings);
    }

    private static Set<EntityType> parseEntityTypes(final String path, final List<String> names, final List<String> warnings) {
        final Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        for (final String name : names) {
            final NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            final EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (type == null) {
                warnings.add(path + ": unknown entity type '" + name + "'");
            } else {
                types.add(type);
            }
        }
        return Collections.unmodifiableSet(types);
    }


    public boolean isEmpty() {
        return this.worlds.isEmpty();
    }

    public Map<String, WorldRegions> worlds() {
        return this.worlds;
    }

    public @Nullable WorldRegions world(final World world) {
        return this.worlds.get(world.getName());
    }

    @Override
    public List<Region> regionsAt(final Location location) {
        final WorldRegions world = this.world(location.getWorld());
        if (world == null) return List.of();

        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        final List<Region> result = new ArrayList<>();
        for (final RegionImpl region : world.candidates(x, z)) {
            if (region.contains(x, y, z)) result.add(region);
        }
        if (world.global() != null) result.add(world.global());
        return result;
    }

    @Override
    public List<Region> regionsAt(final Location location, final boolean includeParents) {
        if (!includeParents) return this.regionsAt(location);
        final WorldRegions world = this.world(location.getWorld());
        if (world == null) return List.of();

        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        final List<Region> result = new ArrayList<>();
        for (final RegionImpl region : world.candidates(x, z)) {
            if (!region.contains(x, y, z)) continue;
            for (RegionImpl current = region; current != null; current = current.parent()) {
                if (!result.contains(current)) result.add(current);
            }
        }
        result.sort(Comparator.comparingInt(Region::priority).reversed());
        if (world.global() != null) result.add(world.global());
        return result;
    }

    @Override
    public @Nullable Region region(final String worldName, final String name) {
        final WorldRegions world = this.worlds.get(worldName);
        return world == null ? null : world.byName(name);
    }

    @Override
    public List<Region> regions(final String worldName) {
        final WorldRegions world = this.worlds.get(worldName);
        return world == null ? List.of() : world.all();
    }

    @Override
    public <T> @Nullable T setting(final Location location, final RegionSetting<T> setting) {
        final WorldRegions world = this.world(location.getWorld());
        if (world == null || !world.usesSetting(setting.key())) return null;

        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        for (final RegionImpl region : world.candidates(x, z)) {
            if (!region.contains(x, y, z)) continue;
            final T value = region.setting(setting);
            if (value != null) return value;
        }
        return world.global() == null ? null : world.global().ownSetting(setting);
    }

    @Override
    public @Nullable Boolean query(final Location location, final RegionFlag flag) {
        return toBoolean(state(this.world(location.getWorld()), location.getBlockX(), location.getBlockY(), location.getBlockZ(), flag));
    }

    public boolean isDenied(final Location location, final RegionFlag flag) {
        return state(this.world(location.getWorld()), location.getBlockX(), location.getBlockY(), location.getBlockZ(), flag) == RegionImpl.DENY;
    }

    public boolean isDenied(final Block block, final RegionFlag flag) {
        return state(this.world(block.getWorld()), block.getX(), block.getY(), block.getZ(), flag) == RegionImpl.DENY;
    }

    @Override
    public boolean isAllowed(final Player player, final Location location, final RegionFlag flag) {
        return isAllowed(player, this.world(location.getWorld()), location.getBlockX(), location.getBlockY(), location.getBlockZ(), flag);
    }

    public boolean isAllowed(final Player player, final Block block, final RegionFlag flag) {
        return isAllowed(player, this.world(block.getWorld()), block.getX(), block.getY(), block.getZ(), flag);
    }

    private static boolean isAllowed(final Player player, final @Nullable WorldRegions world, final int x, final int y, final int z, final RegionFlag flag) {
        if (state(world, x, y, z, flag) != RegionImpl.DENY) return true;
        if (!flag.bypassable()) return false;
        if (hasBypass(player, flag)) return true;
        return world.hasMembers() && memberState(world, x, y, z, flag, player) != RegionImpl.DENY;
    }

    /**
     * Same walk as state(), with the entries the player ignores as a member of a region left out.
     * Like WorldGuard membership, a member region that leaves the flag to its members allows it at its priority, so lower regions and global no longer apply
     */
    private static byte memberState(final WorldRegions world, final int x, final int y, final int z, final RegionFlag flag, final Player player) {
        byte result = RegionImpl.UNSET;
        int resultPriority = 0;
        for (final RegionImpl region : world.candidates(x, z)) {
            if (result != RegionImpl.UNSET && region.priority() < resultPriority) break;
            if (!region.contains(x, y, z)) continue;
            final byte value = region.isMember(player) ? region.resolveForMember(flag) : region.resolved[flag.ordinal()];
            if (value == RegionImpl.UNSET) continue;
            if (value == RegionImpl.DENY) return RegionImpl.DENY;
            result = RegionImpl.ALLOW;
            resultPriority = region.priority();
        }
        if (result != RegionImpl.UNSET) return result;
        return world.global() == null ? RegionImpl.UNSET : world.global().resolved[flag.ordinal()];
    }

    private static boolean hasBypass(final Player player, final RegionFlag flag) {
        if (!flag.bypassable()) return false;
        for (RegionFlag current = flag; current != null; current = current.parent()) {
            if (player.hasPermission(current.bypassPermission())) return true;
        }
        return false;
    }

    @Override
    public Set<EntityType> denySpawn(final Location location) {
        final WorldRegions world = this.world(location.getWorld());
        if (world == null || !world.usesDenySpawn()) return Set.of();

        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        for (final RegionImpl region : world.candidates(x, z)) {
            if (region.denySpawn() != null && region.contains(x, y, z)) return region.denySpawn();
        }
        return world.global() == null || world.global().denySpawn() == null ? Set.of() : world.global().denySpawn();
    }

    private static @Nullable Boolean toBoolean(final byte state) {
        return switch (state) {
            case RegionImpl.ALLOW -> Boolean.TRUE;
            case RegionImpl.DENY -> Boolean.FALSE;
            default -> null;
        };
    }

    private static byte state(final @Nullable WorldRegions world, final int x, final int y, final int z, final RegionFlag flag) {
        if (world == null || !world.mayDefine(flag)) return RegionImpl.UNSET;

        final int ordinal = flag.ordinal();
        byte result = RegionImpl.UNSET;
        int resultPriority = 0;
        for (final RegionImpl region : world.candidates(x, z)) {
            if (result != RegionImpl.UNSET && region.priority() < resultPriority) break;
            if (!region.contains(x, y, z)) continue;
            final byte value = region.resolved[ordinal];
            if (value == RegionImpl.UNSET) continue;
            if (value == RegionImpl.DENY) return RegionImpl.DENY;
            result = RegionImpl.ALLOW;
            resultPriority = region.priority();
        }
        if (result != RegionImpl.UNSET) return result;
        return world.global() == null ? RegionImpl.UNSET : world.global().resolved[ordinal];
    }

    /**
     * Regions are bucketed into square cells of 2^CELL_SHIFT blocks on x and z.
     * Every cell lists the regions overlapping it in the same order as the full priority sorted list, so a lookup walks the same regions minus the ones that cannot contain the point
     */
    public static final class WorldRegions {

        private static final int CELL_SHIFT = 8;
        // Regions spanning more cells than this are not bucketed and instead appear in every cell, this keeps a world wide region from creating millions of cells
        private static final long MAX_CELLS_PER_REGION = 4096;
        private static final RegionImpl[] NO_REGIONS = new RegionImpl[0];

        private final List<RegionImpl> regions;
        private final List<RegionImpl> templates;
        private final @Nullable RegionImpl global;
        private final boolean[] mayDefine = new boolean[RegionFlag.values().length];
        private final boolean usesDenySpawn;
        private final boolean hasMembers;
        private final Set<String> settingKeys;
        private final boolean usesPlayerSettings;
        private final List<Region> all;
        private final Map<String, RegionImpl> byName;
        private final Long2ObjectMap<RegionImpl[]> cells;
        private final RegionImpl[] uncelled;

        WorldRegions(final List<RegionImpl> regions, final List<RegionImpl> templates, final @Nullable RegionImpl global) {
            this.regions = regions;
            this.templates = templates;
            this.global = global;

            final Set<RegionFlag> usedFlags = EnumSet.noneOf(RegionFlag.class);
            boolean usesDenySpawn = false;
            for (final RegionImpl region : regions) {
                usedFlags.addAll(region.flags().keySet());
                usesDenySpawn |= region.denySpawn() != null;
            }
            for (final RegionImpl region : templates) {
                usedFlags.addAll(region.flags().keySet());
                usesDenySpawn |= region.denySpawn() != null;
            }
            if (global != null) {
                usedFlags.addAll(global.flags().keySet());
                usesDenySpawn |= global.denySpawn() != null;
            }
            this.usesDenySpawn = usesDenySpawn;
            this.hasMembers = regions.stream().anyMatch(region -> !region.memberPermissions.isEmpty());

            final List<Region> all = new ArrayList<>(regions);
            all.addAll(templates);
            if (global != null) all.add(global);
            this.all = List.copyOf(all);
            final Map<String, RegionImpl> byName = new HashMap<>();
            final Set<String> settingKeys = new HashSet<>();
            for (final Region region : all) {
                byName.put(region.name(), (RegionImpl) region);
                settingKeys.addAll(region.settings().keySet());
            }
            this.byName = Map.copyOf(byName);
            this.settingKeys = Set.copyOf(settingKeys);
            this.usesPlayerSettings = usedFlags.contains(RegionFlag.PLAYER_COLLISION)
                || Stream.of(RegionSetting.TIME_LOCK, RegionSetting.WEATHER_LOCK, RegionSetting.GREETING, RegionSetting.FAREWELL, RegionSetting.GREETING_TITLE, RegionSetting.FAREWELL_TITLE)
                .anyMatch(setting -> settingKeys.contains(setting.key()));

            for (final RegionFlag flag : RegionFlag.values()) {
                for (RegionFlag current = flag; current != null; current = current.parent()) {
                    if (usedFlags.contains(current)) {
                        this.mayDefine[flag.ordinal()] = true;
                        break;
                    }
                }
            }

            final Long2ObjectOpenHashMap<List<RegionImpl>> lists = new Long2ObjectOpenHashMap<>();
            for (final RegionImpl region : regions) {
                if (!isCelled(region)) continue;
                forEachCell(region, key -> lists.computeIfAbsent(key, ignored -> new ArrayList<>()));
            }
            final List<RegionImpl> uncelled = new ArrayList<>();
            for (final RegionImpl region : regions) {
                if (isCelled(region)) {
                    forEachCell(region, key -> lists.get(key).add(region));
                } else {
                    uncelled.add(region);
                    for (final List<RegionImpl> list : lists.values()) list.add(region);
                }
            }
            final Long2ObjectOpenHashMap<RegionImpl[]> cells = new Long2ObjectOpenHashMap<>(lists.size());
            for (final Long2ObjectMap.Entry<List<RegionImpl>> entry : lists.long2ObjectEntrySet()) {
                cells.put(entry.getLongKey(), entry.getValue().toArray(NO_REGIONS));
            }
            cells.trim();
            this.cells = cells;
            this.uncelled = uncelled.toArray(NO_REGIONS);
        }

        private static boolean isCelled(final RegionImpl region) {
            final long width = (region.maxX >> CELL_SHIFT) - (region.minX >> CELL_SHIFT) + 1L;
            final long depth = (region.maxZ >> CELL_SHIFT) - (region.minZ >> CELL_SHIFT) + 1L;
            return width * depth <= MAX_CELLS_PER_REGION;
        }

        private static void forEachCell(final RegionImpl region, final LongConsumer action) {
            for (int cellX = region.minX >> CELL_SHIFT; cellX <= region.maxX >> CELL_SHIFT; cellX++) {
                for (int cellZ = region.minZ >> CELL_SHIFT; cellZ <= region.maxZ >> CELL_SHIFT; cellZ++) {
                    action.accept(cellKey(cellX, cellZ));
                }
            }
        }

        private static long cellKey(final int cellX, final int cellZ) {
            return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        }

        /**
         * Highest priority first, a superset of the regions containing any block in the column
         */
        RegionImpl[] candidates(final int x, final int z) {
            final RegionImpl[] cell = this.cells.get(cellKey(x >> CELL_SHIFT, z >> CELL_SHIFT));
            return cell == null ? this.uncelled : cell;
        }

        boolean mayDefine(final RegionFlag flag) {
            return this.mayDefine[flag.ordinal()];
        }

        public boolean usesDenySpawn() {
            return this.usesDenySpawn;
        }

        /**
         * Regions with bounds, highest priority first
         */
        public List<RegionImpl> regions() {
            return this.regions;
        }

        public List<RegionImpl> templates() {
            return this.templates;
        }

        public @Nullable RegionImpl global() {
            return this.global;
        }

        public List<Region> all() {
            return this.all;
        }

        public @Nullable RegionImpl byName(final String name) {
            return this.byName.get(name);
        }

        boolean hasMembers() {
            return this.hasMembers;
        }

        boolean usesSetting(final String key) {
            return this.settingKeys.contains(key);
        }

        boolean usesPlayerSettings() {
            return this.usesPlayerSettings;
        }
    }

    public static final class RegionImpl implements Region {

        static final byte UNSET = 0;
        static final byte ALLOW = 1;
        static final byte DENY = 2;

        private final String name;
        private final String worldName;
        private final int priority;
        private final boolean hasBounds;
        // Inclusive block coordinates, only meaningful when hasBounds is true
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        private final @Nullable RegionImpl parent;
        private final Map<RegionFlag, Boolean> flags;
        private final @Nullable Set<EntityType> denySpawn;
        private final Map<String, Object> settings;
        private final @Nullable String bypassPermission;
        private final Set<RegionFlag> bypassFlags;
        // Own bypassFlags, or the nearest parent's when this region lists none
        private final Set<RegionFlag> effectiveBypassFlags;
        // Own bypass permission first, then those of the parents
        final List<String> memberPermissions;
        // Indexed by RegionFlag ordinal, the value resolve() would give through parent flags and parent regions
        final byte[] resolved;

        RegionImpl(
            final String name, final String worldName, final int priority, final int @Nullable [] from, final int @Nullable [] to,
            final @Nullable RegionImpl parent, final Map<RegionFlag, Boolean> flags, final @Nullable Set<EntityType> denySpawn,
            final Map<String, Object> settings, final @Nullable String bypassPermission, final Set<RegionFlag> bypassFlags
        ) {
            this.name = name;
            this.worldName = worldName;
            this.priority = priority;
            this.hasBounds = from != null && to != null;
            this.minX = this.hasBounds ? Math.min(from[0], to[0]) : 0;
            this.minY = this.hasBounds ? Math.min(from[1], to[1]) : 0;
            this.minZ = this.hasBounds ? Math.min(from[2], to[2]) : 0;
            this.maxX = this.hasBounds ? Math.max(from[0], to[0]) : 0;
            this.maxY = this.hasBounds ? Math.max(from[1], to[1]) : 0;
            this.maxZ = this.hasBounds ? Math.max(from[2], to[2]) : 0;
            this.parent = parent;
            this.flags = flags;
            this.denySpawn = denySpawn;
            this.settings = settings;
            this.bypassPermission = bypassPermission;
            this.bypassFlags = bypassFlags;
            this.effectiveBypassFlags = bypassFlags.isEmpty() && parent != null ? parent.effectiveBypassFlags : bypassFlags;
            final List<String> memberPermissions = new ArrayList<>();
            if (bypassPermission != null) memberPermissions.add(bypassPermission);
            for (RegionImpl current = parent; current != null; current = current.parent) {
                if (current.bypassPermission != null) memberPermissions.add(current.bypassPermission);
            }
            this.memberPermissions = List.copyOf(memberPermissions);

            final RegionFlag[] values = RegionFlag.values();
            this.resolved = new byte[values.length];
            for (final RegionFlag flag : values) {
                this.resolved[flag.ordinal()] = this.computeResolved(flag);
            }
        }

        private byte computeResolved(final RegionFlag flag) {
            for (RegionFlag current = flag; current != null; current = current.parent()) {
                final Boolean value = this.flags.get(current);
                if (value != null) return value ? ALLOW : DENY;
            }
            return this.parent == null ? UNSET : this.parent.resolved[flag.ordinal()];
        }

        boolean contains(final int x, final int y, final int z) {
            return !this.hasBounds || x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public String worldName() {
            return this.worldName;
        }

        @Override
        public int priority() {
            return this.priority;
        }

        @Override
        public @Nullable BoundingBox bounds() {
            return this.hasBounds ? new BoundingBox(this.minX, this.minY, this.minZ, this.maxX + 1, this.maxY + 1, this.maxZ + 1) : null;
        }

        @Override
        public @Nullable RegionImpl parent() {
            return this.parent;
        }

        @Override
        public Map<RegionFlag, Boolean> flags() {
            return this.flags;
        }

        @Override
        public @Nullable Set<EntityType> denySpawn() {
            return this.denySpawn != null || this.parent == null ? this.denySpawn : this.parent.denySpawn();
        }

        RegionImpl withBypass(final @Nullable String bypassPermission, final Set<RegionFlag> bypassFlags) {
            if (bypassPermission == null && bypassFlags.isEmpty()) return this;
            return new RegionImpl(
                this.name, this.worldName, this.priority, this.hasBounds ? new int[]{this.minX, this.minY, this.minZ} : null,
                this.hasBounds ? new int[]{this.maxX, this.maxY, this.maxZ} : null, this.parent, this.flags, this.denySpawn, this.settings, bypassPermission, bypassFlags
            );
        }

        boolean isMember(final Player player) {
            for (final String permission : this.memberPermissions) {
                if (player.hasPermission(permission)) return true;
            }
            return false;
        }

        byte resolveAsMember(final RegionFlag flag) {
            for (RegionImpl current = this; current != null; current = current.parent) {
                for (RegionFlag key = flag; key != null; key = key.parent()) {
                    final Boolean value = current.flags.get(key);
                    if (value != null && !current.membersIgnore(key)) return value ? ALLOW : DENY;
                }
            }
            return UNSET;
        }

        byte resolveForMember(final RegionFlag flag) {
            final byte value = resolveAsMember(flag);
            if (value != UNSET) return value;
            for (RegionFlag key = flag; key != null; key = key.parent()) {
                if (membersIgnore(key)) return ALLOW;
            }
            return UNSET;
        }

        private boolean membersIgnore(final RegionFlag flag) {
            return this.effectiveBypassFlags.isEmpty() ? flag.bypassable() : this.effectiveBypassFlags.contains(flag);
        }

        @Override
        public Map<String, Object> settings() {
            return this.settings;
        }

        @Override
        public @Nullable String bypassPermission() {
            return this.bypassPermission;
        }

        @Override
        public Set<RegionFlag> bypassFlags() {
            return this.bypassFlags;
        }

        @Override
        public String toString() {
            return "Region[" + this.worldName + "/" + this.name + "]";
        }
    }
}
