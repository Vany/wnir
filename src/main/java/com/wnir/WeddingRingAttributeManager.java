package com.wnir;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Recalculates all wedding ring attribute modifiers on the pet.
 * All modifiers use identifiers under wnir:wedding_ring/* so they can be fully removed and re-added.
 */
public final class WeddingRingAttributeManager {

    private static final Identifier ID_ARMOR         = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring/armor");
    private static final Identifier ID_TOUGHNESS     = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring/toughness");
    private static final Identifier ID_ATTACK_DAMAGE = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring/attack_damage");
    private static final Identifier ID_PROT_ARMOR    = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring/prot_armor");

    private WeddingRingAttributeManager() {}

    public static void recalculate(Mob pet) {
        WeddingRingData data = WeddingRingData.get(pet);

        // Remove all existing ring modifiers first
        removeAll(pet);

        if (data == null) return;

        double armor    = 0;
        double toughness = 0;
        int    totalProt = 0;

        // Armor pieces
        List<ItemStack> armorPieces;
        if (data.hasArmor()) {
            armorPieces = List.of(
                data.getArmorHead(), data.getArmorChest(),
                data.getArmorLegs(), data.getArmorFeet()
            );
            EquipmentSlot[] slots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
            };
            for (int i = 0; i < armorPieces.size(); i++) {
                ItemStack piece = armorPieces.get(i);
                if (piece.isEmpty()) continue;
                // Sum item attribute modifiers for this slot
                double[] armorSum     = {0};
                double[] toughSum     = {0};
                piece.forEachModifier(slots[i], (attr, mod) -> {
                    if (attr.equals(Attributes.ARMOR))          armorSum[0]  += mod.amount();
                    if (attr.equals(Attributes.ARMOR_TOUGHNESS)) toughSum[0] += mod.amount();
                });
                armor    += armorSum[0];
                toughness += toughSum[0];

                // Enchantments: protection
                totalProt += enchLevel(pet, piece, Enchantments.PROTECTION);
                // Toughness enchantment (from our custom enchantment)
                // Our custom wnir:toughness enchant modifies ARMOR_TOUGHNESS — but that's handled
                // by the enchantment system directly; skip custom enchant here unless desired.
            }
        }

        // Weapon damage
        double weaponDmg = 0;
        int    sharpness  = 0;
        ItemStack weapon = data.getWeapon();
        if (!weapon.isEmpty()) {
            double[] dmgSum = {0};
            weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                if (attr.equals(Attributes.ATTACK_DAMAGE)) dmgSum[0] += mod.amount();
            });
            weaponDmg = dmgSum[0];
            sharpness = enchLevel(pet, weapon, Enchantments.SHARPNESS);
        }

        // Apply modifiers
        if (armor > 0) {
            applyModifier(pet, Attributes.ARMOR, ID_ARMOR, armor, AttributeModifier.Operation.ADD_VALUE);
        }
        if (toughness > 0) {
            applyModifier(pet, Attributes.ARMOR_TOUGHNESS, ID_TOUGHNESS, toughness, AttributeModifier.Operation.ADD_VALUE);
        }
        if (weaponDmg > 0 || sharpness > 0) {
            double dmg = weaponDmg + (sharpness > 0 ? 0.5 + 0.5 * sharpness : 0); // vanilla sharpness: +0.5 * level + 0.5
            applyModifier(pet, Attributes.ATTACK_DAMAGE, ID_ATTACK_DAMAGE, dmg, AttributeModifier.Operation.ADD_VALUE);
        }
        if (totalProt > 0) {
            // +0.5 per total protection level, cap contribution at +20 armor
            double protArmor = Math.min(totalProt * 0.5, 20.0);
            applyModifier(pet, Attributes.ARMOR, ID_PROT_ARMOR, protArmor, AttributeModifier.Operation.ADD_VALUE);
        }

        // Physical copy in MAINHAND so startUsingItem / doHurtTarget mechanics work
        ItemStack handItem = (data != null && !data.getWeapon().isEmpty())
            ? data.getWeapon().copy() : ItemStack.EMPTY;
        pet.setItemSlot(EquipmentSlot.MAINHAND, handItem);
    }

    private static void removeAll(Mob pet) {
        for (Holder<Attribute> attrHolder : List.of(Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.ATTACK_DAMAGE)) {
            var inst = pet.getAttribute(attrHolder);
            if (inst != null) {
                inst.removeModifier(ID_ARMOR);
                inst.removeModifier(ID_TOUGHNESS);
                inst.removeModifier(ID_ATTACK_DAMAGE);
                inst.removeModifier(ID_PROT_ARMOR);
            }
        }
    }

    private static void applyModifier(Mob pet, Holder<Attribute> attr, Identifier id,
                                       double amount, AttributeModifier.Operation op) {
        var inst = pet.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id); // ensure no duplicate
        inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static int enchLevel(Mob pet, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        var registry = pet.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        return registry.get(key).map(stack::getEnchantmentLevel).orElse(0);
    }
}
