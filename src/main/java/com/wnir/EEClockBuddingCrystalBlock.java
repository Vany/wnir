package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * EE Clock Budding Crystal — created by placing a BuddingAmethystBlock on top of an
 * EE Clock column (or placing an EE Clock below an existing budding amethyst).
 *
 * Grows over time based on EE Clock column height directly below:
 *   progress per game tick = number of EEClocks in the column below
 *   BASE_TICKS = 168000 (1 Minecraft week at 20 tps)
 *   1 EE Clock → 168000 ticks = 1 week
 *   N EE Clocks → 168000 / N ticks
 *
 * When fully grown, transforms into an EEClockBlock.
 * Does NOT accelerate via the EEClock ticker mechanism (guarded in EEClockBlockEntity).
 */
public class EEClockBuddingCrystalBlock extends BaseEntityBlock {

    private static final MapCodec<EEClockBuddingCrystalBlock> CODEC =
        simpleCodec(EEClockBuddingCrystalBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public EEClockBuddingCrystalBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EEClockBuddingCrystalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_BE.get(),
            EEClockBuddingCrystalBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof EEClockBuddingCrystalBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.CONSUME;
    }

    // ── Transformation triggers ───────────────────────────────────────────

    /**
     * Called from EEClockBlock.onPlace() and the EntityPlaceEvent handler.
     * Replaces the block at crystalPos with EEClockBuddingCrystalBlock if it is a
     * BuddingAmethystBlock and there is at least one EEClockBlock directly below it.
     */
    static void tryTransformAbove(Level level, BlockPos clockPos) {
        if (level.isClientSide()) return;
        BlockPos above = clockPos.above();
        if (level.getBlockState(above).getBlock() instanceof BuddingAmethystBlock) {
            level.setBlock(above, WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_BLOCK.get().defaultBlockState(), 3);
        }
    }

    /**
     * Called from EntityPlaceEvent when a BuddingAmethystBlock is placed.
     * If the block below is an EEClockBlock, transform this position to EEClockBuddingCrystalBlock.
     */
    static void tryTransformAt(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        if (!(level.getBlockState(pos).getBlock() instanceof BuddingAmethystBlock)) return;
        if (level.getBlockState(pos.below()).getBlock() instanceof EEClockBlock) {
            level.setBlock(pos, WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_BLOCK.get().defaultBlockState(), 3);
        }
    }
}
