package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/**
 * Accumulator block entity.
 *
 * Stores FE energy. Capacity is variable: 1 000 000 FE per base accumulator,
 * growing when accumulators are combined in the crafting table.
 *
 * Thread safety: NeoForge's TransactionContext / SnapshotJournal handles atomicity
 * for concurrent insert + extract operations from external machines.
 *
 * NBT keys:
 *   "capacity" (long) — max FE; may exceed Integer.MAX_VALUE for heavily combined stacks
 *   "energy"   (long) — current FE stored (effective value capped at Integer.MAX_VALUE by handler)
 */
public class AccumulatorBlockEntity extends BlockEntity {

    public static final long BASE_CAPACITY = 1_000_000L;

    // Source-of-truth capacity (long to survive repeated combining without overflow).
    // The SimpleEnergyHandler uses int internally, so effective stored FE caps at Integer.MAX_VALUE.
    private long capacity = BASE_CAPACITY;

    // Recreated in loadAdditional to reflect the loaded capacity.
    SimpleEnergyHandler energyHandler = buildHandler(BASE_CAPACITY, 0);

    public AccumulatorBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.ACCUMULATOR_BE.get(), pos, state);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Clamp long to int range for SimpleEnergyHandler. */
    private static int clamp(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private SimpleEnergyHandler buildHandler(long cap, long initialEnergy) {
        int icap = clamp(cap);
        int ienergy = Math.min(clamp(initialEnergy), icap);
        return new SimpleEnergyHandler(icap, icap, icap, ienergy) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                AccumulatorBlockEntity.this.setChanged();
            }
        };
    }

    // ── Public accessors (used by combine recipe) ─────────────────────────────

    public long getCapacity() { return capacity; }

    public long getEnergy() { return energyHandler.getAmountAsLong(); }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        capacity = input.getLongOr("capacity", BASE_CAPACITY);
        long storedEnergy = input.getLongOr("energy", 0L);
        // Rebuild handler with correct capacity and energy — must happen after load.
        // invalidateCapabilities() follows so any mod that cached the old handler reference
        // (e.g. Tesseract, Pipez) is forced to re-query instead of using the stale object.
        energyHandler = buildHandler(capacity, storedEnergy);
        invalidateCapabilities();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("capacity", capacity);
        output.putLong("energy", energyHandler.getAmountAsLong());
    }
}
