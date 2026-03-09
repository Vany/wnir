package com.wnir;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Handles the Toughness enchantment (wnir:toughness).
 * Sums levels across all worn armor pieces; applies a single ADD_VALUE modifier
 * to ARMOR_TOUGHNESS. Four pieces of Toughness III = +12 total toughness.
 */
public final class ToughnessHandler {

    static final ResourceKey<Enchantment> KEY =
        ResourceKey.create(Registries.ENCHANTMENT, WnirRegistries.id("toughness"));

    private static final Identifier MODIFIER_ID =
        WnirRegistries.id("toughness");

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private ToughnessHandler() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AttributeInstance attr = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (attr == null) return;

        int total = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            total += WnirEnchantments.getLevel(player.getItemBySlot(slot), KEY);
        }

        attr.removeModifier(MODIFIER_ID);
        if (total > 0) {
            attr.addTransientModifier(new AttributeModifier(
                MODIFIER_ID,
                (double) total,
                AttributeModifier.Operation.ADD_VALUE
            ));
        }
    }

}
