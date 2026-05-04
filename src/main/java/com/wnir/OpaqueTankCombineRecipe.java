package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Combines 2–9 Opaque Tank items in the crafting grid (shapeless).
 *
 * All input tanks must hold the same fluid type (or be empty).
 * Output: one tank with summed capacity and summed fluid amount.
 *
 * JSON: {"type": "wnir:opaque_tank_combine"}
 */
public class OpaqueTankCombineRecipe extends CustomRecipe {

    private static final OpaqueTankCombineRecipe INSTANCE = new OpaqueTankCombineRecipe();
    public static final RecipeSerializer<OpaqueTankCombineRecipe> SERIALIZER =
        new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

    public OpaqueTankCombineRecipe() { super(); }

    @Override
    public RecipeSerializer<OpaqueTankCombineRecipe> getSerializer() {
        return WnirRegistries.OPAQUE_TANK_COMBINE_RECIPE.get();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        int count = 0;
        String commonFluid = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (!isTank(stack)) return false;
            count++;
            String fluidId = getFluidId(stack);
            if (!fluidId.isEmpty()) {
                if (commonFluid == null) commonFluid = fluidId;
                else if (!commonFluid.equals(fluidId)) return false;
            }
        }
        return count >= 2;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        long totalCapacity = 0;
        long totalAmount   = 0;
        String fluidId     = "";

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null) {
                CompoundTag tag = data.copyTagWithoutId();
                totalCapacity += tag.getLong("capacity").orElse((long) OpaqueTankBlockEntity.BASE_CAPACITY);
                totalAmount   += tag.getLong("fluid_amount").orElse(0L);
                String fid = tag.getString("fluid_id").orElse("");
                if (!fid.isEmpty()) fluidId = fid;
            } else {
                totalCapacity += OpaqueTankBlockEntity.BASE_CAPACITY;
            }
        }

        totalAmount = Math.min(totalAmount, totalCapacity);

        ItemStack result = new ItemStack(WnirRegistries.OPAQUE_TANK_BLOCK.get());
        CompoundTag tag = new CompoundTag();
        tag.putLong("capacity", totalCapacity);
        if (!fluidId.isEmpty() && totalAmount > 0) {
            tag.putString("fluid_id", fluidId);
            tag.putLong("fluid_amount", totalAmount);
        }
        result.set(DataComponents.BLOCK_ENTITY_DATA,
            TypedEntityData.of(WnirRegistries.OPAQUE_TANK_BE.get(), tag));
        return result;
    }

    private static String getFluidId(ItemStack stack) {
        TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return "";
        return data.copyTagWithoutId().getString("fluid_id").orElse("");
    }

    private static boolean isTank(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof OpaqueTankBlock;
    }
}
