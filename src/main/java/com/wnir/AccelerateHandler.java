package com.wnir;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.enchantment.Enchantment;
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
        ResourceKey.create(Registries.ENCHANTMENT, WnirRegistries.id("accelerate"));

    private static final double[] MULT = { 1.5, 2.0, 3.0 };

    private AccelerateHandler() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof AbstractArrow)) return;
        if (!(((Projectile) entity).getOwner() instanceof Player player)) return;

        int level = Math.max(
            WnirEnchantments.getLevel(player.getMainHandItem(), KEY),
            WnirEnchantments.getLevel(player.getOffhandItem(), KEY)
        );
        if (level <= 0) return;

        double mult = MULT[Math.min(level, 3) - 1];
        entity.setDeltaMovement(entity.getDeltaMovement().scale(mult));
    }

}
