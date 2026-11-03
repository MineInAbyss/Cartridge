package com.mineinabyss.cartridge.region;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Applies timeLock, greeting, farewell, greetingTitle and farewellTitle as players move between regions, like the WorldGuard flags of the same names
 */
@NullMarked
public final class RegionPlayerSettings {

    private static final State EMPTY = new State(null, null, null, null, null);
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private RegionPlayerSettings() {
    }

    private record State(
        @Nullable Long timeLock, @Nullable String greeting, @Nullable String farewell, @Nullable String greetingTitle, @Nullable String farewellTitle
    ) {
    }

    static void moved(final CartridgeRegions regions, final Player player, final Location to) {
        update(regions, player, to, true);
    }

    /**
     * Applies the time lock without showing greetings, as WorldGuard does when a session starts
     */
    public static void refresh(final CartridgeRegions regions, final Player player, final Location location) {
        update(regions, player, location, false);
    }

    static void quit(final Player player) {
        STATES.remove(player.getUniqueId());
    }

    private static void update(final CartridgeRegions regions, final Player player, final Location location, final boolean announce) {
        final State previous = STATES.getOrDefault(player.getUniqueId(), EMPTY);
        final CartridgeRegions.WorldRegions world = regions.world(location.getWorld());
        if (previous == EMPTY && (world == null || !world.usesPlayerSettings())) return;

        final State current = world == null || !world.usesPlayerSettings() ? EMPTY : new State(
            regions.setting(location, RegionSetting.TIME_LOCK),
            regions.setting(location, RegionSetting.GREETING),
            regions.setting(location, RegionSetting.FAREWELL),
            regions.setting(location, RegionSetting.GREETING_TITLE),
            regions.setting(location, RegionSetting.FAREWELL_TITLE)
        );
        if (current.equals(previous)) return;
        if (current == EMPTY) {
            STATES.remove(player.getUniqueId());
        } else {
            STATES.put(player.getUniqueId(), current);
        }

        if (!Objects.equals(previous.timeLock(), current.timeLock())) {
            if (current.timeLock() != null) {
                player.setPlayerTime(current.timeLock(), false);
            } else {
                player.resetPlayerTime();
            }
        }
        if (!announce) return;

        if (previous.farewell() != null && !previous.farewell().equals(current.farewell())) player.sendMessage(text(previous.farewell()));
        if (previous.farewellTitle() != null && !previous.farewellTitle().equals(current.farewellTitle())) player.showTitle(title(previous.farewellTitle()));
        if (current.greeting() != null && !current.greeting().equals(previous.greeting())) player.sendMessage(text(current.greeting()));
        if (current.greetingTitle() != null && !current.greetingTitle().equals(previous.greetingTitle())) player.showTitle(title(current.greetingTitle()));
    }

    private static Component text(final String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('§', '&'));
    }

    private static Title title(final String value) {
        final String[] parts = value.replace("\\n", "\n").split("\n", 2);
        return Title.title(text(parts[0]), parts.length > 1 ? text(parts[1]) : Component.empty());
    }
}
