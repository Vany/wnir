package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.minecraft.world.level.redstone.Orientation;

import javax.annotation.Nullable;

/**
 * Trader block.
 *
 * Right-click (empty hand) → opens GUI.
 * Right-click (fluid container) → bucket fill/drain for the cellulose tank.
 * Redstone rising edge → performTradeCycle on the BE.
 * Must sit on a Container block; GUI shows error otherwise.
 * State preserved on mine via playerWillDestroy (same pattern as Celluloser).
 */
public class TraderBlock extends BaseEntityBlock {

    private static final MapCodec<TraderBlock> CODEC = simpleCodec(TraderBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public TraderBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TraderBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        // No continuous tick needed — trade cycle is event-driven (redstone)
        return null;
    }

    /**
     * Detect redstone rising edge and fire the trade cycle.
     * neighborChanged is called whenever a neighbor block changes, including
     * when a redstone signal changes.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel sLevel)) return;
        if (!(level.getBlockEntity(pos) instanceof TraderBlockEntity be)) return;

        boolean nowPowered = level.hasNeighborSignal(pos);
        WnirMod.LOGGER.info("[Trader] neighborChanged at {}, nowPowered={}", pos, nowPowered);
        if (be.checkAndUpdatePower(nowPowered)) {
            WnirMod.LOGGER.info("[Trader] rising edge at {}, firing trade cycle", pos);
            be.performTradeCycle(sLevel);
        }
    }

    /** Drops block with full BE state preserved (fluids, trader data, XP). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TraderBlockEntity tbe && !level.isClientSide()) {
            ItemStack stack = new ItemStack(this);
            CompoundTag tag = tbe.saveCustomOnly(level.registryAccess());
            if (!tag.isEmpty()) {
                stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(tbe.getType(), tag));
            }
            ItemEntity entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Right-click with fluid container → interact with cellulose tank. */
    @Override
    protected InteractionResult useItemOn(
        ItemStack stack, BlockState state, Level level, BlockPos pos,
        Player player, InteractionHand hand, BlockHitResult hit
    ) {
        if (stack.isEmpty()) {
            return useWithoutItem(state, level, pos, player, hit);
        }
        if (ItemAccess.forStack(stack).oneByOne().getCapability(Capabilities.Fluid.ITEM) == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        boolean success = net.neoforged.neoforge.transfer.fluid.FluidUtil.interactWithFluidHandler(
            player, hand, level, pos, hit.getDirection());
        return success ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** Right-click (empty hand) → open GUI and send initial sync to client. */
    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof TraderBlockEntity be) {
            player.openMenu(be);
            // Send full trader data immediately after opening.
            // Packets are ordered on the same connection, so this arrives after
            // ClientboundOpenScreenPacket and the client menu is ready.
            if (player instanceof ServerPlayer sp) {
                be.afterMenuOpened(sp, sp.containerMenu.containerId);
            }
        }
        return InteractionResult.CONSUME;
    }
}
