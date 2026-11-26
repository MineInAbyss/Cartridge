package com.mineinabyss.cartridge.region;

import java.util.Map;
import java.util.Set;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An immutable snapshot, replaced by a new object on every region reload.
 * Keep the name and look it up again instead of holding on to the object
 */
@NullMarked
public interface Region {

    /**
     * Unique within the world, the world-wide region is called global
     */
    String name();

    String worldName();

    /**
     * Integer.MIN_VALUE for the global region
     */
    int priority();

    /**
     * Null for the world-wide global region and for templates, which only exist to be inherited from.
     * Otherwise the box spans whole blocks, so the max corner is one past the last block
     */
    @Nullable BoundingBox bounds();

    /**
     * Flags, settings and denySpawn left unset in this region are inherited from the parent
     */
    @Nullable Region parent();

    /**
     * The region named by inherit, checked before the parent for flags, settings and denySpawn left unset here.
     * Only those are copied, priority, bounds and membership stay with this region
     */
    @Nullable Region inherit();

    /**
     * Only the flags set on this region itself, true for allow, false for deny, absent when unset
     */
    Map<RegionFlag, Boolean> flags();

    /**
     * Includes the inherited or parent list when unset here.
     * Null when unset on every inherited and parent region, so lower priority regions decide
     */
    @Nullable Set<EntityType> denySpawn();

    /**
     * Only the settings set on this region itself, values as written in the region file
     */
    Map<String, Object> settings();

    /**
     * Players holding this permission, or the bypass permission of a parent, are members of this region.
     * Members ignore the region's own entries for the flags in {@link #bypassFlags}
     */
    @Nullable String bypassPermission();

    /**
     * Empty means the list of the nearest parent that has one, or every bypassable flag when none has
     */
    Set<RegionFlag> bypassFlags();

    default <T> @Nullable T ownSetting(final RegionSetting<T> setting) {
        return setting.convert(this.settings().get(setting.key()));
    }

    default <T> @Nullable T setting(final RegionSetting<T> setting) {
        final T own = this.ownSetting(setting);
        if (own != null) return own;
        final T inherited = this.inherit() == null ? null : this.inherit().setting(setting);
        if (inherited != null) return inherited;
        return this.parent() == null ? null : this.parent().setting(setting);
    }
}
