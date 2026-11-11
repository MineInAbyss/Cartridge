package com.mineinabyss.cartridge.event;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Called when redstone power is about to switch a hopper on or off.
 * Cancelling leaves it as it is until its neighbours change again
 */
@NullMarked
public class HopperToggleEvent extends BlockEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final boolean enabled;
    private boolean cancelled;

    public HopperToggleEvent(final Block hopper, final boolean enabled) {
        super(hopper);
        this.enabled = enabled;
    }

    /** Whether the hopper is being switched on, false when power is switching it off */
    public boolean isEnabled() {
        return this.enabled;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
