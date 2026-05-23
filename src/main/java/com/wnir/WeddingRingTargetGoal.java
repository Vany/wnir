package com.wnir;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Target selection for the wedding ring pet.
 * Priority:
 * 1. Enemy attacking the pet, within dist(pet→owner)
 * 2. Enemy closer to pet than to owner, within dist(pet→owner)
 * 3. Any Enemy currently targeting the owner
 * 4. Any Enemy within dist(pet→owner) closer to owner than dist(pet→owner)
 *
 * Sends "Help me!" caption when the pet itself gets targeted.
 * All goals are no-ops when owner is offline or >32 blocks away.
 */
public class WeddingRingTargetGoal extends TargetGoal {

    private static final double MAX_OWNER_DIST = 32.0;
    private static final long   HELP_CAPTION_COOLDOWN_TICKS = 15 * 20L;
    private static final String HELP_CAPTION_NBT_KEY        = "WRHelpCaption";

    private final Mob pet;

    public WeddingRingTargetGoal(Mob pet) {
        super(pet, true);
        this.pet = pet;
    }

    @Override
    public boolean canUse() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null || data.isCalm()) return false;
        ServerPlayer owner = findOwner();
        if (owner == null) return false;
        double ownerDist = pet.distanceTo(owner);
        if (ownerDist > MAX_OWNER_DIST) return false;

        LivingEntity target = selectTarget(owner, ownerDist);
        if (target == null) return false;

        this.targetMob = target;
        return true;
    }

    @Override
    public void start() {
        pet.setTarget(targetMob);
        super.start();
    }

    private LivingEntity selectTarget(ServerPlayer owner, double ownerDist) {
        double scanRadius = Math.max(ownerDist, 8.0);
        Level level = pet.level();
        AABB box = pet.getBoundingBox().inflate(scanRadius);
        List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, box,
            e -> e instanceof Enemy && e.isAlive()
                && !(e instanceof OwnableEntity oe && oe.getOwner() != null));

        if (enemies.isEmpty()) return null;

        double petToOwnerSq = pet.distanceToSqr(owner);

        // Priority 1: attacking the pet
        for (LivingEntity e : enemies) {
            if (e instanceof Mob m && m.getTarget() == pet && pet.distanceToSqr(e) <= petToOwnerSq) {
                return e;
            }
        }

        // Priority 2: closer to pet than to owner, within ownerDist
        for (LivingEntity e : enemies) {
            if (pet.distanceToSqr(e) <= petToOwnerSq && pet.distanceToSqr(e) < owner.distanceToSqr(e)) {
                return e;
            }
        }

        // Priority 3: targeting the owner
        for (LivingEntity e : enemies) {
            if (e instanceof Mob m && m.getTarget() == owner) {
                return e;
            }
        }

        // Priority 4: within ownerDist of pet
        return enemies.stream()
            .filter(e -> pet.distanceToSqr(e) <= petToOwnerSq)
            .min(Comparator.comparingDouble(e -> owner.distanceToSqr(e)))
            .orElse(null);
    }

    /**
     * Fired server-side when any mob selects a new target.
     * If the new target is a ring-bound pet, sends "Help me!" to the owner.
     * Cooldown stored in persistentData so it survives entity reload.
     */
    public static void onMobTargetsPet(LivingChangeTargetEvent event) {
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (!(newTarget instanceof Mob pet)) return;
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return;
        if (!(pet.level() instanceof ServerLevel sl)) return;

        long now  = sl.getGameTime();
        long last = pet.getPersistentData().getLongOr(HELP_CAPTION_NBT_KEY, 0L);
        if (now - last < HELP_CAPTION_COOLDOWN_TICKS) return;
        pet.getPersistentData().putLong(HELP_CAPTION_NBT_KEY, now);

        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
        if (owner != null) {
            WeddingRingCaptionPayload.send(owner, pet.getDisplayName().getString() + ": Help me!");
        }

        LivingEntity attacker = event.getEntity();
        WeddingRingLlmHandler.onTargetedByMob(pet, attacker);
    }

    private ServerPlayer findOwner() {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return null;
        UUID ownerUUID = data.getOwnerUUID();
        Level level = pet.level();
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        return sl.getServer().getPlayerList().getPlayer(ownerUUID);
    }
}
