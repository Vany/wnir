package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wither Silencer — completely cancels Wither spawn and death sounds
 * in the chunk it occupies. Client-side effect via WitherSilencerHandler.
 *
 * Recipe: silencer_post + nether_star (shapeless).
 */
public class WitherSilencerBlock extends BaseEntityBlock {

    private static final MapCodec<WitherSilencerBlock> CODEC = simpleCodec(WitherSilencerBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public WitherSilencerBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WitherSilencerBlockEntity(pos, state);
    }
}
