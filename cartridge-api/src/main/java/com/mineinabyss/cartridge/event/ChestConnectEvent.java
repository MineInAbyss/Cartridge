package com.mineinabyss.cartridge.event;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Called while a chest is being placed next to one it would join into a double chest.
 * The placed block is not in the world yet, so {@link #getBlock()} still shows what it replaces.
 * Cancelling places it as a single chest
 */
@NullMarked
public class ChestConnectEvent extends BlockEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Block partner;
    private final @Nullable Player player;
    private boolean cancelled;

    public ChestConnectEvent(final Block block, final Block partner, final @Nullable Player player) {
        super(block);
        this.partner = partner;
        this.player = player;
    }

    public Block getPartner() {
        return this.partner;
    }

    public @Nullable Player getPlayer() {
        return this.player;
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
