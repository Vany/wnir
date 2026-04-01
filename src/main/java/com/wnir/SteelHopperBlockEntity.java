package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Steel Hopper block entity.
 *
 * Inventory: 10 slots (two rows of 5).
 * Transfer: every 8 ticks, eject up to 8 items to the output container.
 * Vanilla behavior — no "never empty slot" restriction.
 */
public class SteelHopperBlockEntity extends RandomizableContainerBlockEntity implements Hopper {

    private static final int SIZE     = 10;
    private static final int COOLDOWN = 8;
    /** Items to transfer per cycle (vs vanilla 1, mossy 2). */
    private static final int TRANSFER_PER_CYCLE = 8;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int cooldownTime = -1;
    private Direction facing;

    SteelHopperBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.STEEL_HOPPER_BE.get(), pos, state);
        this.facing = state.getValue(HopperBlock.FACING);
    }

    // ── NBT ───────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        if (!tryLoadLootTable(input)) ContainerHelper.loadAllItems(input, items);
        cooldownTime = input.getIntOr("TransferCooldown", -1);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!trySaveLootTable(output)) ContainerHelper.saveAllItems(output, items);
        output.putInt("TransferCooldown", cooldownTime);
    }

    // ── Container ─────────────────────────────────────────────────────────

    @Override public int getContainerSize()                       { return SIZE; }
    @Override protected NonNullList<ItemStack> getItems()         { return items; }
    @Override protected void setItems(NonNullList<ItemStack> i)   { items = i; }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        facing = state.getValue(HopperBlock.FACING);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.wnir.steel_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new SteelHopperMenu(id, playerInv, this);
    }

    // ── Tick ──────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, SteelHopperBlockEntity be) {
        be.cooldownTime--;
        if (be.cooldownTime > 0) return;
        be.cooldownTime = 0;

        if (!state.getValue(HopperBlock.ENABLED)) return;

        boolean moved = false;

        // Eject: up to TRANSFER_PER_CYCLE items to the attached container.
        // Use IItemHandler capability so modded inventories (e.g. Sophisticated Storage) work.
        if (!be.isEmpty()) {
            ResourceHandler<ItemResource> dest = level.getCapability(
                Capabilities.Item.BLOCK,
                pos.relative(be.facing),
                be.facing.getOpposite()
            );
            if (dest != null) moved = ejectItems(be, dest);
        }

        // Suck: pull items from container above (or item entities).
        if (!be.inventoryFull()) {
            moved |= HopperBlockEntity.suckInItems(level, be);
        }

        if (moved) {
            be.cooldownTime = COOLDOWN;
            setChanged(level, pos, state);
        }
    }

    /**
     * Transfers up to TRANSFER_PER_CYCLE items from this hopper to {@code dest}.
     * Uses IItemHandler so both vanilla and modded inventories are supported.
     */
    private static boolean ejectItems(SteelHopperBlockEntity be, ResourceHandler<ItemResource> dest) {
        boolean moved = false;
        int transferred = 0;

        for (int slot = 0; slot < SIZE && transferred < TRANSFER_PER_CYCLE; slot++) {
            ItemStack stack = be.items.get(slot);
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
                    break; // destination full — no point trying further slots
                }
            }
        }

        return moved;
    }

    private boolean inventoryFull() {
        for (ItemStack s : items) {
            if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) return false;
        }
        return true;
    }

    // ── Hopper interface ──────────────────────────────────────────────────

    @Override public double getLevelX()      { return worldPosition.getX() + 0.5; }
    @Override public double getLevelY()      { return worldPosition.getY() + 0.5; }
    @Override public double getLevelZ()      { return worldPosition.getZ() + 0.5; }
    @Override public boolean isGridAligned() { return true; }
}
