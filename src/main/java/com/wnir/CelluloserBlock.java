package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Celluloser — converts enchanted books + water + FE into magic cellulose fluid.
 *
 * Input:  water (16 buckets tank) + enchanted book (1 slot) + FE (1M buffer)
 * Output: magic cellulose fluid (16 buckets tank)
 *
 * Craft: "EBE" / "SLS" / "GEG"
 *   E = emerald, B = brush, S = shears, L = lectern, G = gold_ingot
 *
 * Block state (including all fluids, energy, and progress) is preserved on mine
 * via copy_components on minecraft:block_entity_data in the loot table.
 */
public class CelluloserBlock extends BaseEntityBlock {

    private static final MapCodec<CelluloserBlock> CODEC = simpleCodec(CelluloserBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public CelluloserBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CelluloserBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        return level.isClientSide() ? null
            : createTickerHelper(type, WnirRegistries.CELLULOSER_BE.get(),
                CelluloserBlockEntity::serverTick);
    }

    /**
     * Drops the celluloser with all BE state (fluids, energy, book, progress) for all game modes.
     * Runs while the block entity is still alive, so collectComponents() captures current state.
     * Loot table is empty — this method is the sole source of drops.
     * Creative players get the item too (consistent with other stateful blocks like shulker boxes).
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CelluloserBlockEntity cbe && !level.isClientSide()) {
            ItemStack stack = new ItemStack(this);
            // saveCustomOnly serializes our saveAdditional data into a CompoundTag.
            // BlockItem.updateCustomBlockEntityTag reads BLOCK_ENTITY_DATA back via
            // CustomData.loadInto → loadAdditional when the block is placed.
            CompoundTag tag = cbe.saveCustomOnly(level.registryAccess());
            if (!tag.isEmpty()) {
                stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(cbe.getType(), tag));
            }
            ItemEntity entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Right-click opens the GUI. */
    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof CelluloserBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.CONSUME;
    }
}
