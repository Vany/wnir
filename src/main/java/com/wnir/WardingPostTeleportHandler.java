package com.wnir;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * Redirects teleportation whose destination falls within range of a warding
 * column that contains at least one TeleporterInhibitorBlock.
 * The teleport is NOT cancelled — the destination is changed to on top of the
 * inhibitor block, so the entity still arrives but outside the protected zone.
 *
 * Uses WardingColumnBlockEntity.inhibitorRegistry for O(n) lookup instead of
 * scanning all blocks in radius.
 * Skips player-commanded teleports (/tp, spreadplayers).
 */
public final class WardingPostTeleportHandler {

    private WardingPostTeleportHandler() {}

    public static void onEntityTeleport(EntityTeleportEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event instanceof EntityTeleportEvent.TeleportCommand) return;
        if (event instanceof EntityTeleportEvent.SpreadPlayersCommand) return;

        ServerLevel level = (ServerLevel) event.getEntity().level();
        ResourceKey<Level> key = level.dimension();
        Set<BlockPos> registry = WardingColumnBlockEntity.inhibitorRegistry.get(key);
        if (registry == null || registry.isEmpty()) return;

        double tx = event.getTargetX();
        double tz = event.getTargetZ();

        for (BlockPos pos : registry) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof WardingColumnBlockEntity wbe)) continue;
            if (!wbe.isBottomOfColumn || !wbe.hasInhibit) continue;

            double dx = tx - (pos.getX() + 0.5);
            double dz = tz - (pos.getZ() + 0.5);
            double r = wbe.totalRadius;
            if (dx * dx + dz * dz <= r * r) {
                // Redirect destination to on top of the inhibitor block.
                event.setTargetX(pos.getX() + 0.5);
                event.setTargetY(pos.getY() + 1.0);
                event.setTargetZ(pos.getZ() + 0.5);
                return;
            }
        }
    }
}
