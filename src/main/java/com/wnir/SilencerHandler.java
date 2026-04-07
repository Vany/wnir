package com.wnir;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
        double y = sound.getY();
        double z = sound.getZ();

        ResourceKey<Level> key = mc.level.dimension();
        Set<BlockPos> registry = WardingColumnBlockEntity.silencerRegistry.get(key);
        if (registry == null || registry.isEmpty()) return;

        for (BlockPos pos : registry) {
            BlockEntity be = mc.level.getBlockEntity(pos);
            if (!(be instanceof WardingColumnBlockEntity wbe)) continue;
            if (!wbe.isBottomOfColumn || wbe.silencerCount == 0) continue;

            double dx = x - (pos.getX() + 0.5);
            double dy = y - (pos.getY() + 0.5);
            double dz = z - (pos.getZ() + 0.5);
            double r = wbe.totalRadius;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                event.setSound(new DelegateSoundInstance(sound, 0.1f));
                return;
            }
        }
    }
}
