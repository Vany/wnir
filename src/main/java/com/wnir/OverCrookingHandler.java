package com.wnir;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Handles the OverCrooking enchantment (wnir:over_crooking) for hoes.
 *
 * When breaking leaves with a hoe enchanted with OverCrooking, multiplies
 * the count of all drops EXCEPT saplings and sticks.
 *
 *   Level I:   ×2
 *   Level II:  ×3
 *   Level III: ×4
 */
public final class OverCrookingHandler {

    static final ResourceKey<Enchantment> KEY =
        ResourceKey.create(Registries.ENCHANTMENT, WnirRegistries.id("over_crooking"));

    private OverCrookingHandler() {}

    public static void onBlockDrops(BlockDropsEvent event) {
        if (!event.getState().is(BlockTags.LEAVES)) return;

        int level = WnirEnchantments.getLevel(event.getTool(), KEY);
        if (level <= 0) return;

        int mult = level + 1;

        for (ItemEntity entity : event.getDrops()) {
            ItemStack stack = entity.getItem();
            if (stack.is(Items.STICK)) continue;
            if (stack.is(ItemTags.SAPLINGS)) continue;
            stack.setCount(Math.min(stack.getCount() * mult, stack.getMaxStackSize()));
        }
    }
}
