package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

public class ZygoteBlock extends BaseEntityBlock {

    public final ZygoteVariant variant;
    private final MapCodec<ZygoteBlock> myCodec;

    public ZygoteBlock(ZygoteVariant variant, Properties props) {
        super(props);
        this.variant = variant;
        this.myCodec = MapCodec.unit(this);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return myCodec; }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ZygoteBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, WnirRegistries.zygoteBeType(variant), ZygoteBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ZygoteBlockEntity be) player.openMenu(be);
        return InteractionResult.CONSUME;
    }

    /** Called from EEClockBlock.onPlace — transform any matching block sitting above the clock. */
    static void tryTransformAbove(Level level, BlockPos clockPos) {
        if (level.isClientSide()) return;
        BlockPos above = clockPos.above();
        Block aboveBlock = level.getBlockState(above).getBlock();
        for (ZygoteVariant v : ZygoteVariant.values()) {
            if (aboveBlock == v.revertBlock) {
                level.setBlock(above, WnirRegistries.zygoteBlock(v).defaultBlockState(), 3);
                return;
            }
        }
    }

    /** Called from WnirMod.onBlockPlaced — transform the newly-placed block if it sits on a clock. */
    static void tryTransformAt(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        Block placed = level.getBlockState(pos).getBlock();
        for (ZygoteVariant v : ZygoteVariant.values()) {
            if (placed == v.revertBlock
                    && level.getBlockState(pos.below()).getBlock() instanceof EEClockBlock) {
                level.setBlock(pos, WnirRegistries.zygoteBlock(v).defaultBlockState(), 3);
                return;
            }
        }
    }
}
