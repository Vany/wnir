package com.wnir;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the per-pet LLM sessions each server tick:
 * - World summary every 200 ticks
 * - Particle effects based on session state
 * - Combat-end detection to flush queued combat events
 * - Sleep compaction trigger
 * - Session lifecycle (create on entity join, remove on death/server stop)
 */
public final class WeddingRingLlmHandler {

    private static final int WORLD_SUMMARY_INTERVAL = 200;

    /** session UUID → tick counter for world summary. Accessed only from server tick. */
    private static final ConcurrentHashMap<UUID, WeddingRingLlmSession> SESSIONS   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SUMMARY_TIMERS            = new ConcurrentHashMap<>();
    /** Tracks whether each pet was in combat last tick (for end-of-combat detection). */
    private static final ConcurrentHashMap<UUID, Boolean> WAS_IN_COMBAT             = new ConcurrentHashMap<>();

    private WeddingRingLlmHandler() {}

    // ── Session lifecycle ────────────────────────────────────────────────────

    /** Called from WnirMod.onEntityJoinLevel — creates a session if the pet is LLM-enabled. */
    public static void onPetJoin(Mob pet) {
        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return;
        UUID uid = pet.getUUID();
        SESSIONS.computeIfAbsent(uid, WeddingRingLlmSession::new);
    }

    /** Called from WeddingRingTickHandler.onPetDeath. */
    public static void onPetDeath(UUID petUUID) {
        WeddingRingLlmSession session = SESSIONS.remove(petUUID);
        if (session != null) session.shutdown();
        SUMMARY_TIMERS.remove(petUUID);
        WAS_IN_COMBAT.remove(petUUID);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.values().forEach(WeddingRingLlmSession::shutdown);
        SESSIONS.clear();
        SUMMARY_TIMERS.clear();
        WAS_IN_COMBAT.clear();
    }

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) return;
                WeddingRingData data = WeddingRingData.get(mob);
                if (data == null) return;

                UUID uid = mob.getUUID();
                WeddingRingLlmSession session = SESSIONS.computeIfAbsent(uid, WeddingRingLlmSession::new);

                tickParticles(session, mob, level);
                tickCombatTracking(session, server, mob, uid);
                tickWorldSummary(session, server, mob, data, uid, level);
            });
        }
    }

    private static void tickParticles(WeddingRingLlmSession session, Mob pet, ServerLevel level) {
        Vec3 head = pet.getEyePosition().add(0, 0.5, 0);
        switch (session.particleState) {
            case THINKING -> level.sendParticles(
                ParticleTypes.SOUL_FIRE_FLAME,
                head.x, head.y, head.z,
                2, 0.15, 0.15, 0.15, 0.01);
            case DONE_BURST -> {
                if (session.burstTicksLeft > 0) {
                    level.sendParticles(ParticleTypes.END_ROD,
                        head.x, head.y, head.z,
                        20, 0.3, 0.3, 0.3, 0.1);
                    session.burstTicksLeft--;
                    if (session.burstTicksLeft <= 0) session.particleState = WeddingRingLlmSession.ParticleState.IDLE;
                }
            }
            case TOOL_BURST -> {
                if (session.burstTicksLeft > 0) {
                    Vec3 petHead = pet.getEyePosition();
                    level.sendParticles(ParticleTypes.ENCHANT,
                        petHead.x, petHead.y, petHead.z,
                        10, 0.2, 0.2, 0.2, 0.1);
                    session.burstTicksLeft--;
                }
                // state resets back to THINKING in the session's runCallLoop
            }
            default -> {}
        }
    }

    private static void tickCombatTracking(WeddingRingLlmSession session, MinecraftServer server,
                                            Mob pet, UUID uid) {
        boolean inCombat = pet.getTarget() != null;
        boolean wasInCombat = WAS_IN_COMBAT.getOrDefault(uid, false);
        WAS_IN_COMBAT.put(uid, inCombat);

        // Combat just ended → flush combat events (only if AI enabled)
        if (wasInCombat && !inCombat) {
            WeddingRingData data = WeddingRingData.get(pet);
            if (data != null && data.isAiEnabled()) {
                session.flushCombatEvents(server, pet);
            }
        }
    }

    private static void tickWorldSummary(WeddingRingLlmSession session, MinecraftServer server,
                                          Mob pet, WeddingRingData data, UUID uid, ServerLevel level) {
        if (pet.getTarget() != null) {
            SUMMARY_TIMERS.put(uid, 0); // reset timer during combat
            return;
        }
        int timer = SUMMARY_TIMERS.getOrDefault(uid, 0) + 1;
        SUMMARY_TIMERS.put(uid, timer);
        if (timer < WORLD_SUMMARY_INTERVAL) return;
        SUMMARY_TIMERS.put(uid, 0);

        if (!data.isAiEnabled()) return;
        session.addTrigger(server, buildWorldSummary(pet, data, server, level));
    }

    private static String buildWorldSummary(Mob pet, WeddingRingData data, MinecraftServer server, ServerLevel level) {
        // Distance and direction to owner
        String ownerInfo = "owner offline";
        ServerPlayer owner = server.getPlayerList().getPlayer(data.getOwnerUUID());
        if (owner != null && owner.level() == level) {
            double dist = pet.distanceTo(owner);
            int dx = (int)(owner.getX() - pet.getX());
            int dz = (int)(owner.getZ() - pet.getZ());
            String card = cardinal(dx, dz);
            ownerInfo = "%.1f blocks %s".formatted(dist, card);
        }

        // Health and hunger
        String health = "%.1f/%.1f ♥".formatted(pet.getHealth(), pet.getMaxHealth());
        String hunger = data.getFoodLevel() + "/20";

        // Time of day (game time % 24000 = position within the current day cycle)
        long time = level.getGameTime() % 24000L;
        String timeStr;
        if      (time < 1000)  timeStr = "Dawn";
        else if (time < 6000)  timeStr = "Morning";
        else if (time < 7000)  timeStr = "Noon";
        else if (time < 12000) timeStr = "Afternoon";
        else if (time < 13000) timeStr = "Dusk";
        else if (time < 18000) timeStr = "Night";
        else                    timeStr = "Midnight";

        // Weather
        String weather = level.isThundering() ? "Thunder" : level.isRaining() ? "Rain" : "Clear";

        // Nearby hostiles
        var hostiles = level.getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity.class,
            pet.getBoundingBox().inflate(32),
            e -> e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive() && e != pet
        );
        String hostileStr;
        if (hostiles.isEmpty()) {
            hostileStr = "none";
        } else {
            var closest = hostiles.stream().min(
                java.util.Comparator.comparingDouble(e -> e.distanceTo(pet))).orElse(null);
            String closestStr = closest != null
                ? "%s %.1fm %s".formatted(
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(closest.getType()).getPath(),
                    closest.distanceTo(pet),
                    cardinal((int)(closest.getX() - pet.getX()), (int)(closest.getZ() - pet.getZ())))
                : "";
            hostileStr = "%d (closest: %s)".formatted(hostiles.size(), closestStr);
        }

        return """
            [World update]
            Distance to owner: %s
            Your health: %s  |  Hunger: %s
            Time of day: %s
            Weather: %s
            Nearby hostiles: %s
            """.formatted(ownerInfo, health, hunger, timeStr, weather, hostileStr).trim();
    }

    // ── Combat event hooks (called from WeddingRingAttackHandler) ────────────

    /** Call when the pet kills a mob. */
    public static void onPetKill(Mob pet, net.minecraft.world.entity.LivingEntity victim) {
        WeddingRingLlmSession session = SESSIONS.get(pet.getUUID());
        if (session == null) return;
        String typePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getKey(victim.getType()).getPath();
        session.addCombatEvent("[Combat] I defeated a " + typePath + ".");
    }

    /** Call when the pet takes a big hit. Threshold: 3 HP. */
    public static void onPetDamaged(Mob pet, float amount, net.minecraft.world.entity.LivingEntity attacker) {
        if (amount < 3f) return;
        WeddingRingLlmSession session = SESSIONS.get(pet.getUUID());
        if (session == null) return;
        String typePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getKey(attacker.getType()).getPath();
        session.addCombatEvent("[Combat] I took %.1f damage from %s.".formatted(amount, typePath));
    }

    /** Call when a mob starts targeting the pet (from WeddingRingTargetGoal.onMobTargetsPet). */
    public static void onTargetedByMob(Mob pet, net.minecraft.world.entity.LivingEntity attacker) {
        WeddingRingLlmSession session = SESSIONS.get(pet.getUUID());
        if (session == null) return;
        String typePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getKey(attacker.getType()).getPath();
        session.addCombatEvent("[Combat] I am being attacked by " + typePath + ".");
    }

    // ── Sleep compaction hook ────────────────────────────────────────────────

    /**
     * Called on SleepFinishedTimeEvent — compacts all active pet sessions.
     */
    public static void onSleepFinished(MinecraftServer server) {
        SESSIONS.forEach((uid, session) -> session.compact(server));
    }

    // ── Chat hook ────────────────────────────────────────────────────────────

    /**
     * Called on ServerChatEvent. Queues the message as a trigger for all active LLM sessions.
     */
    public static void onServerChat(MinecraftServer server, String senderName, String message) {
        String trigger = senderName + ": " + message;
        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof Mob mob)) return;
                WeddingRingData data = WeddingRingData.get(mob);
                if (data == null || !data.isAiEnabled()) return;
                WeddingRingLlmSession session = SESSIONS.get(mob.getUUID());
                if (session != null) session.addTrigger(server, trigger);
            });
        }
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    private static String cardinal(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        String[] dirs = {"N","NE","E","SE","S","SW","W","NW"};
        return dirs[(int)((angle + 22.5) / 45) % 8];
    }
}
