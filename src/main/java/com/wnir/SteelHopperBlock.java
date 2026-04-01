package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Steel Hopper — 10-slot hopper transferring 8 items per 8-tick cycle.
 * Renders as iron using iron block textures.
 */
public class SteelHopperBlock extends HopperBlock {

    private static final MapCodec<SteelHopperBlock> CODEC = simpleCodec(SteelHopperBlock::new);

    @SuppressWarnings("unchecked")
    @Override
    public MapCodec<HopperBlock> codec() {
        return (MapCodec<HopperBlock>) (MapCodec<?>) CODEC;
    }

    public SteelHopperBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SteelHopperBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        return level.isClientSide() ? null
            : createTickerHelper(type, WnirRegistries.STEEL_HOPPER_BE.get(), SteelHopperBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof SteelHopperBlockEntity be) {
            player.openMenu(be);
            player.awardStat(Stats.INSPECT_HOPPER);
        }
        return InteractionResult.CONSUME;
    }
}
