package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Mossy Hopper — item sorter.
 *
 * Every 16 ticks: pull up to 4 items per slot from above, push up to 4 items per slot to target.
 * Never ejects the last item from any slot (count must be > 1 to push).
 */
public class MossyHopperBlockEntity extends AbstractWnirHopperBlockEntity {

    MossyHopperBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.MOSSY_HOPPER_BE.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.wnir.mossy_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new WnirHopperMenu(WnirRegistries.MOSSY_HOPPER_MENU.get(), id, playerInv, this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MossyHopperBlockEntity be) {
        tick(level, pos, state, be);
    }

    @Override
    protected boolean doCycle(Level level, BlockPos pos) {
        boolean moved = pullAll(level, pos);

        var dest = level.getCapability(Capabilities.Item.BLOCK, pos.relative(facing), facing.getOpposite());
        if (dest != null) {
            for (int slot = 0; slot < SIZE; slot++) {
                ItemStack stack = items.get(slot);
                if (stack.getCount() <= 1) continue;
                int toEject = Math.min(stack.getCount() - 1, PER_SLOT);
                try (var tx = Transaction.openRoot()) {
                    int inserted = ResourceHandlerUtil.insertStacking(dest, ItemResource.of(stack), toEject, tx);
                    if (inserted > 0) {
                        tx.commit();
                        removeItem(slot, inserted);
                        moved = true;
                    }
                }
            }
        }

        return moved;
    }
}
