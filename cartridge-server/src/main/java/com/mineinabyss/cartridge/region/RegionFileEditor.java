package com.mineinabyss.cartridge.region;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

/**
 * Edits a single flag as text so the rest of the file, including anchors, merge keys and comments, stays as written.
 * Bukkit would rewrite the whole file and expand every alias
 */
@NullMarked
final class RegionFileEditor {

    private static final String MERGE_KEY = "<<";
    private static final String CHILDREN = "children";

    private RegionFileEditor() {
    }

    /**
     * @param sectionPath dotted path of the region section, the global section keeps its flags at its root
     * @param value null removes the flag
     */
    static String setFlag(final String text, final String sectionPath, final String flag, final @Nullable String value) {
        final Node root = new Yaml().compose(new StringReader(text));
        final MappingNode section = navigate(root, sectionPath);
        if (section.getFlowStyle() == DumperOptions.FlowStyle.FLOW) throw new IllegalArgumentException("this region is written inline, edit the file instead");
        final List<String> lines = new ArrayList<>(text.lines().toList());
        final boolean global = sectionPath.equals(CartridgeRegions.GLOBAL_REGION);

        final MappingNode flags;
        if (global) {
            flags = section;
        } else {
            final NodeTuple flagsTuple = tuple(section, "flags");
            if (flagsTuple == null || isEmptyScalar(flagsTuple.getValueNode())) {
                if (value == null) throw new IllegalArgumentException(flag + " is not set on this region");
                insertFlagsBlock(lines, section, flagsTuple, flag, value);
                return join(lines, text);
            }

            final Node valueNode = flagsTuple.getValueNode();
            if (isAlias(flagsTuple)) {
                if (value == null) throw notLocal(flag, valueNode);
                final int line = flagsTuple.getKeyNode().getStartMark().getLine();
                final String indent = " ".repeat(flagsTuple.getKeyNode().getStartMark().getColumn());
                lines.set(line, indent + "flags:");
                lines.add(line + 1, indent + "  " + MERGE_KEY + ": *" + valueNode.getAnchor());
                lines.add(line + 2, indent + "  " + flag + ": " + value);
                return join(lines, text);
            }
            if (!(valueNode instanceof MappingNode mapping) || mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
                throw new IllegalArgumentException("flags of this region are written inline, edit the file instead");
            }
            flags = mapping;
        }

        if (flags.getAnchor() != null) {
            throw new IllegalArgumentException("flags of this region are shared as &" + flags.getAnchor() + " with other regions, edit the file instead");
        }

        final NodeTuple existing = tuple(flags, flag);
        if (existing != null) {
            final int line = existing.getKeyNode().getStartMark().getLine();
            if (value != null) {
                final String current = lines.get(line);
                final int start = current.offsetByCodePoints(0, existing.getValueNode().getStartMark().getColumn());
                final int end = current.offsetByCodePoints(0, existing.getValueNode().getEndMark().getColumn());
                lines.set(line, current.substring(0, start) + value + current.substring(end));
            } else if (!global && flags.getValue().size() == 1) {
                final NodeTuple flagsTuple = tuple(section, "flags");
                removeLines(lines, flagsTuple.getKeyNode().getStartMark().getLine(), endLine(existing));
            } else {
                removeLines(lines, line, endLine(existing));
            }
            return join(lines, text);
        }

        if (value == null) {
            final NodeTuple merge = tuple(flags, MERGE_KEY);
            if (merge != null && merge.getValueNode() instanceof MappingNode shared && tuple(shared, flag) != null) throw notLocal(flag, shared);
            throw new IllegalArgumentException(flag + " is not set on this region");
        }
        lines.add(insertionLine(flags, flag), " ".repeat(flags.getValue().getFirst().getKeyNode().getStartMark().getColumn()) + flag + ": " + value);
        return join(lines, text);
    }

    private static MappingNode navigate(final Node root, final String path) {
        Node current = root;
        for (final String part : path.split("\\.")) {
            final NodeTuple tuple = current instanceof MappingNode mapping ? tuple(mapping, part) : null;
            if (tuple == null) throw new IllegalArgumentException("Could not find " + path + " in the file, reload regions first");
            current = tuple.getValueNode();
        }
        if (!(current instanceof MappingNode mapping)) throw new IllegalArgumentException(path + " is not a section");
        return mapping;
    }

    private static @Nullable NodeTuple tuple(final MappingNode mapping, final String key) {
        for (final NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) return tuple;
        }
        return null;
    }

    private static boolean isEmptyScalar(final Node node) {
        return node instanceof ScalarNode scalar && scalar.getValue().isEmpty();
    }

    /**
     * The composer hands out the anchored node itself for an alias, so an alias is a value that starts before its own key
     */
    private static boolean isAlias(final NodeTuple tuple) {
        return tuple.getValueNode().getStartMark().getIndex() < tuple.getKeyNode().getStartMark().getIndex();
    }

    private static int endLine(final NodeTuple tuple) {
        if (isAlias(tuple)) return tuple.getKeyNode().getStartMark().getLine();
        final int startLine = tuple.getKeyNode().getStartMark().getLine();
        final int endLine = tuple.getValueNode().getEndMark().getLine();
        return endLine > startLine && tuple.getValueNode().getEndMark().getColumn() == 0 ? endLine - 1 : endLine;
    }

    /**
     * Keeps flags sorted when the mapping already is, after any merge key, otherwise appends
     */
    private static int insertionLine(final MappingNode flags, final String flag) {
        int after = -1;
        for (final NodeTuple tuple : flags.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode scalar)) continue;
            final String key = scalar.getValue();
            if (!key.equals(MERGE_KEY) && RegionFlag.byKey(key) == null) continue;
            if (!key.equals(MERGE_KEY) && key.compareTo(flag) > 0) return tuple.getKeyNode().getStartMark().getLine();
            after = endLine(tuple);
        }
        return after == -1 ? flags.getValue().getFirst().getKeyNode().getStartMark().getLine() : after + 1;
    }

    /**
     * Adds a flags block before settings or children so it reads like the other regions
     */
    private static void insertFlagsBlock(final List<String> lines, final MappingNode section, final @Nullable NodeTuple emptyFlags, final String flag, final String value) {
        final int column = section.getValue().getFirst().getKeyNode().getStartMark().getColumn();
        final String indent = " ".repeat(column);
        if (emptyFlags != null) {
            final int line = emptyFlags.getKeyNode().getStartMark().getLine();
            lines.set(line, indent + "flags:");
            lines.add(line + 1, indent + "  " + flag + ": " + value);
            return;
        }

        int at = endLine(section.getValue().getLast()) + 1;
        for (final NodeTuple tuple : section.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar
                && (scalar.getValue().equals(CartridgeRegions.SETTINGS) || scalar.getValue().equals(CHILDREN))) {
                at = tuple.getKeyNode().getStartMark().getLine();
                break;
            }
        }
        lines.add(at, indent + "flags:");
        lines.add(at + 1, indent + "  " + flag + ": " + value);
    }

    private static void removeLines(final List<String> lines, final int from, final int to) {
        lines.subList(from, to + 1).clear();
    }

    private static IllegalArgumentException notLocal(final String flag, final Node shared) {
        return new IllegalArgumentException(flag + " comes from the shared block &" + shared.getAnchor() + ", edit the file instead");
    }

    private static String join(final List<String> lines, final String original) {
        return String.join("\n", lines) + (original.endsWith("\n") ? "\n" : "");
    }
}
