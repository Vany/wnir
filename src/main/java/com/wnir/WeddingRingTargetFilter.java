package com.wnir;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-pet target reachability filter.
 *
 * Every SAMPLE_TICKS, checks whether the pet is making progress toward its current target:
 * - distance decreased by ≥ 1 block, OR
 * - pet received damage from the target (it's at least interacting)
 *
 * After BAD_SAMPLES_THRESHOLD consecutive no-progress samples the target is blacklisted for
 * BLACKLIST_TICKS (60 s) and immediately dropped. Blacklisted mobs are skipped during target
 * selection and cause an instant target-drop if somehow re-selected.
 */
public final class WeddingRingTargetFilter {

    private static final int  SAMPLE_TICKS          = 40;    // 2 s between distance samples
    private static final int  BAD_SAMPLES_THRESHOLD = 2;     // consecutive bad samples → blacklist
    private static final long BLACKLIST_TICKS        = 1200L; // 60 s in game ticks

    private record Track(UUID targetUUID, double bestDist, int badSamples, boolean damagedThisWindow) {}

    private static final ConcurrentHashMap<UUID, Track>   TRACKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> TIMERS   = new ConcurrentHashMap<>();
    // petUUID → (targetUUID → expiryGameTick)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> BLACKLIST =
        new ConcurrentHashMap<>();

    private WeddingRingTargetFilter() {}

    /** Called every server tick for each bound, alive pet. */
    public static void onServerTick(Mob pet, long gameTime) {
        UUID petUUID = pet.getUUID();

        int timer = TIMERS.merge(petUUID, 1, Integer::sum);
        if (timer < SAMPLE_TICKS) return;
        TIMERS.put(petUUID, 0);

        LivingEntity target = pet.getTarget();
        if (target == null || !target.isAlive()) {
            TRACKING.remove(petUUID);
            return;
        }

        UUID   targetUUID   = target.getUUID();
        double currentDist  = pet.distanceTo(target);
        Track  track        = TRACKING.get(petUUID);

        if (track == null || !targetUUID.equals(track.targetUUID())) {
            // New or switched target — start fresh
            TRACKING.put(petUUID, new Track(targetUUID, currentDist, 0, false));
            return;
        }

        boolean distanceReduced = currentDist < track.bestDist() - 1.0;
        boolean damagedByTarget = track.damagedThisWindow();

        if (distanceReduced || damagedByTarget) {
            double newBest = Math.min(currentDist, track.bestDist());
            TRACKING.put(petUUID, new Track(targetUUID, newBest, 0, false));
        } else {
            int bad = track.badSamples() + 1;
            if (bad >= BAD_SAMPLES_THRESHOLD) {
                BLACKLIST.computeIfAbsent(petUUID, k -> new ConcurrentHashMap<>())
                         .put(targetUUID, gameTime + BLACKLIST_TICKS);
                TRACKING.remove(petUUID);
                WnirMod.LOGGER.info("[TargetFilter] {} blacklisted target {} (unreachable) for 60s",
                    petUUID.toString().substring(0, 8), targetUUID.toString().substring(0, 8));
                pet.setTarget(null);
            } else {
                TRACKING.put(petUUID, new Track(targetUUID, track.bestDist(), bad, false));
            }
        }
    }

    /** Called when the pet takes damage from its current target. Clears the bad-sample counter. */
    public static void onDamagedByTarget(UUID petUUID) {
        TRACKING.computeIfPresent(petUUID, (k, t) ->
            new Track(t.targetUUID(), t.bestDist(), t.badSamples(), true));
    }

    /** Returns true if the given target is currently blacklisted for this pet. */
    public static boolean isBlacklisted(UUID petUUID, UUID targetUUID, long gameTime) {
        var bl = BLACKLIST.get(petUUID);
        if (bl == null) return false;
        Long expiry = bl.get(targetUUID);
        if (expiry == null) return false;
        if (gameTime >= expiry) {
            bl.remove(targetUUID);
            return false;
        }
        return true;
    }

    /** Cleans up all state for a pet (call on death / unbind). */
    public static void clear(UUID petUUID) {
        TRACKING.remove(petUUID);
        TIMERS.remove(petUUID);
        BLACKLIST.remove(petUUID);
    }
}
