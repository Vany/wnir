package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public class ChunkLoaderBlock extends Block {

    private static final MapCodec<ChunkLoaderBlock> CODEC = simpleCodec(ChunkLoaderBlock::new);

    @Override
    protected MapCodec<ChunkLoaderBlock> codec() { return CODEC; }

    public ChunkLoaderBlock(BlockBehaviour.Properties props) {
        super(props);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            ChunkLoaderData.get(serverLevel).add(pos);
            forceChunk(serverLevel, pos, true);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel) {
            ChunkLoaderData.get(serverLevel).remove(pos);
            forceChunk(serverLevel, pos, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    static void forceChunk(ServerLevel level, BlockPos pos, boolean add) {
        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        level.setChunkForced(chunkPos.x(), chunkPos.z(), add);
    }
}
