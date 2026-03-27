package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 * Skull Beehive — a turret block that automatically shoots arrows at hostile mobs.
 *
 * Inventory (136 slots total):
 *   slots 0-5:   weapon slots (bows / crossbows)
 *   slot  6:     arrow receiver (immediately drains to arrow storage)
 *   slot  7:     gunpowder receiver (immediately drains to gunpowder storage)
 *   slots 8-71:  64 arrow storage slots   (max 1024 arrows total)
 *   slots 72-135:64 gunpowder storage slots (max 1024 gunpowder total)
 *
 * Craft: " S " / "SHS" / " S "   S = skeleton skull, H = beehive
 *
 * When mined the block drops itself with all contents preserved via the loot
 * table (copy_components on minecraft:block_entity_data).  Standard BlockItem
 * restores the data on placement — no custom BlockItem subclass needed.
 *
 * GeckoLib: reserved for future model + shooting animation — not wired yet.
 */
public class SkullBeehiveBlock extends BaseEntityBlock {

    private static final MapCodec<SkullBeehiveBlock> CODEC = simpleCodec(SkullBeehiveBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public SkullBeehiveBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SkullBeehiveBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        return level.isClientSide() ? null
            : createTickerHelper(type, WnirRegistries.SKULL_BEEHIVE_BE.get(),
                SkullBeehiveBlockEntity::serverTick);
    }

    /**
     * In creative mode vanilla skips the loot table, so we manually spawn the
     * item with its full component data (same pattern as ShulkerBoxBlock).
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SkullBeehiveBlockEntity sbbe && !level.isClientSide() && player.preventsBlockDrops()) {
            ItemStack stack = new ItemStack(this);
            stack.applyComponents(be.collectComponents());
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Sneak+right-click with empty hand: pick up into inventory. Normal right-click: open GUI. */
    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SkullBeehiveBlockEntity) {
                ItemStack stack = new ItemStack(this);
                stack.applyComponents(be.collectComponents());
                level.removeBlock(pos, false);
                if (!player.getInventory().add(stack)) {
                    // Inventory full — drop at player's feet
                    ItemEntity entity = new ItemEntity(level,
                        player.getX(), player.getY(), player.getZ(), stack);
                    entity.setDefaultPickUpDelay();
                    level.addFreshEntity(entity);
                }
            }
            return InteractionResult.CONSUME;
        }
        if (level.getBlockEntity(pos) instanceof SkullBeehiveBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.CONSUME;
    }
}
