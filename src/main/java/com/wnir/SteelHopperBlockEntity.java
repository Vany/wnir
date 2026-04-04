package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Steel Hopper block entity.
 *
 * Transfer: every 8 ticks, eject up to 8 items to the output container.
 * No slot-lock restriction.
 */
public class SteelHopperBlockEntity extends AbstractWnirHopperBlockEntity {

    private static final int TRANSFER_PER_CYCLE = 8;

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

    // ── Eject ─────────────────────────────────────────────────────────────

    @Override
    protected boolean tryEject(Level level, BlockPos pos, BlockPos targetPos) {
        ResourceHandler<ItemResource> dest = level.getCapability(
            Capabilities.Item.BLOCK, targetPos, facing.getOpposite());
        return dest != null && ejectItems(this, dest);
    }

    /**
     * Transfers up to TRANSFER_PER_CYCLE items sequentially from this hopper to {@code dest}.
     */
    private static boolean ejectItems(SteelHopperBlockEntity be, ResourceHandler<ItemResource> dest) {
        boolean moved = false;
        int transferred = 0;

        for (int slot = 0; slot < SIZE && transferred < TRANSFER_PER_CYCLE; slot++) {
            var stack = be.items.get(slot);
            if (stack.isEmpty()) continue;

            int amount = Math.min(stack.getCount(), TRANSFER_PER_CYCLE - transferred);
            ItemResource resource = ItemResource.of(stack);
            try (var tx = Transaction.openRoot()) {
                int inserted = ResourceHandlerUtil.insertStacking(dest, resource, amount, tx);
                if (inserted > 0) {
                    tx.commit();
                    be.removeItem(slot, inserted);
                    moved = true;
                    transferred += inserted;
                } else {
                    break;
                }
            }
        }
        return moved;
    }
}
