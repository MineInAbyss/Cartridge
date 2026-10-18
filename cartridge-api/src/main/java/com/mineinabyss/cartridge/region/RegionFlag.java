package com.mineinabyss.cartridge.region;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A flag left unset in a region falls back to its parent flag in that same region, then to the parent region, before lower priority regions are checked.
 * When overlapping regions of the same priority disagree, deny wins.
 * Flags unset everywhere are allowed
 */
@NullMarked
public enum RegionFlag {
    INTERACT(null, true),
    USE(INTERACT, true),
    USE_OPENABLES(USE, true),
    USE_REDSTONE(USE, true),
    TRIGGER_PLATES(USE, true),
    USE_ANVIL(INTERACT, true),
    USE_DRIPLEAF(INTERACT, true),
    CHEST_ACCESS(INTERACT, true),
    SLEEP(INTERACT, true),
    RESPAWN_ANCHORS(INTERACT, true),
    RIDE(INTERACT, true),
    ITEM_FRAME_ROTATION(INTERACT, true),

    BUILD(null, true),
    BLOCK_PLACE(BUILD, true),
    BLOCK_BREAK(BUILD, true),
    VEHICLE_PLACE(BUILD, true),
    VEHICLE_DESTROY(BUILD, true),
    LIGHTER(BUILD, true),
    BLOCK_TRAMPLING(BUILD, true),

    PVP(null, true),
    DAMAGE_ANIMALS(null, true),
    POTION_SPLASH(null, true),
    WIND_CHARGE_BURST(null, true),
    ITEM_PICKUP(null, true),
    ITEM_DROP(null, true),
    ENDERPEARL(null, true),
    CHORUS_FRUIT_TELEPORT(null, true),
    ENTRY(null, true),
    EXIT(null, true),
    EXIT_VIA_TELEPORT(null, true),
    SEND_CHAT(null, true),
    RECEIVE_CHAT(null, false),

    PLAYER_DAMAGE(null, false),
    MOB_DAMAGE(PLAYER_DAMAGE, false),
    FALL_DAMAGE(PLAYER_DAMAGE, false),
    FIREWORK_DAMAGE(null, false),
    NATURAL_HEALTH_REGEN(null, false),
    NATURAL_HUNGER_DRAIN(null, false),
    EXP_DROPS(null, false),

    TNT(null, false),
    CREEPER_EXPLOSION(null, false),
    ENDERDRAGON_BLOCK_DAMAGE(null, false),
    GHAST_FIREBALL(null, false),
    BREEZE_CHARGE_EXPLOSION(null, false),
    WITHER_DAMAGE(null, false),
    OTHER_EXPLOSION(null, false),
    ENDERMAN_GRIEF(null, false),
    RAVAGER_GRIEF(null, false),
    SNOWMAN_TRAILS(null, false),
    ENTITY_PAINTING_DESTROY(null, false),
    ENTITY_ITEM_FRAME_DESTROY(null, false),
    MOB_SPAWNING(null, false),
    PISTONS(null, false),
    FIRE_SPREAD(null, false),
    LAVA_FIRE(null, false),
    LIGHTNING(null, false),
    SNOW_FALL(null, false),
    SNOW_MELT(null, false),
    ICE_FORM(null, false),
    ICE_MELT(null, false),
    FROSTED_ICE_FORM(null, false),
    FROSTED_ICE_MELT(null, false),
    MUSHROOM_GROWTH(null, false),
    LEAF_DECAY(null, false),
    GRASS_GROWTH(null, false),
    MYCELIUM_SPREAD(null, false),
    VINE_GROWTH(null, false),
    ROCK_GROWTH(null, false),
    SCULK_GROWTH(null, false),
    CROP_GROWTH(null, false),
    SOIL_DRY(null, false),
    MOISTURE_CHANGE(null, false),
    CORAL_FADE(null, false),
    COPPER_FADE(null, false),
    WATER_FLOW(null, false),
    LAVA_FLOW(null, false),
    LAVA_HARDEN(null, false);

    private static final Map<String, RegionFlag> BY_KEY = new HashMap<>();

    static {
        for (final RegionFlag flag : values()) {
            BY_KEY.put(flag.key, flag);
        }
    }

    private final @Nullable RegionFlag parent;
    private final boolean bypassable;
    private final String key;

    RegionFlag(final @Nullable RegionFlag parent, final boolean bypassable) {
        this.parent = parent;
        this.bypassable = bypassable;
        this.key = camelCase(this.name());
    }

    private static String camelCase(final String name) {
        final String[] parts = name.toLowerCase(Locale.ROOT).split("_");
        final StringBuilder builder = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            builder.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i], 1, parts[i].length());
        }
        return builder.toString();
    }

    public static @Nullable RegionFlag byKey(final String key) {
        return BY_KEY.get(key);
    }

    public String key() {
        return this.key;
    }

    public @Nullable RegionFlag parent() {
        return this.parent;
    }

    /**
     * Flags that are not bypassable describe the environment or a player's own state rather than an action, so permissions never override them
     */
    public boolean bypassable() {
        return this.bypassable;
    }

    public String bypassPermission() {
        return "cartridge.region.bypass." + this.key;
    }
}
