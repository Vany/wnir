package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * Mossy Hopper block entity.
 *
 * Transfer: every 8 ticks, eject 1 item from each of 2 randomly chosen eligible slots
 * (slots with count > 1). Never ejects the last item from any slot.
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

    // ── Eject ─────────────────────────────────────────────────────────────

    @Override
    protected boolean tryEject(Level level, BlockPos pos, BlockPos targetPos) {
        ResourceHandler<ItemResource> dest = level.getCapability(
            Capabilities.Item.BLOCK, targetPos, facing.getOpposite());
        return dest != null && ejectTwoItems(level, this, dest);
    }

    /**
     * Transfers up to 2 items from eligible slots (count > 1) to {@code dest}.
     * Re-evaluates eligibility before each transfer so a slot reduced to count=1
     * isn't transferred again.
     */
    private static boolean ejectTwoItems(Level level, MossyHopperBlockEntity be,
                                         ResourceHandler<ItemResource> dest) {
        boolean moved = false;
        for (int transfer = 0; transfer < 2; transfer++) {
            int eligibleCount = 0;
            for (ItemStack s : be.items) if (s.getCount() > 1) eligibleCount++;
            if (eligibleCount == 0) break;

            int pick = level.random.nextInt(eligibleCount);
            int slot = -1;
            for (int i = 0; i < SIZE; i++) {
                if (be.items.get(i).getCount() > 1 && pick-- == 0) { slot = i; break; }
            }
            if (slot < 0) break;

            ItemResource resource = ItemResource.of(be.items.get(slot));
            try (var tx = Transaction.openRoot()) {
                int inserted = ResourceHandlerUtil.insertStacking(dest, resource, 1, tx);
                if (inserted > 0) {
                    tx.commit();
                    be.removeItem(slot, inserted);
                    moved = true;
                } else {
                    break;
                }
            }
        }
        return moved;
    }
}
