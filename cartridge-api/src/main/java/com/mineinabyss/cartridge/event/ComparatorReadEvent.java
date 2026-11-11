package com.mineinabyss.cartridge.event;

import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Called when a comparator reads a block or item frame, such as how full a container is.
 * Only called for signals above 0, a comparator may read the same source several times per update.
 * Exactly one of {@link #getSource()} and {@link #getItemFrame()} is set
 */
@NullMarked
public class ComparatorReadEvent extends BlockEvent {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final @Nullable Block source;
    private final @Nullable ItemFrame itemFrame;
    private int signal;

    public ComparatorReadEvent(final Block comparator, final @Nullable Block source, final @Nullable ItemFrame itemFrame, final int signal) {
        super(comparator);
        this.source = source;
        this.itemFrame = itemFrame;
        this.signal = signal;
    }

    public @Nullable Block getSource() {
        return this.source;
    }

    public @Nullable ItemFrame getItemFrame() {
        return this.itemFrame;
    }

    /** From 1 to 15 */
    public int getSignal() {
        return this.signal;
    }

    /** Clamped to 0 to 15, 0 reads as empty */
    public void setSignal(final int signal) {
        this.signal = Math.clamp(signal, 0, 15);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
