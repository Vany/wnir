package com.wnir;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(WnirMod.MOD_ID)
public class WnirMod {

    public static final String MOD_ID = "wnir";

    public WnirMod(IEventBus modEventBus, ModContainer modContainer) {
        WnirRegistries.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(MartialLightningHandler::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(DeadBlowHandler::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(HomingArcheryHandler::onArrowLoose);
        NeoForge.EVENT_BUS.addListener(HomingArcheryHandler::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(MegaChanterHandler::onAnvilUpdate);
        NeoForge.EVENT_BUS.addListener(InsaneLightHandler::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(InsaneLightHandler::onLivingChangeTarget);
        NeoForge.EVENT_BUS.addListener(SwiftStrikeHandler::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(AccelerateHandler::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(ToughnessHandler::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(
            EventPriority.LOWEST, WardingPostTeleportHandler::onEntityTeleport
        );

        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent e) -> {
                e.getBuilder().addMix(
                    net.minecraft.world.item.alchemy.Potions.AWKWARD,
                    net.minecraft.world.item.Items.BOOK,
                    WnirRegistries.MEGA_CHANTER_POTION
                );
            }
        );

        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.server.ServerStartingEvent e) -> onServerStarting(e)
        );
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> onServerStopping(e)
        );
    }

    private void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        var server = event.getServer();
        for (var level : server.getAllLevels()) {
            ChunkLoaderData.get(level).forceAll(level);
        }
    }

    private void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SpawnerAgitatorBlockEntity.unbindAll();
        WardingColumnBlockEntity.clearRegistry();
        ChunkLoaderData.reset();
    }
}
