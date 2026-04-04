package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Nether Hopper block entity — regulator hopper.
 *
 * Regulator rule: count occupied target slots (= N), pull from hopper slot N,
 * skip if target already contains that item type. Inserts 1 item per cycle.
 *
 * Dual eject path: Container (slot-count-mapped) + Capabilities.Item.BLOCK fallback.
 * Never nests Transaction.openRoot() — check tx must be closed before insert tx.
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

    // ── Eject ─────────────────────────────────────────────────────────────

    @Override
    protected boolean tryEject(Level level, BlockPos pos, BlockPos targetPos) {
        var targetBe = level.getBlockEntity(targetPos);
        if (targetBe instanceof Container target) {
            return ejectSlotMapped(this, target);
        }
        ResourceHandler<ItemResource> cap = level.getCapability(
            Capabilities.Item.BLOCK, targetPos, facing.getOpposite());
        return cap != null && ejectCapability(this, cap);
    }

    /**
     * Fill-count regulator eject for Container targets.
     *
     * Strategy: count how many non-empty slots the target currently has (= N).
     * Pull from hopper slot N. Skip if hopper slot N is empty or target already
     * contains that item type. Inserts into first accepting empty target slot.
     */
    private static boolean ejectSlotMapped(NetherHopperBlockEntity be, Container target) {
        int n = countOccupied(target);
        if (n >= SIZE) return false;

        ItemStack hopperStack = be.items.get(n);
        if (hopperStack.isEmpty()) return false;
        if (containerHasItem(target, hopperStack)) return false;

        for (int i = 0; i < target.getContainerSize(); i++) {
            if (!target.getItem(i).isEmpty()) continue;
            if (!target.canPlaceItem(i, hopperStack)) continue;
            target.setItem(i, hopperStack.copyWithCount(1));
            be.removeItem(n, 1);
            target.setChanged();
            return true;
        }
        return false;
    }

    private static int countOccupied(Container target) {
        int count = 0;
        for (int i = 0; i < target.getContainerSize(); i++) {
            if (!target.getItem(i).isEmpty()) count++;
        }
        return count;
    }

    private static boolean containerHasItem(Container target, ItemStack stack) {
        for (int i = 0; i < target.getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(target.getItem(i), stack)) return true;
        }
        return false;
    }

    /**
     * Capability-based regulator eject for modded inventories that don't implement Container.
     *
     * Mirrors the Container path: determine N by counting how many consecutive hopper items
     * (starting from slot 0) are already present in the target. Stops at the first empty
     * hopper slot, then inserts from slot N.
     */
    private static boolean ejectCapability(NetherHopperBlockEntity be,
                                           ResourceHandler<ItemResource> dest) {
        int n = 0;
        for (int i = 0; i < SIZE; i++) {
            ItemStack s = be.items.get(i);
            if (s.isEmpty()) break;
            long found;
            try (var checkTx = Transaction.openRoot()) {
                found = dest.extract(ItemResource.of(s), 1, checkTx);
            } // aborts on close — nothing extracted
            if (found > 0) {
                n = i + 1;
            } else {
                break;
            }
        }

        if (n >= SIZE) return false;
        ItemStack stack = be.items.get(n);
        if (stack.isEmpty()) return false;

        try (var tx = Transaction.openRoot()) {
            int inserted = ResourceHandlerUtil.insertStacking(dest, ItemResource.of(stack), 1, tx);
            if (inserted > 0) {
                tx.commit();
                be.removeItem(n, inserted);
                return true;
            }
        }
        return false;
    }
}
