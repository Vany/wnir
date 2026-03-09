package com.wnir;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Handles the Accelerate enchantment (wnir:accelerate) for bows and crossbows.
 * Scales arrow velocity when the arrow enters the world.
 *
 *   Level 1: ×1.5
 *   Level 2: ×2.0
 *   Level 3: ×3.0
 */
public final class AccelerateHandler {

    static final ResourceKey<Enchantment> KEY =
        ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "accelerate"));

    private static final double[] MULT = { 1.5, 2.0, 3.0 };

    private AccelerateHandler() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof AbstractArrow)) return;
        if (!(((Projectile) entity).getOwner() instanceof Player player)) return;

        int level = Math.max(
            getLevel(player.getMainHandItem()),
            getLevel(player.getOffhandItem())
        );
        if (level <= 0) return;

        double mult = MULT[Math.min(level, 3) - 1];
        entity.setDeltaMovement(entity.getDeltaMovement().scale(mult));
    }

    private static int getLevel(ItemStack stack) {
        ItemEnchantments enc = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> holder : enc.keySet()) {
            if (holder.is(KEY)) {
                return enc.getLevel(holder);
            }
        }
        return 0;
    }
}
