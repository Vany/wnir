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
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Shared base for EEClockBuddingCrystalBlockEntity and TeleporterCrystalBlockEntity.
 *
 * Growth ticks accumulate when EEClock blocks are below and pearl fuel is available.
 * At BASE_TICKS the block transforms into a subclass-defined target block.
 * If removed from the EEClock column the block reverts to a subclass-defined fallback.
 *
 * Pearl fuel: each pearl provides PEARL_FUEL_TICKS ticks burned at `clocks` per game tick.
 * TRANSFORM_PEARLS extra pearls are consumed at the moment of transformation.
 */
public abstract class GrowingCrystalBlockEntity extends BlockEntity implements MenuProvider {

    static final int BASE_TICKS       = 168_000; // 7 days × 24000 ticks/day
    static final int PEARL_FUEL_TICKS = 12_000;  // half a day per pearl
    static final int TRANSFORM_PEARLS = 2;       // extra pearls consumed on final transform

    private int ticksAccumulated = 0;
    private int currentPearlFuel = 0;

    private int cachedClocks = -1;
    private int clocksTimer  = 0;
    private static final int CLOCKS_RECHECK = 40;

    /** Single-slot container for ender pearls. */
    final SimpleContainer pearlContainer = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            GrowingCrystalBlockEntity.this.setChanged();
        }
    };

    protected GrowingCrystalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Abstract ──────────────────────────────────────────────────────────

    /** BlockState to place when growth completes. */
    protected abstract BlockState transformState();

    /** BlockState to revert to when placed without an EEClock column below. */
    protected abstract BlockState revertState();

    /** MenuType for GrowingCrystalMenu registration. */
    protected abstract MenuType<GrowingCrystalMenu> menuType();

    /** Display name for GUI title. */
    @Override
    public abstract Component getDisplayName();

    // ── Tick ─────────────────────────────────────────────────────────────

    protected static void tick(Level level, BlockPos pos, BlockState state,
                                GrowingCrystalBlockEntity be) {
        if (++be.clocksTimer >= CLOCKS_RECHECK || be.cachedClocks < 0) {
            be.clocksTimer  = 0;
            be.cachedClocks = countEEClocksBelow(level, pos);
        }
        int clocks = be.cachedClocks;
        if (clocks == 0) return;

        if (be.currentPearlFuel <= 0) {
            ItemStack pearl = be.pearlContainer.getItem(0);
            if (pearl.is(Items.ENDER_PEARL) && !pearl.isEmpty()) {
                pearl.shrink(1);
                be.pearlContainer.setItem(0, pearl.isEmpty() ? ItemStack.EMPTY : pearl);
                be.currentPearlFuel = PEARL_FUEL_TICKS;
                be.setChanged();
            } else {
                return; // no fuel → pause
            }
        }
        be.currentPearlFuel -= clocks;
        be.ticksAccumulated += clocks;
        be.setChanged();

        if (be.ticksAccumulated >= BASE_TICKS) {
            ItemStack pearl = be.pearlContainer.getItem(0);
            int have = pearl.is(Items.ENDER_PEARL) ? pearl.getCount() : 0;
            if (have < TRANSFORM_PEARLS) return; // stall — wait for pearls
            pearl.shrink(TRANSFORM_PEARLS);
            be.pearlContainer.setItem(0, pearl.isEmpty() ? ItemStack.EMPTY : pearl);
            level.setBlock(pos, be.transformState(), 3);
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
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        ContainerData syncedData = new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case 0 -> (int)((long) ticksAccumulated * 10000 / BASE_TICKS);
                    case 1 -> cachedClocks < 0 ? countEEClocksBelow(level, worldPosition) : cachedClocks;
                    case 2 -> currentPearlFuel * 1000 / PEARL_FUEL_TICKS;
                    default -> 0;
                };
            }
            @Override public void set(int i, int v) {}
            @Override public int getCount() { return 3; }
        };
        return new GrowingCrystalMenu(menuType(), id, inv, syncedData, pearlContainer);
    }

    // ── Persistence ───────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ticksAccumulated = input.getIntOr("ticks", 0);
        currentPearlFuel = input.getIntOr("pearlFuel", 0);
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        pearlContainer.setItem(0, items.get(0));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("ticks", ticksAccumulated);
        output.putInt("pearlFuel", currentPearlFuel);
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        items.set(0, pearlContainer.getItem(0));
        ContainerHelper.saveAllItems(output, items);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            cachedClocks = countEEClocksBelow(serverLevel, worldPosition);
            if (cachedClocks == 0) {
                serverLevel.setBlock(worldPosition, revertState(), 3);
            }
        }
    }
}
