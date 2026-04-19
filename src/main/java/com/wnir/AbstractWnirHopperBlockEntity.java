package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public abstract class AbstractWnirHopperBlockEntity
    extends RandomizableContainerBlockEntity implements Hopper {

    static final int SIZE     = 10;
    static final int COOLDOWN = 16;
    static final int PER_SLOT = 4;

    NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    int cooldownTime = -1;
    Direction facing;

    protected AbstractWnirHopperBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
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

    // ── Tick skeleton ─────────────────────────────────────────────────────

    /**
     * Run one transfer cycle: pull from above into all slots, push from all slots to target.
     * Each slot moves up to PER_SLOT items in and out. Returns true if any item moved.
     */
    protected abstract boolean doCycle(Level level, BlockPos pos);

    protected static void tick(Level level, BlockPos pos, BlockState state,
                               AbstractWnirHopperBlockEntity be) {
        be.cooldownTime--;
        if (be.cooldownTime > 0) return;
        be.cooldownTime = 0;

        if (!state.getValue(HopperBlock.ENABLED)) return;

        if (be.doCycle(level, pos)) {
            be.cooldownTime = COOLDOWN;
            setChanged(level, pos, state);
        }
    }

    // ── Pull helpers ──────────────────────────────────────────────────────

    /**
     * For each of the 10 hopper slots, pull up to PER_SLOT matching items from the
     * Container above. Source slot selection is unrestricted (any source slot).
     */
    protected boolean pullAll(Level level, BlockPos pos) {
        var sourceBe = level.getBlockEntity(pos.above());
        if (!(sourceBe instanceof Container source)) return false;
        boolean moved = false;
        for (int slot = 0; slot < SIZE; slot++) {
            moved |= pullIntoSlot(source, slot) > 0;
        }
        return moved;
    }

    /**
     * Pull up to PER_SLOT items into hopper slot {@code hopperSlot} from any slot in
     * {@code source}. Items must match the type already in the hopper slot (if occupied).
     * Respects WorldlyContainer face restrictions (taking from source's DOWN face).
     */
    protected int pullIntoSlot(Container source, int hopperSlot) {
        ItemStack current = items.get(hopperSlot);
        int canAccept = current.isEmpty()
            ? Integer.MAX_VALUE
            : current.getMaxStackSize() - current.getCount();
        if (canAccept <= 0) return 0;

        int toTake = Math.min(canAccept, PER_SLOT);
        int taken = 0;

        for (int i = 0; i < source.getContainerSize() && taken < toTake; i++) {
            ItemStack src = source.getItem(i);
            if (src.isEmpty()) continue;
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, src)) continue;
            if (source instanceof WorldlyContainer wc && !wc.canTakeItemThroughFace(i, src, Direction.DOWN)) continue;

            int amount = Math.min(toTake - taken,
                current.isEmpty()
                    ? Math.min(src.getCount(), src.getMaxStackSize())
                    : Math.min(src.getCount(), current.getMaxStackSize() - current.getCount()));
            if (amount <= 0) continue;

            source.removeItem(i, amount);
            if (current.isEmpty()) {
                items.set(hopperSlot, src.copyWithCount(amount));
                current = items.get(hopperSlot);
            } else {
                current.grow(amount);
            }
            taken += amount;
        }
        return taken;
    }

    // ── Hopper interface ──────────────────────────────────────────────────

    @Override public double getLevelX()      { return worldPosition.getX() + 0.5; }
    @Override public double getLevelY()      { return worldPosition.getY() + 0.5; }
    @Override public double getLevelZ()      { return worldPosition.getZ() + 0.5; }
    @Override public boolean isGridAligned() { return true; }
}
