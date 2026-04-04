package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Shared base for all 10-slot WNIR hopper block entities.
 *
 * Handles: inventory fields, NBT, Container boilerplate, facing, inventoryFull,
 * Hopper interface, and the outer serverTick skeleton (cooldown + enabled + suck).
 * Each subclass supplies its name, menu, and eject logic via abstract methods.
 */
public abstract class AbstractWnirHopperBlockEntity
    extends RandomizableContainerBlockEntity implements Hopper {

    static final int SIZE     = 10;
    static final int COOLDOWN = 8;

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
     * Try to eject one item from this hopper into the block at {@code targetPos}.
     * Called once per tick cycle when the hopper is not empty.
     * @return true if an item was moved
     */
    protected abstract boolean tryEject(Level level, BlockPos pos, BlockPos targetPos);

    protected static void tick(Level level, BlockPos pos, BlockState state,
                               AbstractWnirHopperBlockEntity be) {
        be.cooldownTime--;
        if (be.cooldownTime > 0) return;
        be.cooldownTime = 0;

        if (!state.getValue(HopperBlock.ENABLED)) return;

        boolean moved = false;

        if (!be.isEmpty()) {
            moved = be.tryEject(level, pos, pos.relative(be.facing));
        }

        if (!be.inventoryFull()) {
            moved |= HopperBlockEntity.suckInItems(level, be);
        }

        if (moved) {
            be.cooldownTime = COOLDOWN;
            setChanged(level, pos, state);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    boolean inventoryFull() {
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
