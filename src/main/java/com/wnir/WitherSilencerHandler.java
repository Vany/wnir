package com.wnir;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Cancels Wither spawn and death sounds when any Wither Silencer block exists
 * in the current dimension. These sounds are global (no world position), so a
 * single placed block suppresses them dimension-wide.
 *
 * CLIENT-SIDE ONLY. Registered via FMLClientSetupEvent.
 */
public final class WitherSilencerHandler {

    private static final Identifier WITHER_SPAWN = Identifier.withDefaultNamespace("entity.wither.spawn");
    private static final Identifier WITHER_DEATH  = Identifier.withDefaultNamespace("entity.wither.death");

    private WitherSilencerHandler() {}

    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) return;

        Identifier id = sound.getIdentifier();
        if (!WITHER_SPAWN.equals(id) && !WITHER_DEATH.equals(id)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Wither spawn/death sounds are global (no meaningful position).
        // Suppress if any Wither Silencer exists in the current dimension.
        ResourceKey<Level> dim = mc.level.dimension();
        Set<BlockPos> positions = WitherSilencerBlockEntity.registry.get(dim);
        if (positions == null || positions.isEmpty()) return;

        event.setSound(new DelegateSoundInstance(sound, 0f));
    }
}
