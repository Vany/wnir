package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Opaque fluid tank block entity.
 *
 * Holds one fluid type up to `capacity` mB. Capacity grows when tanks are
 * combined in a crafting grid (OpaqueTankCombineRecipe). Fluid and capacity
 * are preserved when the block is mined (via playerWillDestroy on the block class).
 *
 * NBT keys (flat, CompoundTag-compatible for tooltip/combine recipe reads):
 *   "capacity"     (long) — max mB; base = 16 000
 *   "fluid_id"     (string) — registry key of stored fluid, absent if empty
 *   "fluid_amount" (long)   — current mB stored, absent if empty
 */
public class OpaqueTankBlockEntity extends BlockEntity {

    public static final int BASE_CAPACITY = 16_000;

    private long capacity = BASE_CAPACITY;

    FluidStacksResourceHandler fluidHandler = buildHandler(BASE_CAPACITY, null, 0);

    public OpaqueTankBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.OPAQUE_TANK_BE.get(), pos, state);
    }

    private FluidStacksResourceHandler buildHandler(long cap, FluidResource initialFluid, long initialAmount) {
        int icap = (int) Math.min(cap, Integer.MAX_VALUE);
        FluidStacksResourceHandler handler = new FluidStacksResourceHandler(1, icap) {
            @Override
            public boolean isValid(int slot, FluidResource resource) {
                if (resource.isEmpty()) return false;
                // Once a fluid type is present, only accept more of the same.
                FluidResource current = getResource(0);
                return current.isEmpty() || current.equals(resource);
            }
            @Override
            protected void onContentsChanged(int slot, FluidStack previous) {
                OpaqueTankBlockEntity.this.setChanged();
            }
        };
        if (initialFluid != null && !initialFluid.isEmpty() && initialAmount > 0) {
            try (var tx = Transaction.openRoot()) {
                handler.insert(0, initialFluid, (int) Math.min(initialAmount, Integer.MAX_VALUE), tx);
                tx.commit();
            }
        }
        return handler;
    }

    public long getCapacity() { return capacity; }
    public FluidResource getFluidResource() { return fluidHandler.getResource(0); }
    public long getFluidAmount() { return fluidHandler.getAmountAsLong(0); }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        capacity = input.getLongOr("capacity", BASE_CAPACITY);
        long fluidAmount = input.getLongOr("fluid_amount", 0L);
        String fluidIdStr = input.getStringOr("fluid_id", "");

        FluidResource initialFluid = null;
        if (!fluidIdStr.isEmpty() && fluidAmount > 0) {
            Identifier id = Identifier.tryParse(fluidIdStr);
            if (id != null) {
                var opt = BuiltInRegistries.FLUID.getOptional(id);
                if (opt.isPresent()) initialFluid = FluidResource.of(opt.get());
            }
        }
        fluidHandler = buildHandler(capacity, initialFluid, fluidAmount);
        invalidateCapabilities();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("capacity", capacity);
        FluidResource res = fluidHandler.getResource(0);
        long amount = fluidHandler.getAmountAsLong(0);
        if (!res.isEmpty() && amount > 0) {
            Identifier key = BuiltInRegistries.FLUID.getKey(res.getFluid());
            if (key != null) {
                output.putString("fluid_id", key.toString());
                output.putLong("fluid_amount", amount);
            }
        }
    }
}
