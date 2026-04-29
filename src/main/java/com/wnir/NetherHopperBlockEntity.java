package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
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
 * Nether Hopper — slot-mapped regulator.
 *
 * Every 16 ticks: hopper slot N pulls from source slot N, pushes to target slot N.
 * Never ejects the last item from any slot (count must be > 1 to push).
 * Prefers Container (slot-indexed) for source/target; falls back to Capabilities.Item.BLOCK
 * for modded inventories that don't implement Container (e.g. Sophisticated Barrels).
 * Capability fallback uses per-slot matching (pull: same-type only; push: insertStacking).
 */
public class NetherHopperBlockEntity extends AbstractWnirHopperBlockEntity {

    NetherHopperBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.NETHER_HOPPER_BE.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.wnir.nether_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new WnirHopperMenu(WnirRegistries.NETHER_HOPPER_MENU.get(), id, playerInv, this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NetherHopperBlockEntity be) {
        tick(level, pos, state, be);
    }

    @Override
    protected boolean doCycle(Level level, BlockPos pos) {
        boolean moved = false;

        // ── Pull: hopper slot N ← source slot N ──────────────────────────
        var sourceBe = level.getBlockEntity(pos.above());
        if (sourceBe instanceof Container source) {
            boolean pulled = false;
            int limit = Math.min(SIZE, source.getContainerSize());
            for (int slot = 0; slot < limit; slot++) {
                ItemStack src = source.getItem(slot);
                if (src.isEmpty()) continue;
                if (source instanceof WorldlyContainer wc && !wc.canTakeItemThroughFace(slot, src, Direction.DOWN)) continue;

                ItemStack current = items.get(slot);
                if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, src)) continue;

                int canAccept = current.isEmpty()
                    ? src.getMaxStackSize()
                    : current.getMaxStackSize() - current.getCount();
                if (canAccept <= 0) continue;

                int amount = Math.min(Math.min(src.getCount(), canAccept), PER_SLOT);
                ItemStack toAdd = src.copyWithCount(amount);
                source.removeItem(slot, amount);
                if (current.isEmpty()) {
                    items.set(slot, toAdd);
                } else {
                    current.grow(amount);
                }
                pulled = true;
            }
            if (pulled) { source.setChanged(); moved = true; }
        } else {
            // Capability fallback: extract matching items into each occupied hopper slot.
            // Empty slots are skipped — we can't enumerate what's available in the source.
            var cap = level.getCapability(Capabilities.Item.BLOCK, pos.above(), Direction.DOWN);
            if (cap != null) {
                for (int slot = 0; slot < SIZE; slot++) {
                    ItemStack current = items.get(slot);
                    if (current.isEmpty()) continue;
                    int canAccept = current.getMaxStackSize() - current.getCount();
                    if (canAccept <= 0) continue;
                    int toTake = Math.min(canAccept, PER_SLOT);
                    try (var tx = Transaction.openRoot()) {
                        long extracted = cap.extract(ItemResource.of(current), toTake, tx);
                        if (extracted > 0) { tx.commit(); current.grow((int) extracted); moved = true; }
                    }
                }
            }
        }

        // ── Push: hopper slot N → target slot N, never last item ──────────
        var targetBe = level.getBlockEntity(pos.relative(facing));
        if (targetBe instanceof Container target) {
            boolean pushed = false;
            int limit = Math.min(SIZE, target.getContainerSize());
            Direction inFace = facing.getOpposite();
            for (int slot = 0; slot < limit; slot++) {
                ItemStack stack = items.get(slot);
                if (stack.getCount() <= 1) continue;
                if (!target.canPlaceItem(slot, stack)) continue;
                if (target instanceof WorldlyContainer wt && !wt.canPlaceItemThroughFace(slot, stack, inFace)) continue;

                ItemStack targetStack = target.getItem(slot);
                if (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(targetStack, stack)) continue;

                int canAccept = targetStack.isEmpty()
                    ? stack.getMaxStackSize()
                    : stack.getMaxStackSize() - targetStack.getCount();
                if (canAccept <= 0) continue;

                int amount = Math.min(Math.min(stack.getCount() - 1, canAccept), PER_SLOT);
                if (amount <= 0) continue;

                if (targetStack.isEmpty()) {
                    target.setItem(slot, stack.copyWithCount(amount));
                } else {
                    targetStack.grow(amount);
                }
                removeItem(slot, amount);
                pushed = true;
            }
            if (pushed) { target.setChanged(); moved = true; }
        } else {
            // Capability fallback: push each slot (count > 1) into target without slot mapping.
            var cap = level.getCapability(Capabilities.Item.BLOCK, pos.relative(facing), facing.getOpposite());
            if (cap != null) {
                for (int slot = 0; slot < SIZE; slot++) {
                    ItemStack stack = items.get(slot);
                    if (stack.getCount() <= 1) continue;
                    int toEject = Math.min(stack.getCount() - 1, PER_SLOT);
                    try (var tx = Transaction.openRoot()) {
                        int inserted = ResourceHandlerUtil.insertStacking(cap, ItemResource.of(stack), toEject, tx);
                        if (inserted > 0) { tx.commit(); removeItem(slot, inserted); moved = true; }
                    }
                }
            }
        }

        return moved;
    }
}
