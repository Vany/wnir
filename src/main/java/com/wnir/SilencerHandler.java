package com.wnir;

import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Attenuates entity sounds by 90% when the sound source is within the radius
 * of any active silencer column (one or more SilencerPostBlocks stacked).
 *
 * CLIENT-SIDE ONLY. Registered via FMLClientSetupEvent.
 * Uses WardingColumnBlockEntity.silencerRegistry which is populated on both sides.
 */
public final class SilencerHandler {

    private SilencerHandler() {}

    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        double x = sound.getX();
        double z = sound.getZ();

        ResourceKey<Level> key = mc.level.dimension();
        Set<BlockPos> registry = WardingColumnBlockEntity.silencerRegistry.get(key);
        if (registry == null || registry.isEmpty()) return;

        for (BlockPos pos : registry) {
            BlockEntity be = mc.level.getBlockEntity(pos);
            if (!(be instanceof WardingColumnBlockEntity wbe)) continue;
            if (!wbe.isBottomOfColumn || wbe.silencerCount == 0) continue;

            double dx = x - (pos.getX() + 0.5);
            double dz = z - (pos.getZ() + 0.5);
            double r = wbe.totalRadius;
            if (dx * dx + dz * dz <= r * r) {
                event.setSound(new QuietSoundInstance(sound, 0.1f));
                return;
            }
        }
    }

    /** Delegates all SoundInstance methods to the original, but reduces volume by the given factor. */
    private static final class QuietSoundInstance implements SoundInstance {
        private final SoundInstance delegate;
        private final float factor;

        QuietSoundInstance(SoundInstance delegate, float factor) {
            this.delegate = delegate;
            this.factor = factor;
        }

        @Override public Identifier    getIdentifier()                    { return delegate.getIdentifier(); }
        @Override @Nullable
        public WeighedSoundEvents       resolve(SoundManager manager)     { return delegate.resolve(manager); }
        @Override public Sound          getSound()                        { return delegate.getSound(); }
        @Override public SoundSource    getSource()                       { return delegate.getSource(); }
        @Override public boolean        isLooping()                       { return delegate.isLooping(); }
        @Override public boolean        isRelative()                      { return delegate.isRelative(); }
        @Override public int            getDelay()                        { return delegate.getDelay(); }
        @Override public float          getVolume()                       { return delegate.getVolume() * factor; }
        @Override public float          getPitch()                        { return delegate.getPitch(); }
        @Override public double         getX()                            { return delegate.getX(); }
        @Override public double         getY()                            { return delegate.getY(); }
        @Override public double         getZ()                            { return delegate.getZ(); }
        @Override public Attenuation    getAttenuation()                  { return delegate.getAttenuation(); }
        @Override public boolean        canStartSilent()                  { return delegate.canStartSilent(); }
        @Override public boolean        canPlaySound()                    { return delegate.canPlaySound(); }
    }
}
