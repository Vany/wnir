package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Silencer Post — warding column block that reduces volume of all entity sounds
 * within the column radius by 90%.
 *
 * Participates in the mixed warding column; contributes SILENCER_RADIUS to total column radius.
 * Effect is client-side: sounds originating within the radius are attenuated.
 */
public class SilencerPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<SilencerPostBlock> CODEC = simpleCodec(SilencerPostBlock::new);

    @Override
    protected MapCodec<SilencerPostBlock> codec() { return CODEC; }

    public SilencerPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WardingColumnBlockEntity.create(pos, state);
    }
}
