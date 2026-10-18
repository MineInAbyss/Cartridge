package com.mineinabyss.cartridge.region;

import java.util.Map;
import java.util.Set;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface Region {

    String name();

    String worldName();

    int priority();

    /**
     * Null for the world-wide global region and for templates, which only exist to be inherited from
     */
    @Nullable BoundingBox bounds();

    /**
     * Flags and denySpawn left unset in this region are inherited from the parent
     */
    @Nullable Region parent();

    /**
     * Only the flags set on this region itself, true for allow, false for deny, absent when unset
     */
    Map<RegionFlag, Boolean> flags();

    /**
     * Includes the parent's list when unset here.
     * Null when unset on the whole parent chain, so lower priority regions decide
     */
    @Nullable Set<EntityType> denySpawn();
}
