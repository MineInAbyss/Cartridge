package com.mineinabyss.cartridge.command;

import com.mineinabyss.cartridge.region.CartridgeRegions;
import com.mineinabyss.cartridge.region.Region;
import com.mineinabyss.cartridge.region.RegionFlag;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.NullMarked;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

@NullMarked
public final class CartridgeCommand {
    public static final String DESCRIPTION = "Cartridge server commands";

    private CartridgeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("cartridge")
            .requires(source -> source.getSender().hasPermission("cartridge.command.region"))
            .then(Commands.literal("region")
                .then(Commands.literal("reload").executes(CartridgeCommand::reload))
                .then(Commands.literal("info").executes(CartridgeCommand::info))
                .then(Commands.literal("list").executes(CartridgeCommand::list)))
            .build();
    }

    private static int reload(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        final List<String> warnings;
        try {
            warnings = CartridgeRegions.instance().reload();
        } catch (final Exception e) {
            sender.sendMessage(text("Could not reload regions, keeping the previous ones: " + e.getMessage(), RED));
            return 0;
        }

        warnings.forEach(warning -> sender.sendMessage(text(warning, YELLOW)));
        sender.sendMessage(text("Reloaded regions with " + warnings.size() + " warning(s)", GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int info(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        final Location location = context.getSource().getLocation();
        final CartridgeRegions regions = CartridgeRegions.instance();

        final List<Region> here = regions.regionsAt(location);
        sender.sendMessage(text("Regions here: ", GOLD).append(here.isEmpty()
            ? text("none", GRAY)
            : text(String.join(", ", here.stream().map(region -> region.bounds() == null ? region.name() : region.name() + " (" + region.priority() + ")").toList()), YELLOW)));

        for (final RegionFlag flag : RegionFlag.values()) {
            final Boolean value = regions.query(location, flag);
            if (value == null) continue;
            Component line = text(" " + flag.key() + ": ", GRAY)
                .append(text(value ? "allow" : "deny", value ? GREEN : RED));
            if (sender instanceof Player player && Boolean.FALSE.equals(value) && regions.isAllowed(player, location, flag)) {
                line = line.append(text(" (bypassed)", YELLOW));
            }
            sender.sendMessage(line);
        }

        final Set<EntityType> denySpawn = regions.denySpawn(location);
        if (!denySpawn.isEmpty()) {
            sender.sendMessage(text(" " + CartridgeRegions.DENY_SPAWN + ": ", GRAY).append(text(entityTypesText(denySpawn), RED)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        CartridgeRegions.instance().worlds().forEach((worldName, world) -> {
            sender.sendMessage(text(worldName, GOLD));
            if (world.global() != null) {
                sender.sendMessage(text(" " + CartridgeRegions.GLOBAL_REGION + " " + flagsText(world.global()), GRAY));
            }
            for (final Region region : world.templates()) {
                sender.sendMessage(text(" " + region.name() + " (template" + (region.parent() == null ? "" : ", child of " + region.parent().name()) + ") " + flagsText(region), GRAY));
            }
            for (final Region region : world.regions()) {
                final BoundingBox box = region.bounds();
                sender.sendMessage(text(" " + region.name() + " (" + region.priority() + (region.parent() == null ? "" : ", child of " + region.parent().name()) + ") ", YELLOW)
                    .append(text("%d %d %d -> %d %d %d %s".formatted(
                        (int) box.getMinX(), (int) box.getMinY(), (int) box.getMinZ(),
                        (int) box.getMaxX() - 1, (int) box.getMaxY() - 1, (int) box.getMaxZ() - 1,
                        flagsText(region)
                    ), GRAY)));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static String flagsText(final Region region) {
        final List<String> flags = new ArrayList<>(region.flags().entrySet().stream()
            .map(entry -> entry.getKey().key() + "=" + (entry.getValue() ? "allow" : "deny"))
            .toList());
        if (region.denySpawn() != null) flags.add(CartridgeRegions.DENY_SPAWN + "=" + entityTypesText(region.denySpawn()));
        return flags.toString();
    }

    private static String entityTypesText(final Set<EntityType> types) {
        return types.stream().map(type -> type.getKey().getKey()).toList().toString();
    }
}
