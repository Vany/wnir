package com.wnir;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 3×3 crafting recipe: 8 kelp surrounding 1 magma cream → 1 slimeball.
 * Residue: a dried kelp block is left in the center slot (where magma cream was).
 *
 * JSON: {"type": "wnir:kelp_compression"}
 *
 *   K K K
 *   K M K   →  slimeball  +  dried kelp block (residue in center slot)
 *   K K K
 *
 * K = minecraft:kelp, M = minecraft:magma_cream
 */
public class KelpCompressionRecipe extends CustomRecipe {

    private static final KelpCompressionRecipe INSTANCE = new KelpCompressionRecipe();
    public static final RecipeSerializer<KelpCompressionRecipe> SERIALIZER =
        new RecipeSerializer<>(
            MapCodec.unit(INSTANCE),
            StreamCodec.unit(INSTANCE)
        );

    public KelpCompressionRecipe() {
        super();
    }

    @Override
    public RecipeSerializer<KelpCompressionRecipe> getSerializer() {
        return WnirRegistries.KELP_COMPRESSION_RECIPE.get();
    }

    /** Requires a full 3×3 grid: magma cream in center, kelp in all 8 surrounding slots. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 3 || input.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack item = input.getItem(i);
            if (i == 4) {
                if (!item.is(Items.MAGMA_CREAM)) return false;
            } else {
                if (!item.is(Items.KELP)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return new ItemStack(Items.SLIME_BALL);
    }

    /**
     * Returns a dried kelp block in the center slot (index 4) as a crafting residue.
     * All other slots return empty (default).
     */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        remaining.set(4, new ItemStack(Items.DRIED_KELP_BLOCK));
        return remaining;
    }
}
