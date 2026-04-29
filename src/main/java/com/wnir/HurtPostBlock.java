package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hurt Post — warding column block that deals 1 heart of armor-bypassing magic damage
 * every 4 ticks to all hostile mobs (Enemy implementors) within the column radius.
 *
 * Participates in the mixed warding column; contributes NO radius (damage-only post).
 */
public class HurtPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<HurtPostBlock> CODEC = simpleCodec(HurtPostBlock::new);

    @Override
    protected MapCodec<HurtPostBlock> codec() { return CODEC; }

    public HurtPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WardingColumnBlockEntity.create(pos, state);
    }
}
