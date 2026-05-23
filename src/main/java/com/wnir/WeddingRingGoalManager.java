package com.wnir;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;

/**
 * Adds / removes Wedding Ring AI goals on the pet.
 * Goals are added at priority 1 (below vanilla priority-0 panic goals).
 */
public final class WeddingRingGoalManager {

    private WeddingRingGoalManager() {}

    /** Add all ring goals to the pet's goal selectors. Safe to call multiple times (vanilla deduplication by instance isn't needed — we always create fresh instances). */
    public static void addGoals(Mob pet) {
        // Target goal (priority 1 — above lower-priority vanilla targets)
        pet.targetSelector.addGoal(1, new WeddingRingTargetGoal(pet));

        // Attack goals (priority 2)
        pet.goalSelector.addGoal(2, new WeddingRingSpearGoal(pet));
        pet.goalSelector.addGoal(2, new WeddingRingMaceGoal(pet));
        if (pet instanceof PathfinderMob pm) {
            pet.goalSelector.addGoal(3, new WeddingRingMeleeGoal(pm));
        }
    }

    /**
     * Remove all ring goals previously added by this manager.
     * Uses class-based removal so we don't need to hold references.
     */
    public static void removeGoals(Mob pet) {
        pet.targetSelector.removeAllGoals(g -> g instanceof WeddingRingTargetGoal);
        pet.goalSelector.removeAllGoals(g ->
            g instanceof WeddingRingSpearGoal ||
            g instanceof WeddingRingMaceGoal  ||
            g instanceof WeddingRingMeleeGoal
        );
    }
}
