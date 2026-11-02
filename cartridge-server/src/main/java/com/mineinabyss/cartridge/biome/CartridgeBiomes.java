package com.mineinabyss.cartridge.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Biomes are a worldgen registry, which only loads once at startup, so changes need a restart
 */
@NullMarked
public final class CartridgeBiomes {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String README_NAME = "README.txt";
    private static final String PARENT = "parent";
    private static final String ATTRIBUTES = "attributes";
    private static final String EFFECTS = "effects";

    private static final Map<String, List<String>> SHORTCUTS = Map.of(
        "waterColor", List.of(EFFECTS, "water_color"),
        "grassColor", List.of(EFFECTS, "grass_color"),
        "foliageColor", List.of(EFFECTS, "foliage_color"),
        "dryFoliageColor", List.of(EFFECTS, "dry_foliage_color"),
        "grassColorModifier", List.of(EFFECTS, "grass_color_modifier"),
        "skyColor", List.of(ATTRIBUTES, "minecraft:visual/sky_color"),
        "fogColor", List.of(ATTRIBUTES, "minecraft:visual/fog_color"),
        "waterFogColor", List.of(ATTRIBUTES, "minecraft:visual/water_fog_color"),
        "hasPrecipitation", List.of("has_precipitation")
    );

    private static @Nullable Path directory;

    private CartridgeBiomes() {
    }

    public static void init(final Path configDir) {
        directory = configDir.resolve("cartridge").resolve("biomes");
        try {
            if (Files.notExists(directory)) {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(README_NAME), readme());
            }
        } catch (final IOException e) {
            LOGGER.error("Could not create {}", directory, e);
        }
    }

    /**
     * Throws on any invalid file or definition so registry loading fails and startup stops.
     * Skipping a broken biome instead would make chunks that use it lose their biome on the next save
     *
     * @param existing biome JSON from the loaded data packs
     */
    public static Map<Identifier, JsonElement> resolve(final Function<Identifier, @Nullable JsonElement> existing) throws IOException {
        final Map<Identifier, Definition> definitions = readDefinitions();
        final Map<Identifier, JsonObject> resolved = new LinkedHashMap<>();
        for (final Identifier id : definitions.keySet()) {
            resolve(id, definitions, existing, resolved, new HashSet<>());
        }
        if (!resolved.isEmpty()) {
            LOGGER.info("Loaded {} Cartridge biome(s): {}", resolved.size(), resolved.keySet());
        }
        return Map.copyOf(resolved);
    }

    private static JsonObject resolve(
        final Identifier id,
        final Map<Identifier, Definition> definitions,
        final Function<Identifier, @Nullable JsonElement> existing,
        final Map<Identifier, JsonObject> resolved,
        final Set<Identifier> visiting
    ) {
        final JsonObject done = resolved.get(id);
        if (done != null) return done;

        final Definition definition = definitions.get(id);
        if (definition == null) {
            final JsonElement json = existing.apply(id);
            if (json == null || !json.isJsonObject()) throw new IllegalArgumentException("Unknown parent biome " + id);
            return normalizeAttributes(json.getAsJsonObject().deepCopy());
        }
        if (!visiting.add(id)) throw new IllegalArgumentException(definition.describe() + " has a parent loop through " + visiting);

        final JsonObject base;
        if (definition.parent != null) {
            if (definition.parent.equals(id)) {
                final JsonElement json = existing.apply(id);
                if (json == null || !json.isJsonObject()) throw new IllegalArgumentException(definition.describe() + " uses itself as parent but no data pack defines it");
                base = normalizeAttributes(json.getAsJsonObject().deepCopy());
            } else {
                if (!definitions.containsKey(definition.parent) && existing.apply(definition.parent) == null) {
                    throw new IllegalArgumentException(definition.describe() + " has unknown parent " + definition.parent);
                }
                base = resolve(definition.parent, definitions, existing, resolved, visiting).deepCopy();
            }
        } else {
            final JsonElement json = existing.apply(id);
            base = json != null && json.isJsonObject() ? normalizeAttributes(json.getAsJsonObject().deepCopy()) : emptyBiome();
        }

        merge(base, definition.overrides);
        removeNulls(base);
        resolved.put(id, base);
        return base;
    }

    /**
     * Objects merge key by key and null removes a key.
     * Attribute values replace the parent value as a whole, so a modifier object never mixes with the parent one
     */
    private static void merge(final JsonObject target, final JsonObject overrides) {
        for (final Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
            final String key = entry.getKey();
            final JsonElement value = entry.getValue();
            if (value.isJsonNull()) {
                target.remove(key);
            } else if (key.equals(ATTRIBUTES) && value.isJsonObject() && target.get(key) instanceof final JsonObject attributes) {
                for (final Map.Entry<String, JsonElement> attribute : value.getAsJsonObject().entrySet()) {
                    if (attribute.getValue().isJsonNull()) attributes.remove(attribute.getKey());
                    else attributes.add(attribute.getKey(), attribute.getValue());
                }
            } else if (value.isJsonObject() && target.get(key) instanceof final JsonObject child) {
                merge(child, value.getAsJsonObject());
            } else {
                target.add(key, value);
            }
        }
    }

    private static void removeNulls(final JsonElement element) {
        if (element instanceof final JsonObject object) {
            object.entrySet().removeIf(entry -> entry.getValue().isJsonNull());
            object.entrySet().forEach(entry -> removeNulls(entry.getValue()));
        } else if (element instanceof final JsonArray array) {
            array.forEach(CartridgeBiomes::removeNulls);
        }
    }

    /**
     * Attribute keys are identifiers, so "visual/sky_color" and "minecraft:visual/sky_color" must collapse into one key before merging
     */
    private static JsonObject normalizeAttributes(final JsonObject biome) {
        if (!(biome.get(ATTRIBUTES) instanceof final JsonObject attributes)) return biome;
        final JsonObject normalized = new JsonObject();
        for (final Map.Entry<String, JsonElement> entry : attributes.entrySet()) {
            normalized.add(Identifier.parse(entry.getKey()).toString(), entry.getValue());
        }
        biome.add(ATTRIBUTES, normalized);
        return biome;
    }

    private static JsonObject emptyBiome() {
        final JsonObject biome = new JsonObject();
        biome.addProperty("has_precipitation", false);
        biome.addProperty("temperature", 0.5F);
        biome.addProperty("downfall", 0.5F);
        final JsonObject effects = new JsonObject();
        effects.addProperty("water_color", "#3f76e4");
        biome.add(EFFECTS, effects);
        biome.add("carvers", new JsonArray());
        biome.add("features", new JsonArray());
        return biome;
    }

    private static Map<Identifier, Definition> readDefinitions() throws IOException {
        if (directory == null || Files.notExists(directory)) return Map.of();

        final List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }

        final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        final Map<Identifier, Definition> definitions = new LinkedHashMap<>();
        for (final Path path : files) {
            final String fileName = path.getFileName().toString();
            final Object root;
            try {
                root = yaml.load(Files.readString(path));
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException(fileName + ": " + e.getMessage(), e);
            }
            if (root == null) continue;
            if (!(root instanceof final Map<?, ?> biomes)) throw new IllegalArgumentException(fileName + " must map biome ids to biome definitions");

            for (final Map.Entry<?, ?> entry : biomes.entrySet()) {
                final Identifier id = parseId(fileName, String.valueOf(entry.getKey()));
                if (!(entry.getValue() instanceof final Map<?, ?> body)) throw new IllegalArgumentException(fileName + ": biome " + id + " must be a section");
                final Definition definition = parseDefinition(fileName, id, body);
                final Definition previous = definitions.put(id, definition);
                if (previous != null) throw new IllegalArgumentException(definition.describe() + " is also defined in " + previous.fileName);
            }
        }
        return definitions;
    }

    private static Definition parseDefinition(final String fileName, final Identifier id, final Map<?, ?> body) {
        @Nullable Identifier parent = null;
        final JsonObject overrides = new JsonObject();
        final List<Map.Entry<List<String>, JsonElement>> shortcuts = new ArrayList<>();
        for (final Map.Entry<?, ?> entry : body.entrySet()) {
            final String key = String.valueOf(entry.getKey());
            if (key.equals(PARENT)) {
                parent = parseId(fileName, String.valueOf(entry.getValue()));
                continue;
            }
            final JsonElement value = toJson(entry.getValue());
            final List<String> shortcut = SHORTCUTS.get(key);
            if (shortcut != null) {
                shortcuts.add(Map.entry(shortcut, value));
            } else {
                overrides.add(key, value);
            }
        }

        normalizeAttributes(overrides);
        for (final Map.Entry<List<String>, JsonElement> shortcut : shortcuts) {
            JsonObject target = overrides;
            final List<String> path = shortcut.getKey();
            for (final String part : path.subList(0, path.size() - 1)) {
                if (!(target.get(part) instanceof final JsonObject child)) {
                    final JsonObject created = new JsonObject();
                    target.add(part, created);
                    target = created;
                } else {
                    target = child;
                }
            }
            target.add(path.getLast(), shortcut.getValue());
        }
        return new Definition(fileName, id, parent, overrides);
    }

    private static Identifier parseId(final String fileName, final String raw) {
        final Identifier id = Identifier.tryParse(raw);
        if (id == null) throw new IllegalArgumentException(fileName + ": invalid biome id '" + raw + "'");
        return id;
    }

    private static JsonElement toJson(final @Nullable Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case final Map<?, ?> map -> {
                final JsonObject object = new JsonObject();
                map.forEach((key, child) -> object.add(String.valueOf(key), toJson(child)));
                yield object;
            }
            case final List<?> list -> {
                final JsonArray array = new JsonArray();
                list.forEach(child -> array.add(toJson(child)));
                yield array;
            }
            case final Number number -> new JsonPrimitive(number);
            case final Boolean bool -> new JsonPrimitive(bool);
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }

    private static String readme() {
        return """
            Custom biomes and overrides for existing ones, baked into the biome registry at startup.
            Changes need a full restart, /cartridge reload does not touch biomes.
            A broken file stops the server from starting, so chunks using a biome never lose it.

            Every .yml file in this folder maps biome ids to definitions, ids without a namespace use minecraft.
            A definition uses the vanilla data pack biome format (https://minecraft.wiki/w/Biome_definition) and is merged onto a base:
              parent: <biome id>  copies another biome, either a configured one or one from a data pack
              no parent           the existing biome with the same id when there is one (an override), otherwise an empty biome without features or mob spawns
            Sections merge key by key, each attribute replaces the parent one as a whole, and a value of ~ (null) removes the key.
            Colors are quoted hex strings like "#3f76e4", since an unquoted # starts a YAML comment.

            Shortcuts for the common colors:
              waterColor, grassColor, foliageColor, dryFoliageColor, grassColorModifier (none, dark_forest, swamp) -> effects
              skyColor, fogColor, waterFogColor -> attributes minecraft:visual/*
              hasPrecipitation -> has_precipitation

            Example (mineinabyss.yml):

            mineinabyss:orth:
              parent: plains
              temperature: 0.8
              waterColor: "#3f76e4"
              waterFogColor: "#050533"
              skyColor: "#78a7ff"
              fogColor: "#c0d8ff"
              grassColor: "#79c05a"
              attributes:
                visual/fog_end_distance: 96
                gameplay/natural_mob_spawns: ~

            # Overrides vanilla plains for everyone
            plains:
              waterColor: "#ff0000"
            """;
    }

    private record Definition(String fileName, Identifier id, @Nullable Identifier parent, JsonObject overrides) {
        String describe() {
            return this.fileName + ": biome " + this.id;
        }
    }
}
