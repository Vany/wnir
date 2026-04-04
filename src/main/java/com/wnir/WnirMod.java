package com.wnir;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(WnirMod.MOD_ID)
public class WnirMod {

    public static final String MOD_ID = "wnir";
    public static final Logger LOGGER = LogManager.getLogger();

    public WnirMod(IEventBus modEventBus, ModContainer modContainer) {
        WnirRegistries.register(modEventBus);

        modEventBus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                WnirRegistries.SKULL_BEEHIVE_BE.get(),
                (be, side) -> VanillaContainerWrapper.of(be)
            );
            event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                WnirRegistries.STEEL_HOPPER_BE.get(),
                (be, side) -> VanillaContainerWrapper.of(be)
            );
            event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                WnirRegistries.MOSSY_HOPPER_BE.get(),
                (be, side) -> VanillaContainerWrapper.of(be)
            );
            event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                WnirRegistries.NETHER_HOPPER_BE.get(),
                (be, side) -> VanillaContainerWrapper.of(be)
            );
            event.registerBlockEntity(
                Capabilities.Energy.BLOCK,
                WnirRegistries.CELLULOSER_BE.get(),
                (be, side) -> be.energyHandler
            );
            event.registerBlockEntity(
                Capabilities.Energy.BLOCK,
                WnirRegistries.ACCUMULATOR_BE.get(),
                (be, side) -> be.energyHandler
            );
            event.registerBlockEntity(
                Capabilities.Fluid.BLOCK,
                WnirRegistries.CELLULOSER_BE.get(),
                (be, side) -> be.fluidHandler
            );
            event.registerBlockEntity(
                Capabilities.Fluid.BLOCK,
                WnirRegistries.SPAWNER_BE.get(),
                (be, side) -> be.fluidHandler
            );
        });

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
        NeoForge.EVENT_BUS.addListener(OverCrookingHandler::onBlockDrops);
        NeoForge.EVENT_BUS.addListener(MouseyCompassItem::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(WirelessFuelItem::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(WirelessFuelItem::onServerTick);
        NeoForge.EVENT_BUS.addListener(WirelessFuelItem::onFurnaceFuelBurnTime);
        NeoForge.EVENT_BUS.addListener(
            EventPriority.LOWEST, WardingPostTeleportHandler::onEntityTeleport
        );
        // Client-only: register sound handlers via FMLClientSetupEvent to avoid loading client classes on server
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> {
            NeoForge.EVENT_BUS.addListener(SilencerHandler::onPlaySound);
            NeoForge.EVENT_BUS.addListener(WitherSilencerHandler::onPlaySound);
        });
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) -> {
                if (!(e.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
                EEClockBuddingCrystalBlock.tryTransformAt(level, e.getPos());
                TeleporterCrystalBlock.tryTransformAt(level, e.getPos());
            }
        );

        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent e) -> {
                e.getBuilder().addMix(
                    net.minecraft.world.item.alchemy.Potions.AWKWARD,
                    net.minecraft.world.item.Items.BOOK,
                    WnirRegistries.MEGA_CHANTER_POTION
                );
                e.getBuilder().addMix(
                    net.minecraft.world.item.alchemy.Potions.AWKWARD,
                    net.minecraft.world.item.Items.GOLDEN_SWORD,
                    WnirRegistries.MARTIAL_LIGHTNING_POTION
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
        // Pre-load personal dimension manager so biome source map is populated before any player joins
        PersonalDimensionManager.get(server);
    }

    private void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SpawnerAgitatorBlockEntity.unbindAll();
        WardingColumnBlockEntity.clearRegistry();
        WitherSilencerBlockEntity.clearRegistry();
        ChunkLoaderData.reset();
        PersonalDimensionManager.reset();
        MouseyCompassSearchManager.reset();
    }
}
