package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lighting Post — warding column block that emits the brightest possible light (level 15).
 *
 * Participates in the mixed warding column: stacking lighting posts with warding/inhibitor/
 * repelling posts contributes to column height and to all column-wide radius calculations.
 *
 * Primary purpose: provide maximum-brightness illumination in a slender post shape.
 * Light level 15 is set in the block properties via lightLevel() and requires no tick logic.
 */
public class LightingPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<LightingPostBlock> CODEC = simpleCodec(LightingPostBlock::new);

    @Override
    protected MapCodec<LightingPostBlock> codec() { return CODEC; }

    public LightingPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WardingColumnBlockEntity.create(pos, state);
    }
}
