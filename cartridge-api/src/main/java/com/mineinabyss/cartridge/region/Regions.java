package com.mineinabyss.cartridge.region;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.util.Services;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface Regions {

    static Regions regions() {
        final class Holder {
            static final Regions INSTANCE = Services.service(Regions.class).orElseThrow();
        }
        return Holder.INSTANCE;
    }

    /**
     * Highest priority first, the global region of the world last
     */
    List<Region> regionsAt(Location location);

    /**
     * True for allow, false for deny, null when unset in every region at the location
     */
    @Nullable Boolean query(Location location, RegionFlag flag);

    /**
     * For bypassable flags this is also true when the player holds the bypass permission of the flag or one of its parents
     */
    boolean isAllowed(Player player, Location location, RegionFlag flag);

    Set<EntityType> denySpawn(Location location);
}
