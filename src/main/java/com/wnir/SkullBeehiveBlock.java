package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /** Arrows pass through the beehive — mobs and players still collide normally. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (ctx instanceof EntityCollisionContext ecc && ecc.getEntity() instanceof AbstractArrow) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, ctx);
    }

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

    /** Record the placing player's UUID so arrows are attributed to them for kill-credit. */
    @Override
    public void setPlacedBy(
        Level level, BlockPos pos, BlockState state,
        @Nullable LivingEntity placer, ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
            && level.getBlockEntity(pos) instanceof SkullBeehiveBlockEntity be) {
            be.setOwnerUUID(player.getUUID());
        }
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
            CompoundTag tag = be.saveCustomOnly(level.registryAccess());
            stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(be.getType(), tag));
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
                CompoundTag tag = be.saveCustomOnly(level.registryAccess());
                stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(be.getType(), tag));
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
