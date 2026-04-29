package com.wnir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Combines 2–9 Accumulator items in the crafting grid (shapeless).
 *
 * Output: one Accumulator with summed capacity and summed energy.
 * A fresh Accumulator (no BLOCK_ENTITY_DATA) is treated as base capacity / 0 energy.
 *
 * JSON: {"type": "wnir:accumulator_combine"}
 */
public class AccumulatorCombineRecipe extends CustomRecipe {

    private static final AccumulatorCombineRecipe INSTANCE = new AccumulatorCombineRecipe();
    public static final RecipeSerializer<AccumulatorCombineRecipe> SERIALIZER =
        new RecipeSerializer<>(
            MapCodec.unit(INSTANCE),
            StreamCodec.unit(INSTANCE)
        );

    public AccumulatorCombineRecipe() {
        super();
    }

    @Override
    public RecipeSerializer<AccumulatorCombineRecipe> getSerializer() {
        return WnirRegistries.ACCUMULATOR_COMBINE_RECIPE.get();
    }

    /** All non-empty slots must be accumulators, and there must be at least 2. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        int count = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (!isAccumulator(stack)) return false;
            count++;
        }
        return count >= 2;
    }

    /** Sum capacity and energy from all input accumulators. */
    @Override
    public ItemStack assemble(CraftingInput input) {
        long totalCapacity = 0;
        long totalEnergy   = 0;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null) {
                CompoundTag tag = data.copyTagWithoutId();
                totalCapacity += tag.getLong("capacity").orElse(AccumulatorBlockEntity.BASE_CAPACITY);
                totalEnergy   += tag.getLong("energy").orElse(0L);
            } else {
                totalCapacity += AccumulatorBlockEntity.BASE_CAPACITY;
            }
        }

        // Cap energy at combined capacity (defensive)
        totalEnergy = Math.min(totalEnergy, totalCapacity);

        ItemStack result = new ItemStack(WnirRegistries.ACCUMULATOR_BLOCK.get());
        CompoundTag tag = new CompoundTag();
        tag.putLong("capacity", totalCapacity);
        tag.putLong("energy", totalEnergy);
        result.set(DataComponents.BLOCK_ENTITY_DATA,
            TypedEntityData.of(WnirRegistries.ACCUMULATOR_BE.get(), tag));
        return result;
    }

    private static boolean isAccumulator(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof AccumulatorBlock;
    }
}
