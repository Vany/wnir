package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;

/**
 * Opaque fluid tank — full-cube block that stores any single fluid type.
 *
 * - Right-click with a bucket/fluid container: fills or drains via FluidUtil.
 * - Right-click with empty hand: shows fluid name + amount in chat.
 * - Mine: drops itself with fluid and capacity preserved in BLOCK_ENTITY_DATA.
 * - Combine multiple tanks in a crafting grid to merge capacity and fluid.
 */
public class OpaqueTankBlock extends BaseEntityBlock {

    private static final MapCodec<OpaqueTankBlock> CODEC = simpleCodec(OpaqueTankBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public OpaqueTankBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OpaqueTankBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(
        ItemStack stack, BlockState state, Level level, BlockPos pos,
        Player player, InteractionHand hand, BlockHitResult hit
    ) {
        if (stack.isEmpty()) {
            return useWithoutItem(state, level, pos, player, hit);
        }
        // Only intercept fluid containers (buckets, pipes, etc.)
        if (ItemAccess.forStack(stack).oneByOne().getCapability(Capabilities.Fluid.ITEM) == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        boolean success = FluidUtil.interactWithFluidHandler(player, hand, level, pos, hit.getDirection());
        return success ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp
                && level.getBlockEntity(pos) instanceof OpaqueTankBlockEntity tank) {
            FluidResource res = tank.getFluidResource();
            long amount = tank.getFluidAmount();
            long cap = tank.getCapacity();
            Component msg;
            if (res.isEmpty() || amount == 0) {
                msg = Component.literal("Empty — " + cap + " mB capacity");
            } else {
                String name = res.getFluidType().getDescription().getString();
                msg = Component.literal(name + ": " + amount + " / " + cap + " mB");
            }
            sp.sendSystemMessage(msg);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Drops the tank with full BE state for all game modes.
     * Loot table is empty — this is the sole source of drops.
     *
     * Uses CompoundTag.putLong/putString directly (not saveCustomOnly/ValueOutput)
     * so the tag is readable by CompoundTag.getLong in the tooltip and combine recipe.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof OpaqueTankBlockEntity tank && !level.isClientSide()) {
            ItemStack stack = new ItemStack(this);
            CompoundTag tag = new CompoundTag();
            tag.putLong("capacity", tank.getCapacity());
            FluidResource res = tank.getFluidResource();
            long amount = tank.getFluidAmount();
            if (!res.isEmpty() && amount > 0) {
                Identifier key = BuiltInRegistries.FLUID.getKey(res.getFluid());
                if (key != null) {
                    tag.putString("fluid_id", key.toString());
                    tag.putLong("fluid_amount", amount);
                }
            }
            stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(tank.getType(), tag));
            ItemEntity entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
