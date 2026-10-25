package com.mineinabyss.cartridge.config;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@NullMarked
public final class CartridgeGlobalConfig {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String FILE_NAME = "cartridge-global.yml";

    private static volatile CartridgeGlobalConfig instance = new CartridgeGlobalConfig(new YamlConfiguration());
    private static @Nullable Path file;

    public final DoorAutoClose doorAutoClose;
    public final DoubleDoors doubleDoors;

    private CartridgeGlobalConfig(final ConfigurationSection config) {
        this.doorAutoClose = new DoorAutoClose(config.getConfigurationSection("door-auto-close"));
        this.doubleDoors = new DoubleDoors(config.getConfigurationSection("double-doors"));
    }

    public static CartridgeGlobalConfig get() {
        return instance;
    }

    public static void init(final Path configDir) {
        file = configDir.resolve(FILE_NAME);
        try {
            reload();
        } catch (final IOException | InvalidConfigurationException e) {
            LOGGER.error("Could not load {}, using defaults", file, e);
        }
    }

    /**
     * Keeps the previous config when the file fails to parse
     */
    public static void reload() throws IOException, InvalidConfigurationException {
        if (file == null) throw new IllegalStateException("Cartridge global config is not initialized");
        final YamlConfiguration config = new YamlConfiguration();
        if (Files.exists(file)) {
            config.loadFromString(Files.readString(file));
        }

        final CartridgeGlobalConfig loaded = new CartridgeGlobalConfig(config);
        if (Files.notExists(file)) {
            loaded.save(file);
        }
        instance = loaded;
    }

    private void save(final Path path) throws IOException {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of("Cartridge server settings, reload with /cartridge reload"));
        this.doorAutoClose.write(config.createSection("door-auto-close"));
        config.setComments("door-auto-close", List.of(
            "Closes doors opened by hand after a delay",
            "The autoCloseDoors region flag overrides enabled, allow turns it on and deny turns it off inside a region"
        ));
        this.doubleDoors.write(config.createSection("double-doors"));
        Files.writeString(path, config.saveToString());
    }

    public static final class DoorAutoClose {
        public final boolean enabled;
        public final int closeAfterTicks;
        public final boolean ignoreCreative;
        public final boolean ignoreSneaking;

        private DoorAutoClose(final @Nullable ConfigurationSection section) {
            this.enabled = section != null && section.getBoolean("enabled", false);
            this.closeAfterTicks = Math.max(1, section == null ? 100 : section.getInt("close-after-ticks", 100));
            this.ignoreCreative = section == null || section.getBoolean("ignore-creative", true);
            this.ignoreSneaking = section != null && section.getBoolean("ignore-sneaking", false);
        }

        private void write(final ConfigurationSection section) {
            section.set("enabled", this.enabled);
            section.set("close-after-ticks", this.closeAfterTicks);
            section.set("ignore-creative", this.ignoreCreative);
            section.set("ignore-sneaking", this.ignoreSneaking);
        }
    }

    public static final class DoubleDoors {
        public final boolean synchronize;

        private DoubleDoors(final @Nullable ConfigurationSection section) {
            this.synchronize = section != null && section.getBoolean("synchronize", false);
        }

        private void write(final ConfigurationSection section) {
            section.set("synchronize", this.synchronize);
            section.setComments("synchronize", List.of("Opening or closing one door of a double door by hand does the same to the other"));
        }
    }
}
