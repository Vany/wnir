package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Warding Post: contributes +6 to column radius. No push, no teleport inhibition. */
public class WardingPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<WardingPostBlock> CODEC = simpleCodec(WardingPostBlock::new);

    @Override
    protected MapCodec<WardingPostBlock> codec() { return CODEC; }

    public WardingPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_POST_BE::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WardingPostBlockEntity(pos, state);
    }
}
