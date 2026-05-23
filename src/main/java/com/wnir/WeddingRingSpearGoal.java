package com.wnir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Spear attack goal — charges up KINETIC_WEAPON, hits target, then retreats.
 *
 * Phases:
 *   APPROACH  — move toward target until within APPROACH_DIST
 *   CHARGE    — startUsingItem; count down engageTime ticks
 *   ADVANCE   — charge done but target out of range; close for the hit
 *   FLEE      — post-hit retreat
 */
public class WeddingRingSpearGoal extends Goal {

    private static final double MAX_OWNER_DIST    = 32.0;
    private static final double SPEED_CHARGING    = 2.0;
    private static final double SPEED_REPOSITION  = 1.6;
    private static final float  APPROACH_DIST_SQ  = 5.0f * 5.0f;
    private static final float  HIT_DIST_SQ       = 3.0f * 3.0f;

    private final Mob pet;
    private Phase phase = Phase.APPROACH;

    public WeddingRingSpearGoal(Mob pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (!isOwnerInRange()) return false;
        if (pet.isUsingItem()) return false;
        LivingEntity target = pet.getTarget();
        if (target == null) return false;
        WeddingRingData data = WeddingRingData.get(pet);
        return data != null && data.getWeapon().has(DataComponents.KINETIC_WEAPON);
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.APPROACH) return false; // re-evaluated each tick via canUse
        return isOwnerInRange() && pet.getTarget() != null
            && WeddingRingData.get(pet) != null
            && WeddingRingData.get(pet).getWeapon().has(DataComponents.KINETIC_WEAPON);
    }

    @Override
    public void start() {
        pet.setAggressive(true);
        phase = Phase.APPROACH;
        chargeTimer = 0;
        engageDuration = 0;
        awayPos = null;
        fleeTimer = 0;
    }

    @Override
    public void stop() {
        pet.getNavigation().stop();
        pet.setAggressive(false);
        pet.stopUsingItem();
        restoreWeapon();
        phase = Phase.APPROACH;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private int chargeTimer   = 0;
    private int engageDuration = 0;
    private Vec3 awayPos      = null;
    private int fleeTimer     = 0;

    private enum Phase { APPROACH, CHARGE, ADVANCE, FLEE }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        LivingEntity target = pet.getTarget();
        if (target == null) { phase = Phase.APPROACH; return; }

        double distSq = pet.distanceToSqr(target.getX(), target.getY(), target.getZ());
        pet.lookAt(target, 30.0f, 30.0f);
        pet.getLookControl().setLookAt(target, 30.0f, 30.0f);

        switch (phase) {
            case APPROACH -> {
                if (distSq > APPROACH_DIST_SQ) {
                    pet.getNavigation().moveTo(target, SPEED_REPOSITION);
                } else {
                    engageDuration = computeSpearDuration();
                    chargeTimer = 0;
                    pet.startUsingItem(InteractionHand.MAIN_HAND);
                    phase = Phase.CHARGE;
                }
            }

            case CHARGE -> {
                chargeTimer++;
                if (chargeTimer >= engageDuration) {
                    pet.stopUsingItem();
                    restoreWeapon();
                    if (distSq <= HIT_DIST_SQ * 4) {
                        doHit(target);
                        phase = Phase.FLEE;
                    } else {
                        // Target moved away — advance to land the hit
                        phase = Phase.ADVANCE;
                        pet.getNavigation().moveTo(target, SPEED_CHARGING);
                    }
                }
                // Keep facing target while charging
                if (distSq > APPROACH_DIST_SQ) {
                    pet.getNavigation().moveTo(target, SPEED_REPOSITION);
                }
            }

            case ADVANCE -> {
                pet.getNavigation().moveTo(target, SPEED_CHARGING);
                if (distSq <= HIT_DIST_SQ * 4 || pet.getNavigation().isDone()) {
                    doHit(target);
                    phase = Phase.FLEE;
                }
            }

            case FLEE -> {
                if (awayPos == null) {
                    double dist = Math.sqrt(distSq);
                    awayPos = pet instanceof PathfinderMob pm
                        ? LandRandomPos.getPosAway(pm,
                            (int) Math.max(1.0, 9 - dist), (int) Math.max(2.0, 11 - dist), 7,
                            target.position())
                        : null;
                    fleeTimer = 0;
                }
                fleeTimer++;
                if (awayPos != null) {
                    pet.getNavigation().moveTo(awayPos.x, awayPos.y, awayPos.z, SPEED_REPOSITION);
                }
                if (fleeTimer > Goal.reducedTickDelay(80) || pet.getNavigation().isDone()) {
                    phase = Phase.APPROACH; // done — let canContinueToUse decide
                    awayPos = null;
                    fleeTimer = 0;
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void doHit(LivingEntity target) {
        if (pet.level() instanceof ServerLevel sl) {
            pet.doHurtTarget(sl, target);
        }
    }

    /** Re-equip weapon copy in hand in case stopUsingItem/releaseUsing cleared it. */
    private void restoreWeapon() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return;
        if (!data.getWeapon().isEmpty()) {
            pet.setItemSlot(EquipmentSlot.MAINHAND, data.getWeapon().copy());
        }
    }

    private int computeSpearDuration() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return 20;
        KineticWeapon kw = data.getWeapon().get(DataComponents.KINETIC_WEAPON);
        if (kw == null) return 20;
        return Goal.reducedTickDelay(kw.computeDamageUseDuration());
    }

    private boolean isOwnerInRange() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return false;
        if (!(pet.level() instanceof ServerLevel sl)) return false;
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
        return owner != null && pet.distanceTo(owner) <= MAX_OWNER_DIST;
    }
}
