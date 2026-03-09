package com.wnir;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Handles the Swift Strike enchantment (wnir:swift_strike).
 * Reduces attack delay by applying ADD_MULTIPLIED_TOTAL on ATTACK_SPEED.
 *
 * Delay formula: new_delay = old_delay / (1 + mult)
 *   Level 1: mult = 1/3  → -25% delay
 *   Level 2: mult = 1.0  → -50% delay
 *   Level 3: mult = 3.0  → -75% delay
 */
public final class SwiftStrikeHandler {

    static final ResourceKey<Enchantment> KEY =
        ResourceKey.create(Registries.ENCHANTMENT, WnirRegistries.id("swift_strike"));

    private static final Identifier MODIFIER_ID =
        WnirRegistries.id("swift_strike");

    private static final double[] MULT = { 1.0 / 3.0, 1.0, 3.0 };

    private SwiftStrikeHandler() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_SPEED);
        if (attr == null) return;

        int level = WnirEnchantments.getLevel(player.getMainHandItem(), KEY);
        attr.removeModifier(MODIFIER_ID);
        if (level > 0) {
            attr.addTransientModifier(new AttributeModifier(
                MODIFIER_ID,
                MULT[Math.min(level, 3) - 1],
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
    }

}
