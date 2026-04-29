package com.wnir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.VanillaGameEvent;

/**
 * Potion of Silence — reduces sculk sensor / warden detection radius.
 *
 * When the player has the Silence effect, VanillaGameEvent is cancelled and
 * re-dispatched only to listeners within a reduced radius:
 *   Tier 1 (amplifier 0): listener_radius / 2
 *   Tier 2 (amplifier 1): listener_radius / 4
 *
 * SERVER-SIDE ONLY. Registered on NeoForge.EVENT_BUS.
 */
public final class SilenceHandler {

    private SilenceHandler() {}

    public static void onVanillaGameEvent(VanillaGameEvent event) {
        if (!(event.getCause() instanceof Player player)) return;
        if (!player.hasEffect(WnirRegistries.SILENCE)) return;

        event.setCanceled(true);

        MobEffectInstance inst = player.getEffect(WnirRegistries.SILENCE);
        if (inst == null) return;
        int divisor = 1 << (inst.getAmplifier() + 1); // amplifier 0 -> /2, amplifier 1 -> /4

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        Vec3 eventPos = event.getEventPosition();
        Holder<GameEvent> gameEvent = event.getVanillaEvent();
        GameEvent.Context context = event.getContext();

        int notificationRadius = gameEvent.value().notificationRadius();
        BlockPos blockPos = BlockPos.containing(eventPos);
        int minSX = SectionPos.blockToSectionCoord(blockPos.getX() - notificationRadius);
        int minSY = SectionPos.blockToSectionCoord(blockPos.getY() - notificationRadius);
        int minSZ = SectionPos.blockToSectionCoord(blockPos.getZ() - notificationRadius);
        int maxSX = SectionPos.blockToSectionCoord(blockPos.getX() + notificationRadius);
        int maxSY = SectionPos.blockToSectionCoord(blockPos.getY() + notificationRadius);
        int maxSZ = SectionPos.blockToSectionCoord(blockPos.getZ() + notificationRadius);

        List<GameEvent.ListenerInfo> byDistanceQueue = new ArrayList<>();

        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sz = minSZ; sz <= maxSZ; sz++) {
                var chunk = serverLevel.getChunkSource().getChunkNow(sx, sz);
                if (chunk == null) continue;
                for (int sy = minSY; sy <= maxSY; sy++) {
                    chunk.getListenerRegistry(sy).visitInRangeListeners(
                        gameEvent, eventPos, context,
                        (listener, listenerPos) -> {
                            double reducedR = (double) listener.getListenerRadius() / divisor;
                            if (eventPos.distanceToSqr(listenerPos) > reducedR * reducedR) return;
                            if (listener.getDeliveryMode() == GameEventListener.DeliveryMode.BY_DISTANCE) {
                                byDistanceQueue.add(
                                    new GameEvent.ListenerInfo(gameEvent, eventPos, context, listener, listenerPos)
                                );
                            } else {
                                listener.handleGameEvent(serverLevel, gameEvent, context, eventPos);
                            }
                        }
                    );
                }
            }
        }

        if (!byDistanceQueue.isEmpty()) {
            Collections.sort(byDistanceQueue);
            for (var info : byDistanceQueue) {
                info.recipient().handleGameEvent(serverLevel, info.gameEvent(), info.context(), info.source());
            }
        }
    }
}
