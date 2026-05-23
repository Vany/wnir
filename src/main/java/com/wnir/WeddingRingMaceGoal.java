package com.wnir;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Mace attack goal — jumps above the target, falls onto it for a heavy smash.
 * canUse: weapon slot holds MaceItem, owner ≤32 blocks.
 */
public class WeddingRingMaceGoal extends Goal {

    private static final double MAX_OWNER_DIST  = 32.0;
    private static final double APPROACH_SPEED  = 1.2;
    private static final double RETREAT_SPEED   = 1.6;
    private static final double APPROACH_RANGE  = 6.0;
    private static final double HIT_RANGE       = 2.5;
    private static final float  JUMP_Y          = 0.9f;

    private final Mob pet;
    private Phase phase = Phase.IDLE;
    private Vec3 retreatPos = null;
    private int retreatTimer = 0;

    public WeddingRingMaceGoal(Mob pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!isOwnerInRange()) return false;
        LivingEntity target = pet.getTarget();
        if (target == null || !target.isAlive()) return false;
        WeddingRingData data = WeddingRingData.get(pet);
        return data != null && data.getWeapon().getItem() instanceof MaceItem;
    }

    @Override
    public boolean canContinueToUse() {
        if (!isOwnerInRange()) return false;
        LivingEntity target = pet.getTarget();
        return target != null && target.isAlive() && phase != Phase.IDLE;
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        pet.setAggressive(true);
    }

    @Override
    public void stop() {
        pet.setAggressive(false);
        pet.getNavigation().stop();
        phase = Phase.IDLE;
        retreatPos = null;
        retreatTimer = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = pet.getTarget();
        if (target == null) return;

        pet.getLookControl().setLookAt(target, 30.0f, 30.0f);

        switch (phase) {
            case APPROACH -> {
                pet.getNavigation().moveTo(target, APPROACH_SPEED);
                if (pet.distanceTo(target) <= APPROACH_RANGE) {
                    // Launch upward toward target
                    double dx = (target.getX() - pet.getX()) * 0.3;
                    double dz = (target.getZ() - pet.getZ()) * 0.3;
                    pet.setDeltaMovement(dx, JUMP_Y, dz);
                    phase = Phase.FALLING;
                }
            }
            case FALLING -> {
                // Move horizontally toward target during fall
                double dx = (target.getX() - pet.getX()) * 0.15;
                double dz = (target.getZ() - pet.getZ()) * 0.15;
                Vec3 v = pet.getDeltaMovement();
                pet.setDeltaMovement(dx, v.y, dz);

                if (pet.fallDistance >= 5.0f && pet.distanceTo(target) <= HIT_RANGE) {
                    if (pet.level() instanceof ServerLevel sl) {
                        pet.doHurtTarget(sl, target);
                    }
                    phase = Phase.RETREAT;
                } else if (pet.onGround()) {
                    // Landed without hitting — still try melee
                    if (pet.level() instanceof ServerLevel sl && pet.distanceTo(target) <= HIT_RANGE + 2) {
                        pet.doHurtTarget(sl, target);
                    }
                    phase = Phase.RETREAT;
                }
            }
            case RETREAT -> {
                if (retreatPos == null) {
                    Vec3 pos = pet instanceof PathfinderMob pm
                        ? LandRandomPos.getPosAway(pm, 9, 11, 7, target.position())
                        : null;
                    retreatPos = pos != null ? pos : pet.position();
                    retreatTimer = 0;
                }
                pet.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, RETREAT_SPEED);
                retreatTimer++;
                if (retreatTimer > 60 || pet.getNavigation().isDone()) {
                    phase = Phase.IDLE;
                }
            }
            case IDLE -> {}
        }
    }

    private boolean isOwnerInRange() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return false;
        if (!(pet.level() instanceof ServerLevel sl)) return false;
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
        return owner != null && pet.distanceTo(owner) <= MAX_OWNER_DIST;
    }

    private enum Phase { IDLE, APPROACH, FALLING, RETREAT }
}
