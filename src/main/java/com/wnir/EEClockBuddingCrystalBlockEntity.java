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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Block entity for EEClockBuddingCrystalBlock.
 *
 * Growth requires both EEClock blocks below AND ender pearl fuel in the slot.
 *
 * Pearl fuel mechanic (furnace-style):
 *   PEARL_FUEL_TICKS = 12000 (half a Minecraft day per pearl)
 *   When currentPearlFuel runs out, consume 1 ender pearl → gain 12000 fuel ticks.
 *   If no pearl available: growth pauses.
 *
 * With 1 EEClock and BASE_TICKS = 168 000:
 *   168 000 / 12 000 = 14 pearls consumed as fuel during growth.
 *   At transformation: 2 additional pearls consumed from the slot.
 *   Total = 16 pearls = one full stack.
 *
 * With N EEClocks: crystal finishes in 168 000 / N real ticks → fewer pearls needed.
 */
public class EEClockBuddingCrystalBlockEntity extends BlockEntity implements MenuProvider {

    static final int BASE_TICKS        = 168_000; // 7 days × 24000 ticks/day
    static final int PEARL_FUEL_TICKS  = 12_000;  // half a day per pearl
    static final int TRANSFORM_PEARLS  = 2;       // extra pearls consumed on final transform

    private int ticksAccumulated = 0;
    private int currentPearlFuel = 0;

    /** Single-slot container for ender pearls, notifies this BE on change. */
    final SimpleContainer pearlContainer = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            EEClockBuddingCrystalBlockEntity.this.setChanged();
        }
    };

    public EEClockBuddingCrystalBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_BE.get(), pos, state);
    }

    // ── Tick ─────────────────────────────────────────────────────────────

    public static void serverTick(
        Level level, BlockPos pos, BlockState state, EEClockBuddingCrystalBlockEntity be
    ) {
        int clocks = countEEClocksBelow(level, pos);
        if (clocks == 0) return;

        // Burn `clocks` fuel ticks per game tick so total pearl consumption
        // stays constant at 14 fuel pearls regardless of column height.
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
            // Consume 2 transformation pearls; stall if not available
            ItemStack pearl = be.pearlContainer.getItem(0);
            int have = pearl.is(Items.ENDER_PEARL) ? pearl.getCount() : 0;
            if (have < TRANSFORM_PEARLS) return; // wait for pearls
            pearl.shrink(TRANSFORM_PEARLS);
            be.pearlContainer.setItem(0, pearl.isEmpty() ? ItemStack.EMPTY : pearl);
            level.setBlock(pos, WnirRegistries.EE_CLOCK_BLOCK.get().defaultBlockState(), 3);
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
        return Component.translatable("block.wnir.ee_clock_budding_crystal");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        ContainerData syncedData = new ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case 0 -> (int)((long) ticksAccumulated * 10000 / BASE_TICKS);
                    case 1 -> countEEClocksBelow(level, worldPosition);
                    case 2 -> currentPearlFuel * 1000 / PEARL_FUEL_TICKS;
                    default -> 0;
                };
            }
            @Override public void set(int i, int v) {}
            @Override public int getCount() { return 3; }
        };
        return new EEClockBuddingCrystalMenu(id, inv, syncedData, pearlContainer);
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
            if (countEEClocksBelow(serverLevel, worldPosition) == 0) {
                serverLevel.setBlock(worldPosition,
                    net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST.defaultBlockState(), 3);
            }
        }
    }
}
