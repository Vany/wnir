package com.wnir;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.MaceItem;

import java.util.UUID;

/**
 * Melee attack goal — used when weapon slot is empty or holds a standard melee weapon
 * (no KINETIC_WEAPON component, not a Mace). Standard MeleeAttackGoal with an owner-range check.
 */
public class WeddingRingMeleeGoal extends MeleeAttackGoal {

    private static final double MAX_OWNER_DIST = 32.0;
    private final PathfinderMob pet;

    public WeddingRingMeleeGoal(PathfinderMob pet) {
        super(pet, 1.2, true);
        this.pet = pet;
    }

    @Override
    public boolean canUse() {
        if (!isOwnerInRange()) return false;
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return false;
        // Only activate for non-spear, non-mace weapons
        var weapon = data.getWeapon();
        if (!weapon.isEmpty()) {
            if (weapon.has(net.minecraft.core.component.DataComponents.KINETIC_WEAPON)) return false;
            if (weapon.getItem() instanceof MaceItem) return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return isOwnerInRange() && super.canContinueToUse();
    }

    private boolean isOwnerInRange() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return false;
        UUID ownerUUID = data.getOwnerUUID();
        if (!(pet.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(ownerUUID);
        return owner != null && pet.distanceTo(owner) <= MAX_OWNER_DIST;
    }
}
