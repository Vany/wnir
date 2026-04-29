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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        ResourceKey<Level> dim = mc.level.dimension();
        Set<BlockPos> positions = WitherSilencerBlockEntity.registry.get(dim);
        if (positions == null || positions.isEmpty()) return;

        if (WITHER_SPAWN.equals(id) || WITHER_DEATH.equals(id)) {
            // Global sounds — suppress if any silencer exists in this dimension.
            event.setSound(new DelegateSoundInstance(sound, 0f));
        } else if (id.getNamespace().equals("minecraft") && id.getPath().startsWith("music_disc.")) {
            // Jukebox sound — suppress if a silencer is in the same chunk as the source.
            int cx = (int) Math.floor(sound.getX()) >> 4;
            int cz = (int) Math.floor(sound.getZ()) >> 4;
            for (BlockPos pos : positions) {
                if ((pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz) {
                    event.setSound(new DelegateSoundInstance(sound, 0f));
                    break;
                }
            }
        }
    }
}
