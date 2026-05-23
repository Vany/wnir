package com.wnir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.UUID;

/**
 * Handles on-hit effects for wedding ring pets:
 * - Fire Aspect: burn target when pet attacks
 * - Shield blocking: reduce incoming damage, hurt shield durability, send "broke the shield" caption
 */
public final class WeddingRingAttackHandler {

    private WeddingRingAttackHandler() {}

    /** Called after the pet deals damage — apply Fire Aspect. */
    public static void onLivingHurt(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        var source = event.getSource();
        if (!(source.getDirectEntity() instanceof Mob attacker)) return;

        WeddingRingData data = WeddingRingData.get(attacker);
        if (data == null) return;

        ItemStack weapon = data.getWeapon();
        if (weapon.isEmpty()) return;

        if (!(attacker.level() instanceof ServerLevel sl)) return;

        // Fire Aspect
        var enchRegistry = sl.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        int fireAspect = enchRegistry.get(Enchantments.FIRE_ASPECT)
            .map(weapon::getEnchantmentLevel).orElse(0);
        if (fireAspect > 0) {
            victim.igniteForSeconds(fireAspect * 4.0f);
        }

        // Exhaustion on attack
        data.addExhaustion(0.1f);
    }

    /**
     * Shield blocking: intercepts incoming damage to a bound pet that is in combat.
     * Reduces damage by ~33%, hurts shield durability.
     */
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (!(target instanceof Mob mob)) return;

        WeddingRingData data = WeddingRingData.get(mob);
        if (data == null) return;

        // Notify target filter if this damage came from the pet's current target
        if (event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker == mob.getTarget()) {
            WeddingRingTargetFilter.onDamagedByTarget(mob.getUUID());
        }

        ItemStack shield = data.getShield();
        if (shield.isEmpty()) return;
        BlocksAttacks blockData = shield.get(DataComponents.BLOCKS_ATTACKS);
        if (blockData == null) return;

        // Only shield when in combat
        if (mob.getTarget() == null) return;

        float damage  = event.getAmount();
        // 0.0 angle = treat attacker as head-on so any shield angle threshold passes
        float blocked = blockData.resolveBlockedDamage(event.getSource(), damage, 0.0);
        if (blocked <= 0) return; // damage type bypasses this shield (e.g. fire on fire-resistant shield)

        event.setAmount(damage - blocked);

        // Notify LLM session when the pet takes significant damage
        if (event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attk) {
            WeddingRingLlmHandler.onPetDamaged(mob, damage, attk);
        }

        if (target.level() instanceof ServerLevel sl) {
            blockData.onBlocked(sl, mob); // plays the shield's own block sound

            int durabilityDmg = blockData.itemDamage().apply(blocked);
            if (durabilityDmg > 0) {
                ItemStack newShield = shield.copy();
                newShield.hurtAndBreak(durabilityDmg, sl, null, item -> {
                    data.setShield(ItemStack.EMPTY);
                    mob.playSound(SoundEvents.SHIELD_BREAK.value(), 1.0f, 1.0f);
                    UUID ownerUUID = data.getOwnerUUID();
                    ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(ownerUUID);
                    if (owner != null) {
                        WeddingRingCaptionPayload.send(owner,
                            mob.getDisplayName().getString() + " broke the shield");
                    }
                    WeddingRingAttributeManager.recalculate(mob);
                });
                if (!newShield.isEmpty()) {
                    data.setShield(newShield);
                }
            }
        }
    }

    /** When a mob killed by a bound pet would drop XP, store it in the pet's buffer instead. */
    public static void onLivingExperienceDrop(LivingExperienceDropEvent event) {
        int xp = event.getDroppedExperience();
        if (xp <= 0) return;

        var lastHurtBy = event.getEntity().getLastHurtByMob();
        if (!(lastHurtBy instanceof Mob mob)) return;

        WeddingRingData data = WeddingRingData.get(mob);
        if (data == null) return;

        data.addXp(xp);
        event.setDroppedExperience(0);
    }

    /** When a bound pet kills a mob, teleport the drops to the owner's feet. */
    public static void onLivingDrops(LivingDropsEvent event) {
        var source = event.getSource();
        var killer = source.getDirectEntity();
        if (!(killer instanceof Mob mob)) return;

        WeddingRingData data = WeddingRingData.get(mob);
        if (data == null) return;

        if (!(mob.level() instanceof ServerLevel sl)) return;
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
        if (owner == null) return;

        // Notify LLM session
        WeddingRingLlmHandler.onPetKill(mob, event.getEntity());

        // Move all drops to the owner's position
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) continue;
            ItemEntity relocated = new ItemEntity(sl,
                owner.getX(), owner.getY(), owner.getZ(), stack.copy());
            sl.addFreshEntity(relocated);
        }
        event.getDrops().clear();
    }
}
