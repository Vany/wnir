package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Celluloser block entity.
 *
 * ── Configurable parameters (edit the public static fields) ─────────────────
 *   XP_PER_TICK    = 200   XP processed per server tick
 *   FE_PER_XP      = 100   FE consumed per XP point
 *   WATER_PER_XP   = 1     mB of water consumed per XP point (W = XP)
 *   OUTPUT_DIVISOR = 10    XP / divisor = mB of magic cellulose produced
 *   TANK_CAPACITY  = 16000 max mB per tank (water in / cellulose out)
 *   ENERGY_CAPACITY= 1_000_000  max FE in buffer
 *
 * ── Processing ───────────────────────────────────────────────────────────────
 *   1. Consume enchanted book → calculate total XP via enchantment min-cost formula
 *   2. Each tick: process min(remainingXp, XP_PER_TICK) XP if resources available
 *   3. Pause (preserve progress) when energy or water runs out
 *
 * ── Capabilities ─────────────────────────────────────────────────────────────
 *   Energy: all faces insert; no extraction
 *   Fluid:  all faces → insert water (tank 0); all faces → extract cellulose (tank 1)
 */
public class CelluloserBlockEntity extends BlockEntity implements Container, net.minecraft.world.MenuProvider {

    // ── Configurable parameters ──────────────────────────────────────────────
    public static int XP_PER_TICK     = 200;
    public static int FE_PER_XP       = 100;
    public static int WATER_PER_XP    = 1;
    public static int OUTPUT_DIVISOR  = 10;
    public static int TANK_CAPACITY   = 16_000;
    public static int ENERGY_CAPACITY = 1_000_000;

    // ── State ────────────────────────────────────────────────────────────────

    private NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
    private int remainingXp = 0;
    private int totalXp     = 0;

    /**
     * Two-tank fluid handler.
     *   Slot 0 = water input   — external insert allowed (water only), external extract disallowed
     *   Slot 1 = cellulose out — external extract allowed, external insert disallowed
     */
    final FluidStacksResourceHandler fluidHandler = new FluidStacksResourceHandler(2, TANK_CAPACITY) {
        @Override
        public boolean isValid(int slot, FluidResource resource) {
            if (resource.isEmpty()) return false;
            return switch (slot) {
                case 0 -> resource.getFluid() == Fluids.WATER;
                // slot 1: only the machine tick may insert (via set); external insert blocked
                default -> false;
            };
        }
        /** Block external extraction from slot 0 (water input). Only slot 1 (cellulose) may be extracted. */
        @Override
        public int extract(int slot, FluidResource resource, int amount,
                net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (slot == 0) return 0;
            return super.extract(slot, resource, amount, transaction);
        }
        @Override
        protected void onContentsChanged(int slot, FluidStack previous) {
            CelluloserBlockEntity.this.setChanged();
        }
    };

    /** Energy buffer — external insert only, no extraction. */
    final SimpleEnergyHandler energyHandler = new SimpleEnergyHandler(
        ENERGY_CAPACITY, ENERGY_CAPACITY, 0
    ) {
        @Override
        protected void onEnergyChanged(int delta) {
            CelluloserBlockEntity.this.setChanged();
        }
    };

    // ── ContainerData (synced to client via menu) ────────────────────────────

    final ContainerData syncData = new ContainerData() {
        @Override
        public int get(int i) {
            int fe = energyHandler.getAmountAsInt();
            return switch (i) {
                case 0 -> fe & 0xFFFF;               // energy low 16 bits
                case 1 -> (fe >> 16) & 0xFFFF;       // energy high bits
                case 2 -> (int) fluidHandler.getAmountAsLong(0); // water mB
                case 3 -> (int) fluidHandler.getAmountAsLong(1); // cellulose mB
                case 4 -> Math.min(remainingXp, 32767); // progress numerator
                case 5 -> Math.min(totalXp, 32767);     // progress denominator
                default -> 0;
            };
        }
        @Override public void set(int i, int value) {}  // server-only data
        @Override public int getCount() { return 6; }
    };

    // ── Construction ─────────────────────────────────────────────────────────

    public CelluloserBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.CELLULOSER_BE.get(), pos, state);
    }

    // ── NBT ─────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        energyHandler.deserialize(input.childOrEmpty("Energy"));
        fluidHandler.deserialize(input.childOrEmpty("Fluids"));
        remainingXp = input.getIntOr("RemainingXp", 0);
        totalXp     = input.getIntOr("TotalXp",     0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items, false);
        energyHandler.serialize(output.child("Energy"));
        fluidHandler.serialize(output.child("Fluids"));
        output.putInt("RemainingXp", remainingXp);
        output.putInt("TotalXp",     totalXp);
    }

    // ── Container ────────────────────────────────────────────────────────────

    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty()       { return items.get(0).isEmpty(); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = ContainerHelper.removeItem(items, slot, amount);
        if (!stack.isEmpty()) setChanged();
        return stack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() { items.clear(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return isEnchanted(stack) || isConfigSource(stack);
    }

    static boolean isConfigSource(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && CelluloserConfig.EXTRA_SOURCES.containsKey(id);
    }

    static boolean isEnchanted(ItemStack stack) {
        var enc = stack.get(DataComponents.ENCHANTMENTS);
        if (enc != null && !enc.isEmpty()) return true;
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    // ── MenuProvider ─────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.wnir.celluloser");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new CelluloserMenu(id, playerInv, this, syncData);
    }

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, CelluloserBlockEntity be) {
        boolean changed = false;

        // Consume item → calculate total XP (enchanted book or config source)
        if (be.remainingXp == 0) {
            ItemStack input = be.items.get(0);
            if (!input.isEmpty()) {
                int xp = 0;
                if (isEnchanted(input)) {
                    xp = calcItemXp(input);
                } else {
                    Identifier id = BuiltInRegistries.ITEM.getKey(input.getItem());
                    if (id != null) {
                        xp = CelluloserConfig.EXTRA_SOURCES.getOrDefault(id, 0);
                    }
                }
                if (xp > 0) {
                    input.shrink(1);
                    be.remainingXp = xp;
                    be.totalXp     = xp;
                    changed = true;
                }
            }
        }

        // Process XP — pauses automatically when resources are absent
        if (be.remainingXp > 0) {
            int tickXp  = Math.min(be.remainingXp, XP_PER_TICK);
            int feNeeded = tickXp * FE_PER_XP;
            int wNeeded  = tickXp * WATER_PER_XP;
            int cellOut  = tickXp / OUTPUT_DIVISOR;

            boolean hasEnergy = be.energyHandler.getAmountAsInt() >= feNeeded;
            boolean hasWater  = be.fluidHandler.getAmountAsLong(0) >= wNeeded;
            long cellSpace    = TANK_CAPACITY - be.fluidHandler.getAmountAsLong(1);
            // hasSpace only matters when there is actual output; last-tick batches < OUTPUT_DIVISOR
            // produce cellOut=0 (integer division) and must still be allowed to complete.
            boolean hasSpace  = cellOut == 0 || cellSpace >= cellOut;

            if (hasEnergy && hasWater && hasSpace) {
                var waterRes = FluidResource.of(Fluids.WATER);
                var cellRes  = FluidResource.of(WnirRegistries.MAGIC_CELLULOSE_STILL.get());

                try (var tx = Transaction.openRoot()) {
                    be.energyHandler.extract(feNeeded, tx);
                    be.fluidHandler.extract(0, waterRes, wNeeded, tx);
                    // slot 1 has isValid=false for external, but set() bypasses validation
                    be.fluidHandler.set(1, cellRes, (int)(be.fluidHandler.getAmountAsLong(1) + cellOut));
                    tx.commit();
                }
                be.remainingXp -= tickXp;
                changed = true;
            }
        }

        if (changed) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ── XP calculation ───────────────────────────────────────────────────────

    /**
     * Sum of XP for all enchantments on the item.
     * Reads STORED_ENCHANTMENTS (books) and ENCHANTMENTS (gear) — whichever is present.
     */
    static int calcItemXp(ItemStack stack) {
        int total = 0;
        for (var enc : new ItemEnchantments[]{
            stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY),
            stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY),
        }) {
            for (var entry : enc.entrySet()) {
                var ench = entry.getKey().value();
                int lvl = entry.getValue();
                int meanLevel = (ench.getMinCost(lvl) + ench.getMaxCost(lvl)) / 2;
                total += levelToXp(meanLevel);
            }
        }
        return total;
    }

    /**
     * Total XP points needed to reach level n from level 0.
     * Minecraft formula (Java edition):
     *   n ≤ 16 : n² + 6n
     *   n ≤ 31 : 2.5n² − 40.5n + 360
     *   n > 31 : 4.5n² − 162.5n + 2220
     */
    static int levelToXp(int n) {
        if (n <= 0)  return 0;
        if (n <= 16) return n * n + 6 * n;
        if (n <= 31) return Math.round(2.5f * n * n - 40.5f * n + 360);
        return Math.round(4.5f * n * n - 162.5f * n + 2220);
    }
}
