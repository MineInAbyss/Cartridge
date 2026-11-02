package com.mineinabyss.cartridge.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mineinabyss.cartridge.biome.BiomePreview;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.AQUA;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.WHITE;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

@NullMarked
final class BiomeCommand {
    static final String PERMISSION = "cartridge.command.biome";
    private static final String ATTRIBUTE = "attribute";
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_VALUE_LENGTH = 60;

    private BiomeCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("biome")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .then(Commands.literal("set")
                .then(biomeArgument()
                    .then(Commands.literal(ATTRIBUTE)
                        .then(attributeArgument()
                            .then(valueArgument(context -> BiomePreview.attribute(attribute(context), value(context))))))
                    .then(propertyArgument()
                        .then(valueArgument(context -> BiomePreview.property(StringArgumentType.getString(context, "property"), value(context)))))))
            .then(Commands.literal("reset")
                .then(biomeArgument()
                    .executes(context -> reset(context, null))
                    .then(Commands.literal(ATTRIBUTE)
                        .then(attributeArgument().executes(context -> reset(context, BiomePreview.attribute(attribute(context), JsonNull.INSTANCE).property()))))
                    .then(propertyArgument().executes(context -> reset(context, StringArgumentType.getString(context, "property"))))))
            .then(Commands.literal("info")
                .executes(context -> info(context, biomeAt(context.getSource().getLocation())))
                .then(biomeArgument().executes(context -> info(context, biome(context)))))
            .then(Commands.literal("reload")
                .executes(BiomeCommand::reloadSelf)
                .then(Commands.argument("players", ArgumentTypes.players()).executes(BiomeCommand::reloadPlayers)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> biomeArgument() {
        return Commands.argument("biome", ArgumentTypes.resourceKey(RegistryKey.BIOME));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> propertyArgument() {
        return Commands.argument("property", StringArgumentType.word())
            .suggests((context, builder) -> {
                BiomePreview.PROPERTIES.keySet().stream()
                    .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                    .forEach(builder::suggest);
                return builder.buildFuture();
            });
    }

    private static ArgumentBuilder<CommandSourceStack, ?> attributeArgument() {
        return Commands.argument(ATTRIBUTE, ArgumentTypes.key())
            .suggests((context, builder) -> {
                BiomePreview.attributeIds().stream()
                    .filter(id -> id.startsWith(builder.getRemainingLowerCase()) || id.substring(id.indexOf(':') + 1).startsWith(builder.getRemainingLowerCase()))
                    .forEach(builder::suggest);
                return builder.buildFuture();
            });
    }

    private static ArgumentBuilder<CommandSourceStack, ?> valueArgument(final Function<CommandContext<CommandSourceStack>, BiomePreview.Change> change) {
        return Commands.argument("value", StringArgumentType.greedyString())
            .executes(context -> set(context, change));
    }

    private static Identifier biome(final CommandContext<CommandSourceStack> context) {
        @SuppressWarnings("unchecked") final TypedKey<org.bukkit.block.Biome> key = context.getArgument("biome", TypedKey.class);
        return Identifier.parse(key.key().asString());
    }

    private static Identifier attribute(final CommandContext<CommandSourceStack> context) {
        return Identifier.parse(context.getArgument(ATTRIBUTE, Key.class).asString());
    }

    private static JsonElement value(final CommandContext<CommandSourceStack> context) {
        return BiomePreview.parseValue(StringArgumentType.getString(context, "value"));
    }

    private static Identifier biomeAt(final Location location) {
        return ((CraftWorld) location.getWorld()).getHandle()
            .getBiome(BlockPos.containing(location.getX(), location.getY(), location.getZ()))
            .unwrapKey()
            .orElseThrow()
            .identifier();
    }

    private static int set(final CommandContext<CommandSourceStack> context, final Function<CommandContext<CommandSourceStack>, BiomePreview.Change> changeFactory) {
        final CommandSender sender = context.getSource().getSender();
        final Identifier id = biome(context);
        final BiomePreview.Change change;
        try {
            change = changeFactory.apply(context);
            BiomePreview.set(id, change);
        } catch (final IllegalArgumentException e) {
            sender.sendMessage(text(e.getMessage(), RED));
            return 0;
        }

        sender.sendMessage(text("Previewing ", GREEN)
            .append(text(change.property(), YELLOW))
            .append(text(change.value().isJsonNull() ? " removed" : " = " + displayValue(change.value()), GREEN))
            .append(text(" on " + id + ". ", GREEN))
            .append(reloadButton()));
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(final CommandContext<CommandSourceStack> context, final @Nullable String property) {
        final CommandSender sender = context.getSource().getSender();
        final Identifier id = biome(context);
        final boolean changed;
        try {
            changed = BiomePreview.reset(id, property);
        } catch (final IllegalArgumentException e) {
            sender.sendMessage(text(e.getMessage(), RED));
            return 0;
        }
        if (!changed) {
            sender.sendMessage(text("Nothing previewed for " + (property == null ? "" : property + " on ") + id, YELLOW));
            return 0;
        }

        sender.sendMessage(text("Reset " + (property == null ? "all previews" : property) + " on " + id + ". ", GREEN).append(reloadButton()));
        return Command.SINGLE_SUCCESS;
    }

    private static int info(final CommandContext<CommandSourceStack> context, final Identifier id) {
        final CommandSender sender = context.getSource().getSender();
        final List<BiomePreview.Entry> entries;
        try {
            entries = BiomePreview.entries(id);
        } catch (final IllegalArgumentException e) {
            sender.sendMessage(text(e.getMessage(), RED));
            return 0;
        }

        final int changes = BiomePreview.changes(id).size();
        sender.sendMessage(text("Biome " + id, GOLD).append(changes == 0 ? Component.empty() : text(" (" + changes + " previewed)", YELLOW)));
        for (final BiomePreview.Entry entry : entries) {
            sender.sendMessage(entryLine(id, entry));
        }

        if (changes > 0) {
            final String yaml = BiomePreview.yaml(id);
            sender.sendMessage(text("[Copy YAML]", AQUA)
                .hoverEvent(HoverEvent.showText(text("Copies the previewed changes for config/cartridge/biomes\n\n" + yaml, GRAY)))
                .clickEvent(ClickEvent.copyToClipboard(yaml))
                .append(text(" "))
                .append(text("[Reset]", RED)
                    .hoverEvent(HoverEvent.showText(text("Drops every preview on this biome", GRAY)))
                    .clickEvent(ClickEvent.runCommand("/cartridge biome reset " + id)))
                .append(text(" "))
                .append(reloadButton()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Component entryLine(final Identifier id, final BiomePreview.Entry entry) {
        final JsonElement value = entry.value();
        final String fullValue = value == null ? (entry.previewed() ? "removed" : "unset") : displayValue(value);
        final String shown = fullValue.length() > MAX_VALUE_LENGTH ? fullValue.substring(0, MAX_VALUE_LENGTH) + "..." : fullValue;

        Component line = text(" " + entry.property() + ": ", GRAY);
        if (HEX_COLOR.matcher(fullValue).matches()) {
            line = line.append(text("■ ", TextColor.fromHexString(fullValue)));
        }
        line = line.append(text(shown, value == null ? (entry.previewed() ? RED : GRAY) : WHITE));
        if (entry.previewed()) {
            line = line.append(text(" (preview)", YELLOW));
        }

        final String command = setCommand(id, entry);
        Component hover = text(fullValue, WHITE);
        if (entry.previewed()) hover = hover.append(text("\nPreview only, add it to the biome config and restart to keep it", YELLOW));
        if (command != null) hover = hover.append(text("\nClick to edit", GRAY));
        line = line.hoverEvent(HoverEvent.showText(hover));
        return command == null ? line : line.clickEvent(ClickEvent.suggestCommand(command + (value == null ? "" : fullValue)));
    }

    private static @Nullable String setCommand(final Identifier id, final BiomePreview.Entry entry) {
        if (BiomePreview.PROPERTIES.containsKey(entry.property())) return "/cartridge biome set " + id + " " + entry.property() + " ";
        if (entry.path().getFirst().equals("attributes")) return "/cartridge biome set " + id + " " + ATTRIBUTE + " " + entry.path().getLast() + " ";
        return null;
    }

    private static String displayValue(final JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
    }

    private static Component reloadButton() {
        return text("[Resend to me]", AQUA)
            .hoverEvent(HoverEvent.showText(text("Runs /cartridge biome reload, which briefly sends you to the loading screen", GRAY)))
            .clickEvent(ClickEvent.runCommand("/cartridge biome reload"));
    }

    private static int reloadSelf(final CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getExecutor() instanceof final Player player)) {
            context.getSource().getSender().sendMessage(text("Pass the players to resend biomes to", RED));
            return 0;
        }
        return resync(context.getSource().getSender(), List.of(player));
    }

    private static int reloadPlayers(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final List<Player> players = context.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(context.getSource());
        return resync(context.getSource().getSender(), players);
    }

    private static int resync(final CommandSender sender, final List<Player> players) {
        players.forEach(BiomePreview::resync);
        sender.sendMessage(text("Resending biomes to " + players.size() + " player(s)", GREEN));
        return players.size();
    }
}
