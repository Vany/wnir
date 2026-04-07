package com.wnir;

import java.lang.reflect.Field;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reflection-based accessor for TrialSpawnerStateData.cooldownEndsAt (package-private long).
 * Used by SpawnerAgitatorBlockEntity to accelerate the trial spawner restart timer.
 */
public final class TrialSpawnerAccessor {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Field cooldownEndsAtField;

    static {
        Field f = null;
        try {
            f = TrialSpawnerStateData.class.getDeclaredField("cooldownEndsAt");
            f.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.error("TrialSpawnerAccessor: could not find cooldownEndsAt in TrialSpawnerStateData", e);
        }
        cooldownEndsAtField = f;
    }

    private TrialSpawnerAccessor() {}

    public static boolean isAvailable() {
        return cooldownEndsAtField != null;
    }

    public static long getCooldownEndsAt(TrialSpawnerStateData data) {
        if (cooldownEndsAtField == null) return 0L;
        try {
            return cooldownEndsAtField.getLong(data);
        } catch (Exception e) {
            LOGGER.error("TrialSpawnerAccessor: failed to read cooldownEndsAt", e);
            return 0L;
        }
    }

    public static void setCooldownEndsAt(TrialSpawnerStateData data, long value) {
        if (cooldownEndsAtField == null) return;
        try {
            cooldownEndsAtField.setLong(data, value);
        } catch (Exception e) {
            LOGGER.error("TrialSpawnerAccessor: failed to set cooldownEndsAt", e);
        }
    }

    /** Subtracts {@code ticks} from cooldownEndsAt, advancing the restart timer. */
    public static void advanceCooldown(TrialSpawnerStateData data, long ticks) {
        if (cooldownEndsAtField == null) return;
        try {
            long current = cooldownEndsAtField.getLong(data);
            cooldownEndsAtField.setLong(data, current - ticks);
        } catch (Exception e) {
            LOGGER.error("TrialSpawnerAccessor: failed to advance cooldownEndsAt", e);
        }
    }
}
