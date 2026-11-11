package com.mineinabyss.cartridge.region;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.WeatherType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A typed key into the settings map of a region file.
 * Plugins can define their own with the factory methods, any key is accepted in a region file and kept as written
 */
@NullMarked
public final class RegionSetting<T> {

    /**
     * Player time in ticks, fixed while the player is inside the region
     */
    public static final RegionSetting<Long> TIME_LOCK = number("timeLock");
    /**
     * Player weather while inside the region, clear or rain.
     * Thunder is read as rain, Minecraft has no per player thunder
     */
    public static final RegionSetting<WeatherType> WEATHER_LOCK = new RegionSetting<>("weatherLock", value -> switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
        case "clear", "sun" -> WeatherType.CLEAR;
        case "rain", "downfall", "thunder" -> WeatherType.DOWNFALL;
        default -> null;
    });
    /**
     * Chat message shown on entering the region, legacy colour codes with &amp; or the section sign
     */
    public static final RegionSetting<String> GREETING = string("greeting");
    public static final RegionSetting<String> FAREWELL = string("farewell");
    /**
     * Title shown on entering the region, the text after the first \n is the subtitle
     */
    public static final RegionSetting<String> GREETING_TITLE = string("greetingTitle");
    public static final RegionSetting<String> FAREWELL_TITLE = string("farewellTitle");
    /**
     * Read with {@link Region#ownSetting} only.
     * When true, spawn entries of lower priority regions at the same location are ignored
     */
    public static final RegionSetting<Boolean> SPAWN_OVERRIDE = bool("spawnOverride");

    private final String key;
    private final Function<Object, @Nullable T> converter;

    private RegionSetting(final String key, final Function<Object, @Nullable T> converter) {
        this.key = key;
        this.converter = converter;
    }

    public static RegionSetting<String> string(final String key) {
        return new RegionSetting<>(key, value -> value instanceof List<?> ? null : String.valueOf(value));
    }

    /**
     * Accepts true, false, allow and deny
     */
    public static RegionSetting<Boolean> bool(final String key) {
        return new RegionSetting<>(key, value -> switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "true", "allow" -> Boolean.TRUE;
            case "false", "deny" -> Boolean.FALSE;
            default -> null;
        });
    }

    public static RegionSetting<Long> number(final String key) {
        return new RegionSetting<>(key, value -> {
            if (value instanceof Number number) return number.longValue();
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        });
    }

    public static RegionSetting<Double> decimal(final String key) {
        return new RegionSetting<>(key, value -> {
            if (value instanceof Number number) return number.doubleValue();
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        });
    }

    /**
     * A single value is read as a list of one
     */
    public static RegionSetting<List<String>> stringList(final String key) {
        return new RegionSetting<>(key, value -> value instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of(String.valueOf(value)));
    }

    public String key() {
        return this.key;
    }

    /**
     * Null when the raw value is null or cannot be read as this type
     */
    public @Nullable T convert(final @Nullable Object raw) {
        return raw == null ? null : this.converter.apply(raw);
    }

    @Override
    public String toString() {
        return "RegionSetting[" + this.key + "]";
    }
}
