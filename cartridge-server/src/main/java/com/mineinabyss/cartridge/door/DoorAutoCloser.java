package com.mineinabyss.cartridge.door;

import com.mineinabyss.cartridge.config.CartridgeGlobalConfig;
import com.mineinabyss.cartridge.region.RegionFlag;
import com.mineinabyss.cartridge.region.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class DoorAutoCloser {

    private DoorAutoCloser() {
    }

    public static void onToggledByHand(final DoorBlock door, final Level level, BlockPos pos, final Player player) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(door)) return;
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
            state = level.getBlockState(pos);
            if (!state.is(door)) return;
        }

        final CartridgeGlobalConfig config = CartridgeGlobalConfig.get();
        final boolean open = door.isOpen(state);
        final BlockPos pairedPos = config.doubleDoors.synchronize ? pairedDoor(serverLevel, pos, state, player) : null;
        if (pairedPos != null) {
            final BlockState paired = level.getBlockState(pairedPos);
            ((DoorBlock) paired.getBlock()).setOpen(null, level, paired, pairedPos, open);
        }

        final CartridgeGlobalConfig.DoorAutoClose autoClose = config.doorAutoClose;
        if (!open) return;
        if (autoClose.ignoreCreative && player.isCreative()) return;
        if (autoClose.ignoreSneaking && player.isShiftKeyDown()) return;
        if (!isActive(serverLevel, pos)) return;
        scheduleClose(serverLevel, pos, door, autoClose.closeAfterTicks);
        if (pairedPos != null) {
            scheduleClose(serverLevel, pairedPos, level.getBlockState(pairedPos).getBlock(), autoClose.closeAfterTicks);
        }
    }

    public static void tick(final DoorBlock door, final BlockState state, final ServerLevel level, final BlockPos pos) {
        if (!door.isOpen(state) || state.getValue(DoorBlock.POWERED)) return;
        if (!isActive(level, pos)) return;
        door.setOpen(null, level, state, pos, false);
    }

    private static boolean isActive(final ServerLevel level, final BlockPos pos) {
        final Boolean flag = Regions.regions().query(CraftLocation.toBukkit(pos, level), RegionFlag.AUTO_CLOSE_DOORS);
        return flag != null ? flag : CartridgeGlobalConfig.get().doorAutoClose.enabled;
    }

    // Scheduled ticks cannot be pushed back, so a reopened door drops its pending close instead of keeping the earlier one
    private static void scheduleClose(final ServerLevel level, final BlockPos pos, final Block block, final int delay) {
        level.getBlockTicks().clearArea(new BoundingBox(pos));
        level.scheduleTick(pos, block, delay);
    }

    private static @Nullable BlockPos pairedDoor(final ServerLevel level, final BlockPos lowerPos, final BlockState lower, final Player player) {
        final Direction facing = lower.getValue(DoorBlock.FACING);
        final DoorHingeSide hinge = lower.getValue(DoorBlock.HINGE);
        final BlockPos pairedPos = lowerPos.relative(hinge == DoorHingeSide.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
        final BlockState paired = level.getBlockState(pairedPos);
        if (!(paired.getBlock() instanceof DoorBlock pairedDoor) || !pairedDoor.type().canOpenByHand()) return null;
        if (paired.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || paired.getValue(DoorBlock.FACING) != facing || paired.getValue(DoorBlock.HINGE) == hinge) return null;
        if (pairedDoor.isOpen(paired) == pairedDoor.isOpen(lower)) return null;

        // Lets region protection and lock plugins veto the partner as if the player had clicked it
        final PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, pairedPos, facing, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (event.useInteractedBlock() == Event.Result.DENY) return null;
        return pairedPos;
    }
}
