package com.mineinabyss.cartridge.region;

import com.mojang.logging.LogUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
            Players with cartridge.region.bypass.<flag> ignore a denied flag, ops have every bypass permission by default.
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
            : parseRegion(GLOBAL_REGION, worldName, Integer.MIN_VALUE, null, null, globalSection, worldName + "." + GLOBAL_REGION, warnings);

        final List<RegionImpl> regions = new ArrayList<>();
        final List<RegionImpl> templates = new ArrayList<>();
        parseRegions(worldName, section.getConfigurationSection("regions"), worldName + ".regions", null, regions, templates, warnings);

        regions.sort(Comparator.comparingInt(RegionImpl::priority).reversed());
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
        return new WorldRegions(List.copyOf(regions), List.copyOf(templates), global, usedFlags, usesDenySpawn);
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
            final BoundingBox bounds;
            if (template) {
                bounds = null;
            } else {
                final int[] from = parseCorner(regionSection.getString("from"));
                final int[] to = parseCorner(regionSection.getString("to"));
                if (from == null || to == null) {
                    warnings.add(regionPath + ": from and to must both be \"x, y, z\", or both be left out for a template region, skipped");
                    continue;
                }
                bounds = new BoundingBox(
                    Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]),
                    Math.max(from[0], to[0]) + 1, Math.max(from[1], to[1]) + 1, Math.max(from[2], to[2]) + 1
                );
            }

            final int priority = regionSection.getInt("priority", parent == null ? 0 : parent.priority() + 1);
            final RegionImpl region = parseRegion(name, worldName, priority, bounds, parent, regionSection.getConfigurationSection("flags"), regionPath + ".flags", warnings);
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
        final String name, final String worldName, final int priority, final @Nullable BoundingBox bounds,
        final @Nullable RegionImpl parent, final @Nullable ConfigurationSection section, final String path, final List<String> warnings
    ) {
        final Map<RegionFlag, Boolean> flags = new EnumMap<>(RegionFlag.class);
        Set<EntityType> denySpawn = null;
        if (section != null) {
            for (final String key : section.getKeys(false)) {
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
        return new RegionImpl(name, worldName, priority, bounds, parent, Collections.unmodifiableMap(flags), denySpawn);
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

    @Override
    public List<Region> regionsAt(final Location location) {
        final WorldRegions world = this.worlds.get(location.getWorld().getName());
        if (world == null) return List.of();

        final List<Region> result = new ArrayList<>();
        for (final RegionImpl region : world.regions()) {
            if (region.contains(location)) result.add(region);
        }
        if (world.global() != null) result.add(world.global());
        return result;
    }

    @Override
    public @Nullable Boolean query(final Location location, final RegionFlag flag) {
        final WorldRegions world = this.worlds.get(location.getWorld().getName());
        if (world == null || !world.mayDefine(flag)) return null;

        Boolean result = null;
        int resultPriority = 0;
        for (final RegionImpl region : world.regions()) {
            if (result != null && region.priority() < resultPriority) break;
            if (!region.contains(location)) continue;
            final Boolean value = region.resolve(flag);
            if (value == null) continue;
            if (!value) return false;
            result = true;
            resultPriority = region.priority();
        }
        if (result != null) return result;
        return world.global() == null ? null : world.global().resolve(flag);
    }

    public boolean isDenied(final Location location, final RegionFlag flag) {
        return Boolean.FALSE.equals(this.query(location, flag));
    }

    @Override
    public boolean isAllowed(final Player player, final Location location, final RegionFlag flag) {
        if (!this.isDenied(location, flag)) return true;
        if (!flag.bypassable()) return false;

        for (RegionFlag current = flag; current != null; current = current.parent()) {
            if (player.hasPermission(current.bypassPermission())) return true;
        }
        return false;
    }

    @Override
    public Set<EntityType> denySpawn(final Location location) {
        final WorldRegions world = this.worlds.get(location.getWorld().getName());
        if (world == null || !world.usesDenySpawn()) return Set.of();

        for (final RegionImpl region : world.regions()) {
            if (region.contains(location) && region.denySpawn() != null) return region.denySpawn();
        }
        return world.global() == null || world.global().denySpawn() == null ? Set.of() : world.global().denySpawn();
    }

    public record WorldRegions(List<RegionImpl> regions, List<RegionImpl> templates, @Nullable RegionImpl global, Set<RegionFlag> usedFlags, boolean usesDenySpawn) {

        boolean mayDefine(final RegionFlag flag) {
            for (RegionFlag current = flag; current != null; current = current.parent()) {
                if (this.usedFlags.contains(current)) return true;
            }
            return false;
        }
    }

    public record RegionImpl(
        String name,
        String worldName,
        int priority,
        @Nullable BoundingBox bounds,
        @Nullable RegionImpl parent,
        Map<RegionFlag, Boolean> flags,
        @Nullable Set<EntityType> denySpawn
    ) implements Region {

        boolean contains(final Location location) {
            return this.bounds == null || this.bounds.contains(location.getBlockX() + 0.5, location.getBlockY() + 0.5, location.getBlockZ() + 0.5);
        }

        @Nullable Boolean resolve(final RegionFlag flag) {
            for (RegionFlag current = flag; current != null; current = current.parent()) {
                final Boolean value = this.flags.get(current);
                if (value != null) return value;
            }
            return this.parent == null ? null : this.parent.resolve(flag);
        }

        @Override
        public @Nullable Set<EntityType> denySpawn() {
            return this.denySpawn != null || this.parent == null ? this.denySpawn : this.parent.denySpawn();
        }

        @Override
        public @Nullable BoundingBox bounds() {
            return this.bounds == null ? null : this.bounds.clone();
        }
    }
}
