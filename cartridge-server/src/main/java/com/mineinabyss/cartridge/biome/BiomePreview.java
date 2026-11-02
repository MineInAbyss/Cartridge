package com.mineinabyss.cartridge.biome;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.level.biome.Biome;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Previewed biomes only replace what clients receive during registry sync.
 * The server keeps the startup biome, so anything it simulates itself (snow, mob spawns) does not change until the config is edited and the server restarted
 */
@NullMarked
public final class BiomePreview {

    public static final Map<String, List<String>> PROPERTIES;

    static {
        final Map<String, List<String>> properties = new LinkedHashMap<>();
        for (final String shortcut : List.of(
            "waterColor", "waterFogColor", "skyColor", "fogColor", "grassColor", "foliageColor", "dryFoliageColor", "grassColorModifier", "hasPrecipitation"
        )) {
            properties.put(shortcut, CartridgeBiomes.SHORTCUTS.get(shortcut));
        }
        properties.put("temperature", List.of("temperature"));
        properties.put("downfall", List.of("downfall"));
        properties.put("temperatureModifier", List.of("temperature_modifier"));
        PROPERTIES = Collections.unmodifiableMap(properties);
    }

    private static final Map<Identifier, Map<String, Change>> CHANGES = new ConcurrentHashMap<>();
    private static final Map<Identifier, Biome> PREVIEWS = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING_RESYNC = ConcurrentHashMap.newKeySet();

    private BiomePreview() {
    }

    public record Change(String property, List<String> path, JsonElement value) {
    }

    public record Entry(String property, List<String> path, @Nullable JsonElement value, boolean previewed) {
    }

    public static @Nullable Biome preview(final Identifier id) {
        return PREVIEWS.get(id);
    }

    public static Map<String, Change> changes(final Identifier id) {
        return CHANGES.getOrDefault(id, Map.of());
    }

    public static List<String> attributeIds() {
        return BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.entrySet().stream()
            .filter(entry -> entry.getValue().isSyncable() && entry.getValue().isPositional())
            .map(entry -> entry.getKey().identifier().toString())
            .sorted()
            .toList();
    }

    public static Change property(final String property, final JsonElement value) {
        final List<String> path = PROPERTIES.get(property);
        if (path == null) throw new IllegalArgumentException("Unknown property " + property + ", use one of " + PROPERTIES.keySet());
        return new Change(property, path, value);
    }

    public static Change attribute(final Identifier id, final JsonElement value) {
        final EnvironmentAttribute<?> attribute = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getValue(id);
        if (attribute == null) throw new IllegalArgumentException("Unknown attribute " + id);
        if (!attribute.isPositional()) throw new IllegalArgumentException(id + " cannot be set on a biome");
        if (!attribute.isSyncable()) throw new IllegalArgumentException(id + " is server side only, change it in the config and restart instead");
        final String key = id.toString();
        final String property = CartridgeBiomes.SHORTCUTS.entrySet().stream()
            .filter(entry -> entry.getValue().equals(List.of(CartridgeBiomes.ATTRIBUTES, key)))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(key);
        return new Change(property, List.of(CartridgeBiomes.ATTRIBUTES, key), value);
    }

    /**
     * Plain text that is not valid JSON becomes a string, so colors can be typed as #rrggbb without quotes.
     * The literal null removes the property from the biome
     */
    public static JsonElement parseValue(final String raw) {
        try {
            return StrictJsonParser.parse(raw);
        } catch (final JsonParseException e) {
            return new JsonPrimitive(raw);
        }
    }

    /**
     * @throws IllegalArgumentException when the biome does not exist or the changed biome fails to decode, the preview stays as it was
     */
    public static void set(final Identifier id, final Change change) {
        final Map<String, Change> changes = new LinkedHashMap<>(changes(id));
        changes.put(change.property(), change);
        PREVIEWS.put(id, build(id, changes));
        CHANGES.put(id, changes);
    }

    /**
     * @param property null resets every change to the biome
     * @return false when there was nothing to reset
     */
    public static boolean reset(final Identifier id, final @Nullable String property) {
        final Map<String, Change> current = CHANGES.get(id);
        if (current == null) return false;
        if (property == null || (current.size() == 1 && current.containsKey(property))) {
            CHANGES.remove(id);
            PREVIEWS.remove(id);
            return true;
        }
        if (!current.containsKey(property)) return false;
        final Map<String, Change> changes = new LinkedHashMap<>(current);
        changes.remove(property);
        PREVIEWS.put(id, build(id, changes));
        CHANGES.put(id, changes);
        return true;
    }

    public static Set<Identifier> previewedBiomes() {
        return Set.copyOf(CHANGES.keySet());
    }

    /**
     * Every named property first, unset ones with a null value, then the remaining attributes sorted by id
     */
    public static List<Entry> entries(final Identifier id) {
        final Map<List<String>, Change> changedPaths = changes(id).values().stream().collect(Collectors.toMap(Change::path, change -> change));
        final JsonObject json = encode(id, PREVIEWS.getOrDefault(id, base(id)));
        final List<Entry> entries = new ArrayList<>();
        PROPERTIES.forEach((name, path) -> entries.add(new Entry(name, path, get(json, path), changedPaths.containsKey(path))));

        if (json.get(CartridgeBiomes.ATTRIBUTES) instanceof final JsonObject attributes) {
            attributes.keySet().stream().sorted().forEach(key -> {
                final List<String> path = List.of(CartridgeBiomes.ATTRIBUTES, key);
                if (PROPERTIES.containsValue(path)) return;
                entries.add(new Entry(key, path, attributes.get(key), changedPaths.containsKey(path)));
            });
        }
        changedPaths.values().stream()
            .filter(change -> change.value().isJsonNull() && !PROPERTIES.containsKey(change.property()))
            .forEach(change -> entries.add(new Entry(change.property(), change.path(), null, true)));
        return entries;
    }

    private static @Nullable JsonElement get(final JsonObject json, final List<String> path) {
        JsonElement current = json;
        for (final String part : path) {
            if (!(current instanceof final JsonObject object)) return null;
            current = object.get(part);
        }
        return current;
    }

    public static String yaml(final Identifier id) {
        final StringBuilder builder = new StringBuilder(id.toString()).append(":\n");
        final List<Change> attributes = new ArrayList<>();
        for (final Change change : changes(id).values()) {
            if (change.property().equals(change.path().getLast()) && change.path().getFirst().equals(CartridgeBiomes.ATTRIBUTES)) {
                attributes.add(change);
            } else {
                builder.append("  ").append(change.property()).append(": ").append(yamlValue(change.value())).append('\n');
            }
        }
        if (!attributes.isEmpty()) {
            builder.append("  ").append(CartridgeBiomes.ATTRIBUTES).append(":\n");
            attributes.forEach(change -> builder.append("    ").append(change.property()).append(": ").append(yamlValue(change.value())).append('\n'));
        }
        return builder.toString();
    }

    private static String yamlValue(final JsonElement value) {
        return value.isJsonNull() ? "~" : value.toString();
    }

    public static void resync(final Player player) {
        PENDING_RESYNC.add(player.getUniqueId());
        player.getConnection().reenterConfiguration();
    }

    public static boolean consumeResync(final UUID player) {
        return PENDING_RESYNC.remove(player);
    }

    private static Biome build(final Identifier id, final Map<String, Change> changes) {
        final JsonObject json = encode(id, base(id));
        for (final Change change : changes.values()) {
            JsonObject target = json;
            final List<String> path = change.path();
            for (final String part : path.subList(0, path.size() - 1)) {
                if (!(target.get(part) instanceof final JsonObject child)) {
                    final JsonObject created = new JsonObject();
                    target.add(part, created);
                    target = created;
                } else {
                    target = child;
                }
            }
            if (change.value() instanceof JsonNull) target.remove(path.getLast());
            else target.add(path.getLast(), change.value());
        }
        return Biome.NETWORK_CODEC.parse(ops(), json).getOrThrow(error -> new IllegalArgumentException("Invalid value: " + error));
    }

    private static Biome base(final Identifier id) {
        final Biome biome = registryAccess().lookupOrThrow(Registries.BIOME).getValue(id);
        if (biome == null) throw new IllegalArgumentException("Unknown biome " + id);
        return biome;
    }

    private static JsonObject encode(final Identifier id, final Biome biome) {
        return Biome.NETWORK_CODEC.encodeStart(ops(), biome)
            .getOrThrow(error -> new IllegalStateException("Could not encode biome " + id + ": " + error))
            .getAsJsonObject();
    }

    private static RegistryOps<JsonElement> ops() {
        return registryAccess().createSerializationContext(JsonOps.INSTANCE);
    }

    private static RegistryAccess registryAccess() {
        return MinecraftServer.getServer().registryAccess();
    }
}
