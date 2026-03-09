package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
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

/**
 * Mossy Hopper block entity.
 *
 * Inventory: 10 slots (two rows of 5).
 * Transfer: every 8 ticks, eject 1 item from each of 2 randomly chosen eligible slots
 *           (slots with count > 1). If only 1 eligible slot exists, both transfers come
 *           from it (provided count is still > 1 after the first).
 *           Never ejects the last item — a slot with count=1 is ineligible for ejection.
 */
public class MossyHopperBlockEntity extends RandomizableContainerBlockEntity implements Hopper {

    private static final int SIZE     = 10;
    private static final int COOLDOWN = 8;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int cooldownTime = -1;
    private Direction facing;

    MossyHopperBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.MOSSY_HOPPER_BE.get(), pos, state);
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
        return Component.translatable("container.wnir.mossy_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new MossyHopperMenu(id, playerInv, this);
    }

    // ── Tick ──────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, MossyHopperBlockEntity be) {
        be.cooldownTime--;
        if (be.cooldownTime > 0) return;
        be.cooldownTime = 0;

        if (!state.getValue(HopperBlock.ENABLED)) return;

        boolean moved = false;

        // Eject: up to 2 items from eligible (count > 1) slots to the attached container.
        Container dest = HopperBlockEntity.getContainerAt(level, pos.relative(be.facing));
        if (dest != null && !be.isEmpty()) {
            moved = ejectTwoItems(level, be, dest);
        }

        // Suck: pull 1 item from container above (or item entities).
        if (!be.inventoryFull()) {
            moved |= HopperBlockEntity.suckInItems(level, be);
        }

        if (moved) {
            be.cooldownTime = COOLDOWN;
            setChanged(level, pos, state);
        }
    }

    /**
     * Transfers up to 2 items from eligible slots (count > 1) to {@code dest}.
     * Re-evaluates eligibility before each transfer so a slot reduced to count=1
     * isn't transferred again.
     */
    private static boolean ejectTwoItems(Level level, MossyHopperBlockEntity be, Container dest) {
        Direction inbound = be.facing.getOpposite();
        boolean moved = false;

        for (int transfer = 0; transfer < 2; transfer++) {
            // Re-collect eligible slots each pass so counts are current.
            int eligibleCount = 0;
            for (ItemStack s : be.items) if (s.getCount() > 1) eligibleCount++;
            if (eligibleCount == 0) break;

            // Pick a random eligible slot.
            int pick = level.random.nextInt(eligibleCount);
            int slot = -1;
            for (int i = 0; i < SIZE; i++) {
                if (be.items.get(i).getCount() > 1 && pick-- == 0) { slot = i; break; }
            }
            if (slot < 0) break;

            ItemStack remainder = HopperBlockEntity.addItem(be, dest, be.removeItem(slot, 1), inbound);
            if (remainder.isEmpty()) {
                dest.setChanged();
                moved = true;
            } else {
                // Destination full — restore the item and stop.
                ItemStack existing = be.items.get(slot);
                if (existing.isEmpty()) be.setItem(slot, remainder);
                else existing.grow(remainder.getCount());
                break;
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
