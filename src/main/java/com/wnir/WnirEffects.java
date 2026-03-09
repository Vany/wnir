package com.wnir;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Factory for all WNIR mob effects. Each effect is a simple beneficial
 * marker — behavior is handled by event listeners, not the effect itself.
 */
public final class WnirEffects {

    private WnirEffects() {}

    /** Create a beneficial marker effect with the given particle color. */
    public static MobEffect marker(int color) {
        return new MobEffect(MobEffectCategory.BENEFICIAL, color) {};
    }
}
