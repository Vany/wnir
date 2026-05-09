package com.wnir;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

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

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SpawnerCraftingRecipe INSTANCE = new SpawnerCraftingRecipe();
    public static final RecipeSerializer<SpawnerCraftingRecipe> SERIALIZER =
        new RecipeSerializer<>(
            MapCodec.unit(INSTANCE),
            StreamCodec.unit(INSTANCE)
        );

    public SpawnerCraftingRecipe() {
        super();
    }

    @Override
    public RecipeSerializer<SpawnerCraftingRecipe> getSerializer() {
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
        boolean ok = isIronBar(input.getItem(0))
            && isBucket(input.getItem(1))
            && isIronBar(input.getItem(2))
            && isIronBar(input.getItem(3))
            && isTapeWithSpawner(input.getItem(4))
            && isIronBar(input.getItem(5))
            && isIronBar(input.getItem(6))
            && isAccumulator(input.getItem(7))
            && isIronBar(input.getItem(8));
        if (!ok && !input.getItem(4).isEmpty()) {
            // Log when slot 4 is non-empty but recipe fails — helps diagnose the tape check.
            ItemStack tape = input.getItem(4);
            CustomData cd = tape.get(DataComponents.CUSTOM_DATA);
            CompoundTag cdTag = cd == null ? null : cd.copyTag();
            LOGGER.info("[SpawnerRecipe] FAIL: item4={} isTape={} customData={} blockState={}",
                tape.getItem().getDescriptionId(),
                tape.is(WnirRegistries.BLUE_STICKY_TAPE_ITEM.get()),
                cdTag,
                cdTag == null ? "null" : cdTag.getCompound("block_state").map(Object::toString).orElse("missing"));
        }
        return ok;
    }

    @Override
    public List<RecipeDisplay> display() {
        SlotDisplay iron   = Ingredient.of(Items.IRON_BARS).display();
        SlotDisplay bucket = Ingredient.of(Items.BUCKET).display();
        SlotDisplay tape   = Ingredient.of(WnirRegistries.BLUE_STICKY_TAPE_ITEM.get()).display();
        SlotDisplay accum  = Ingredient.of(WnirRegistries.ACCUMULATOR_BLOCK.get().asItem()).display();
        return List.of(new ShapedCraftingRecipeDisplay(
            3, 3,
            List.of(iron, bucket, iron, iron, tape, iron, iron, accum, iron),
            new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(WnirRegistries.SPAWNER_ITEM.get())),
            new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE.builtInRegistryHolder())
        ));
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
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
