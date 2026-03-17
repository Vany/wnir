package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Teleporter Crystal — created by placing crying obsidian on top of an EE Clock column
 * (or placing an EE Clock below existing crying obsidian).
 *
 * Grows over 168 000 ticks (1 Minecraft week with 1 clock), scaled by column height.
 * Consumes 16 ender pearls total: 14 as fuel + 2 at transformation.
 * When fully grown, transforms into a PersonalDimensionTeleporterBlock.
 */
public class TeleporterCrystalBlock extends BaseEntityBlock {

    private static final MapCodec<TeleporterCrystalBlock> CODEC =
        simpleCodec(TeleporterCrystalBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public TeleporterCrystalBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TeleporterCrystalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, WnirRegistries.TELEPORTER_CRYSTAL_BE.get(),
            TeleporterCrystalBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof TeleporterCrystalBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.CONSUME;
    }

    // ── Transformation triggers ───────────────────────────────────────────

    /**
     * Called from EEClockBlock.onPlace().
     * If the block above clockPos is crying obsidian, transforms it to TeleporterCrystalBlock.
     */
    static void tryTransformAbove(Level level, BlockPos clockPos) {
        if (level.isClientSide()) return;
        BlockPos above = clockPos.above();
        if (level.getBlockState(above).is(Blocks.CRYING_OBSIDIAN)) {
            level.setBlock(above, WnirRegistries.TELEPORTER_CRYSTAL_BLOCK.get().defaultBlockState(), 3);
        }
    }

    /**
     * Called from EntityPlaceEvent when crying obsidian is placed.
     * If the block below is an EEClockBlock, transforms this position to TeleporterCrystalBlock.
     */
    static void tryTransformAt(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        if (!level.getBlockState(pos).is(Blocks.CRYING_OBSIDIAN)) return;
        if (level.getBlockState(pos.below()).getBlock() instanceof EEClockBlock) {
            level.setBlock(pos, WnirRegistries.TELEPORTER_CRYSTAL_BLOCK.get().defaultBlockState(), 3);
        }
    }
}
