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
 * Mossy Hopper — 10-slot hopper that never empties a slot below 1 item,
 * transferring 2 items per 8-tick cycle from random eligible slots.
 */
public class MossyHopperBlock extends HopperBlock {

    private static final MapCodec<MossyHopperBlock> CODEC = simpleCodec(MossyHopperBlock::new);

    // HopperBlock.codec() returns MapCodec<HopperBlock>; Java generics are invariant,
    // so we cast through the wildcard to satisfy the override contract at runtime.
    @SuppressWarnings("unchecked")
    @Override
    public MapCodec<HopperBlock> codec() {
        return (MapCodec<HopperBlock>) (MapCodec<?>) CODEC;
    }

    public MossyHopperBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MossyHopperBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        return level.isClientSide() ? null
            : createTickerHelper(type, WnirRegistries.MOSSY_HOPPER_BE.get(), MossyHopperBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MossyHopperBlockEntity be) {
            player.openMenu(be);
            player.awardStat(Stats.INSPECT_HOPPER);
        }
        return InteractionResult.CONSUME;
    }
}
