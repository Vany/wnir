package com.wnir;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
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

    public static final CustomRecipe.Serializer<KelpCompressionRecipe> SERIALIZER =
        new CustomRecipe.Serializer<>(KelpCompressionRecipe::new);

    public KelpCompressionRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
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
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
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
