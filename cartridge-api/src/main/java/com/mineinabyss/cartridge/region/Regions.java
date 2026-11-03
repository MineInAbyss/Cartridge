package com.mineinabyss.cartridge.region;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.util.Services;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Cartridge's region lookups, read from config/cartridge/regions/&lt;world&gt;.yml.
 * Every method may be called from any thread, results reflect the regions loaded at the time of the call.
 * Locations are reduced to the block they are in
 */
@NullMarked
public interface Regions {

    static Regions regions() {
        final class Holder {
            static final Regions INSTANCE = Services.service(Regions.class).orElseThrow();
        }
        return Holder.INSTANCE;
    }

    /**
     * Regions whose bounds contain the location, highest priority first, then the global region of the world.
     * Templates are not included, see {@link #regionsAt(Location, boolean)}
     */
    List<Region> regionsAt(Location location);

    /**
     * With includeParents, also every parent of a containing region (templates included), each region once.
     * Sorted by priority, highest first, a region comes before its parents at equal priority and the global region is last
     */
    List<Region> regionsAt(Location location, boolean includeParents);

    /**
     * Any region of the world by name, including templates and global
     */
    @Nullable Region region(String worldName, String name);

    /**
     * Every region of the world: regions with bounds highest priority first, then templates, then global if the file sets it
     */
    List<Region> regions(String worldName);

    /**
     * True for allow, false for deny, null when unset in every region at the location
     */
    @Nullable Boolean query(Location location, RegionFlag flag);

    /**
     * For bypassable flags this is also true when the player holds the bypass permission of the flag or one of its parents
     */
    boolean isAllowed(Player player, Location location, RegionFlag flag);

    Set<EntityType> denySpawn(Location location);

    /**
     * The value from the highest priority region containing the location that sets it on itself or a parent, otherwise from the global region.
     * At equal priority the region listed first in the file wins
     */
    <T> @Nullable T setting(Location location, RegionSetting<T> setting);
}
