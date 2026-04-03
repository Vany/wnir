package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reshaper Post — warding column block that trades horizontal radius for vertical reach.
 *
 * Per post in the column:
 *   - Horizontal radius:  −2.0
 *   - Vertical range:     +1.0 up and +1.0 down
 *
 * Useful for deep pits or tall shafts where you need height more than width.
 * Participates in the mixed warding column.
 */
public class ReshaperPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<ReshaperPostBlock> CODEC = simpleCodec(ReshaperPostBlock::new);

    @Override
    protected MapCodec<ReshaperPostBlock> codec() { return CODEC; }

    public ReshaperPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WardingColumnBlockEntity.create(pos, state);
    }
}
