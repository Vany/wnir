package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class ZygoteBlockEntity extends BlockEntity implements MenuProvider {

    private int ticksAccumulated = 0;
    private int fuelConsumedSoFar = 0;
    private int fuelBuffer = 0;

    private int cachedClocks = -1;
    private int clocksTimer  = 0;
    private static final int CLOCKS_RECHECK = 40;

    final SimpleContainer fuelContainer = new SimpleContainer(1) {
        @Override public void setChanged() {
            super.setChanged();
            ZygoteBlockEntity.this.setChanged();
        }
    };

    public ZygoteBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.zygoteBeType(((ZygoteBlock) state.getBlock()).variant), pos, state);
    }

    ZygoteVariant variant() { return ((ZygoteBlock) getBlockState().getBlock()).variant; }

    // ── Tick ─────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, ZygoteBlockEntity be) {
        if (++be.clocksTimer >= CLOCKS_RECHECK || be.cachedClocks < 0) {
            be.clocksTimer  = 0;
            be.cachedClocks = countEEClocksBelow(level, pos);
        }
        int clocks = be.cachedClocks;

        ZygoteVariant v         = be.variant();
        int           baseTicks = v.baseTicks;
        int           totalFuel = v.fuelCount;

        // No-fuel dust warning: 8× base rate (1/25 per tick), shown even without clocks
        if (totalFuel > 0 && level instanceof ServerLevel slDust) {
            ItemStack fuelSlot = be.fuelContainer.getItem(0);
            if ((fuelSlot.isEmpty() || !fuelSlot.is(v.fuelItem)) && slDust.getRandom().nextInt(25) == 0) {
                slDust.sendParticles(new DustParticleOptions(0xFF8000, 1.0f),
                    pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    1, 0.3, 0.2, 0.3, 0.0);
            }
        }

        if (clocks == 0) return;

        if (totalFuel == 0) {
            be.ticksAccumulated = Math.min(be.ticksAccumulated + clocks, baseTicks);
            be.setChanged();
        } else {
            // Buffer model: consume 1 item → gain period ticks; drain buffer for progress.
            // Ceiling division guarantees totalFuel items always covers baseTicks.
            int     period   = (baseTicks + totalFuel - 1) / totalFuel;
            int     remaining = clocks;
            boolean changed   = false;
            while (remaining > 0 && be.ticksAccumulated < baseTicks) {
                if (be.fuelBuffer == 0) {
                    ItemStack slot = be.fuelContainer.getItem(0);
                    if (slot.isEmpty() || !slot.is(v.fuelItem)) return;
                    slot.shrink(1);
                    be.fuelContainer.setItem(0, slot.isEmpty() ? ItemStack.EMPTY : slot);
                    be.fuelConsumedSoFar++;
                    be.fuelBuffer = period;
                    changed = true;
                }
                int advance = Math.min(remaining, Math.min(be.fuelBuffer, baseTicks - be.ticksAccumulated));
                be.ticksAccumulated += advance;
                be.fuelBuffer       -= advance;
                remaining           -= advance;
                changed = true;
            }
            if (changed) be.setChanged();
        }

        if (be.ticksAccumulated >= baseTicks) {
            level.setBlock(pos, v.targetBlock().defaultBlockState(), 3);
            return;
        }

        // Working particles: rate scales with clocks (base 1/200 per tick, doubled per extra clock)
        if (level instanceof ServerLevel sl && sl.getRandom().nextInt(Math.max(1, 200 / clocks)) == 0) {
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                1, 0.3, 0.2, 0.3, 0.0);
        }
    }

    static int countEEClocksBelow(Level level, BlockPos pos) {
        int count = 0;
        BlockPos check = pos.below();
        while (level.getBlockState(check).getBlock() instanceof EEClockBlock) {
            count++;
            check = check.below();
        }
        return count;
    }

    // ── MenuProvider ─────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        ZygoteVariant v        = variant();
        int           baseTicks = v.baseTicks;
        int           totalFuel = v.fuelCount;
        ContainerData syncedData = new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case 0 -> (int)((long) ticksAccumulated * 10000 / baseTicks);
                    case 1 -> cachedClocks < 0 ? countEEClocksBelow(level, worldPosition) : cachedClocks;
                    case 2 -> totalFuel > 0 ? fuelConsumedSoFar * 1000 / totalFuel : 0;
                    case 3 -> baseTicks / 24;
                    default -> 0;
                };
            }
            @Override public void set(int i, int val) {}
            @Override public int getCount() { return 4; }
        };
        return new ZygoteMenu(WnirRegistries.zygoteMenuType(v), id, inv, syncedData, fuelContainer, v);
    }

    // ── Persistence ───────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ticksAccumulated  = input.getIntOr("ticks", 0);
        fuelConsumedSoFar = input.getIntOr("fuelConsumed", 0);
        fuelBuffer        = input.getIntOr("fuelBuffer", 0);
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        fuelContainer.setItem(0, items.get(0));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("ticks", ticksAccumulated);
        output.putInt("fuelConsumed", fuelConsumedSoFar);
        output.putInt("fuelBuffer", fuelBuffer);
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        items.set(0, fuelContainer.getItem(0));
        ContainerHelper.saveAllItems(output, items);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            cachedClocks = countEEClocksBelow(serverLevel, worldPosition);
            if (cachedClocks == 0) {
                serverLevel.setBlock(worldPosition, variant().revertBlock.defaultBlockState(), 3);
            }
        }
    }
}
