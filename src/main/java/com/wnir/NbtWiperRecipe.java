package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/**
 * Crafting wiper: nbt_wiper_liquid + any item → fresh copy of that item with no data components.
 * JSON: {"type": "wnir:nbt_wiper"}
 */
public class NbtWiperRecipe extends CustomRecipe {

    private static final NbtWiperRecipe INSTANCE = new NbtWiperRecipe();
    public static final RecipeSerializer<NbtWiperRecipe> SERIALIZER =
        new RecipeSerializer<>(
            MapCodec.unit(INSTANCE),
            StreamCodec.unit(INSTANCE)
        );

    public NbtWiperRecipe() {
        super();
    }

    @Override
    public RecipeSerializer<NbtWiperRecipe> getSerializer() {
        return WnirRegistries.NBT_WIPER_RECIPE.get();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        boolean hasWiper = false, hasTarget = false;
        int nonEmpty = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            nonEmpty++;
            if (s.is(WnirRegistries.NBT_WIPER_LIQUID_ITEM.get())) hasWiper = true;
            else hasTarget = true;
        }
        return nonEmpty == 2 && hasWiper && hasTarget;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (!s.isEmpty() && !s.is(WnirRegistries.NBT_WIPER_LIQUID_ITEM.get())) {
                return new ItemStack(s.getItem());
            }
        }
        return ItemStack.EMPTY;
    }
}
