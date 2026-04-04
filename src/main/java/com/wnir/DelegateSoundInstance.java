package com.wnir;

import javax.annotation.Nullable;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * Wraps a SoundInstance and scales its volume by a factor.
 * Use factor=0f to silence completely, factor<1f to attenuate.
 */
final class DelegateSoundInstance implements SoundInstance {

    private final SoundInstance delegate;
    private final float factor;

    DelegateSoundInstance(SoundInstance delegate, float factor) {
        this.delegate = delegate;
        this.factor = factor;
    }

    @Override public Identifier         getIdentifier()               { return delegate.getIdentifier(); }
    @Override @Nullable
    public WeighedSoundEvents            resolve(SoundManager manager) { return delegate.resolve(manager); }
    @Override public Sound               getSound()                    { return delegate.getSound(); }
    @Override public SoundSource         getSource()                   { return delegate.getSource(); }
    @Override public boolean             isLooping()                   { return factor > 0f && delegate.isLooping(); }
    @Override public boolean             isRelative()                  { return delegate.isRelative(); }
    @Override public int                 getDelay()                    { return delegate.getDelay(); }
    @Override public float               getVolume()                   { return delegate.getVolume() * factor; }
    @Override public float               getPitch()                    { return delegate.getPitch(); }
    @Override public double              getX()                        { return delegate.getX(); }
    @Override public double              getY()                        { return delegate.getY(); }
    @Override public double              getZ()                        { return delegate.getZ(); }
    @Override public Attenuation         getAttenuation()              { return delegate.getAttenuation(); }
    @Override public boolean             canStartSilent()              { return factor == 0f || delegate.canStartSilent(); }
    @Override public boolean             canPlaySound()                { return factor > 0f && delegate.canPlaySound(); }
}
