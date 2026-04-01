package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Accumulator — passive FE energy storage block.
 *
 * - No active push/pull; external machines may freely insert and extract.
 * - Base capacity: 1 000 000 FE.
 * - Preserves stored energy and capacity on mine (dropped item carries BE data).
 * - Two or more accumulators can be crafted together to merge their capacity + energy.
 */
public class AccumulatorBlock extends BaseEntityBlock {

    private static final MapCodec<AccumulatorBlock> CODEC = simpleCodec(AccumulatorBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public AccumulatorBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AccumulatorBlockEntity(pos, state);
    }

    /**
     * Drops the accumulator with full BE state (energy + capacity) for all game modes.
     * Loot table is empty — this is the sole source of drops.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AccumulatorBlockEntity abe && !level.isClientSide()) {
            ItemStack stack = new ItemStack(this);
            // Build tag with CompoundTag.putLong directly — must match AccumulatorCombineRecipe
            // format so CompoundTag.getLong reads correctly in WnirBlockItem.accumulatorDataLines.
            // saveCustomOnly uses ValueOutput which is not readable by CompoundTag.getLong.
            CompoundTag tag = new CompoundTag();
            tag.putLong("capacity", abe.getCapacity());
            tag.putLong("energy", abe.getEnergy());
            stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(abe.getType(), tag));
            ItemEntity entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
