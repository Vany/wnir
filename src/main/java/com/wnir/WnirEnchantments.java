package com.wnir;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Shared enchantment utilities for wnir enchantment handlers. */
public final class WnirEnchantments {

    private WnirEnchantments() {}

    /** Returns the level of the given enchantment on the stack, or 0 if absent. */
    public static int getLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        ItemEnchantments enc = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> holder : enc.keySet()) {
            if (holder.is(key)) {
                return enc.getLevel(holder);
            }
        }
        return 0;
    }
}
