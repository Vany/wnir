package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
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
 * Steel Hopper — high-throughput.
 *
 * Every 16 ticks: pull up to 4 items per slot from above, then push 4 iterations of
 * (up to 4 items per slot) to target. No last-item restriction.
 */
public class SteelHopperBlockEntity extends AbstractWnirHopperBlockEntity {

    private static final int ITERATIONS = 4;

    SteelHopperBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.STEEL_HOPPER_BE.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.wnir.steel_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new WnirHopperMenu(WnirRegistries.STEEL_HOPPER_MENU.get(), id, playerInv, this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SteelHopperBlockEntity be) {
        tick(level, pos, state, be);
    }

    @Override
    protected boolean doCycle(Level level, BlockPos pos) {
        boolean moved = pullAll(level, pos);

        // Capability pull fallback for non-Container sources (e.g. Sophisticated Backpacks)
        var sourceBe = level.getBlockEntity(pos.above());
        if (!(sourceBe instanceof Container)) {
            var srcCap = level.getCapability(Capabilities.Item.BLOCK, pos.above(), Direction.DOWN);
            if (srcCap != null) {
                int srcSize = srcCap.size();
                for (int srcIdx = 0; srcIdx < srcSize; srcIdx++) {
                    ItemResource resource = srcCap.getResource(srcIdx);
                    if (resource.isEmpty()) continue;
                    for (int hopperSlot = 0; hopperSlot < SIZE; hopperSlot++) {
                        ItemStack current = items.get(hopperSlot);
                        if (!current.isEmpty() && !ItemResource.of(current).equals(resource)) continue;
                        int canAccept = current.isEmpty()
                            ? resource.toStack(1).getMaxStackSize()
                            : current.getMaxStackSize() - current.getCount();
                        if (canAccept <= 0) continue;
                        int toTake = Math.min(canAccept, PER_SLOT);
                        try (var tx = Transaction.openRoot()) {
                            int extracted = srcCap.extract(srcIdx, resource, toTake, tx);
                            if (extracted > 0) {
                                tx.commit();
                                if (current.isEmpty()) items.set(hopperSlot, resource.toStack(extracted));
                                else current.grow(extracted);
                                moved = true;
                            }
                        }
                        break;
                    }
                }
            }
        }

        var dest = level.getCapability(Capabilities.Item.BLOCK, pos.relative(facing), facing.getOpposite());
        if (dest != null) {
            for (int iter = 0; iter < ITERATIONS; iter++) {
                for (int slot = 0; slot < SIZE; slot++) {
                    ItemStack stack = items.get(slot);
                    if (stack.isEmpty()) continue;
                    int toEject = Math.min(stack.getCount(), PER_SLOT);
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
        }

        return moved;
    }
}
