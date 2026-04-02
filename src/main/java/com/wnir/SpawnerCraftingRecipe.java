package com.wnir;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Spawner crafting recipe.
 *
 * Layout (3×3):
 *   I   B   I
 *   I   T   I
 *   I   A   I
 *
 * I = iron bars, B = bucket (empty), T = blue sticky tape wrapping a vanilla spawner,
 * A = accumulator.
 *
 * JSON: {"type": "wnir:spawner_crafting"}
 */
public class SpawnerCraftingRecipe extends CustomRecipe {

    public static final CustomRecipe.Serializer<SpawnerCraftingRecipe> SERIALIZER =
        new CustomRecipe.Serializer<>(SpawnerCraftingRecipe::new);

    public SpawnerCraftingRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return WnirRegistries.SPAWNER_CRAFTING_RECIPE.get();
    }

    /** Return false so JEI and the recipe book include this recipe. */
    @Override
    public boolean isSpecial() { return false; }

    /**
     * Describe the 3×3 grid for JEI and the recipe book.
     * The tape slot shows blue sticky tape; players must wrap a spawner inside it.
     */
    @Override
    public PlacementInfo placementInfo() {
        Ingredient iron = Ingredient.of(Items.IRON_BARS);
        Ingredient bucket = Ingredient.of(Items.BUCKET);
        Ingredient tape = Ingredient.of(WnirRegistries.BLUE_STICKY_TAPE_ITEM.get());
        Ingredient accum = Ingredient.of(WnirRegistries.ACCUMULATOR_BLOCK.get().asItem());
        return PlacementInfo.createFromOptionals(List.of(
            Optional.of(iron),   Optional.of(bucket), Optional.of(iron),
            Optional.of(iron),   Optional.of(tape),   Optional.of(iron),
            Optional.of(iron),   Optional.of(accum),  Optional.of(iron)
        ));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 3 || input.height() != 3) return false;
        // Positions: (row, col) → index = row*3+col
        return isIronBar(input.getItem(0))         // top-left
            && isBucket(input.getItem(1))           // top-center
            && isIronBar(input.getItem(2))          // top-right
            && isIronBar(input.getItem(3))          // mid-left
            && isTapeWithSpawner(input.getItem(4))  // mid-center
            && isIronBar(input.getItem(5))          // mid-right
            && isIronBar(input.getItem(6))          // bot-left
            && isAccumulator(input.getItem(7))      // bot-center
            && isIronBar(input.getItem(8));         // bot-right
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return new ItemStack(WnirRegistries.SPAWNER_ITEM.get());
    }

    // ── Ingredient checks ─────────────────────────────────────────────────────

    private static boolean isIronBar(ItemStack s) {
        return s.is(Items.IRON_BARS);
    }

    private static boolean isBucket(ItemStack s) {
        return s.is(Items.BUCKET);
    }

    private static boolean isAccumulator(ItemStack s) {
        return s.is(WnirRegistries.ACCUMULATOR_BLOCK.get().asItem());
    }

    /**
     * Blue sticky tape that wraps a vanilla spawner (minecraft:spawner).
     * The tape stores block identity in CustomData → block_state.Name.
     */
    static boolean isTapeWithSpawner(ItemStack s) {
        if (!s.is(WnirRegistries.BLUE_STICKY_TAPE_ITEM.get())) return false;
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        return tag.getCompound("block_state")
            .flatMap(bs -> bs.getString("Name"))
            .map(name -> name.equals("minecraft:spawner"))
            .orElse(false);
    }
}
