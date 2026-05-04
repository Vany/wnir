package com.wnir;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(WnirMod.MOD_ID)
public class WnirMod {

    public static final String MOD_ID = "wnir";
    public static final Logger LOGGER = LogManager.getLogger();

    public WnirMod(IEventBus modEventBus, ModContainer modContainer) {
        WnirRegistries.register(modEventBus);

        modEventBus.addListener(this::onRegisterPayloads);
        modEventBus.addListener(this::onRegisterCapabilities);
        modEventBus.addListener(this::onClientSetup);

        NeoForge.EVENT_BUS.addListener(BlockDeleteHandler::onChunkLoad);
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
        NeoForge.EVENT_BUS.addListener(SilenceHandler::onVanillaGameEvent);
        NeoForge.EVENT_BUS.addListener(SpawnerAgitatorBlockEntity::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onFinalizeSpawn);
        NeoForge.EVENT_BUS.addListener(WardingPostEnchantHandler::onEnchantItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WardingPostTeleportHandler::onEntityTeleport);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(this::onRegisterBrewingRecipes);
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(MOD_ID)
            .playToClient(
                TraderPayloads.TraderSyncPayload.TYPE,
                TraderPayloads.TraderSyncPayload.STREAM_CODEC,
                TraderPayloads.TraderSyncPayload::handle)
            .playToServer(
                TraderPayloads.TraderActionPayload.TYPE,
                TraderPayloads.TraderActionPayload.STREAM_CODEC,
                TraderPayloads.TraderActionPayload::handle);
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
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
            Capabilities.Item.BLOCK,
            WnirRegistries.CELLULOSER_BE.get(),
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
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            WnirRegistries.TRADER_BE.get(),
            (be, side) -> be.fluidHandler
        );
        event.registerBlockEntity(
            Capabilities.Fluid.BLOCK,
            WnirRegistries.OPAQUE_TANK_BE.get(),
            (be, side) -> be.fluidHandler
        );
        event.registerBlockEntity(
            Capabilities.Item.BLOCK,
            WnirRegistries.TRADER_BE.get(),
            (be, side) -> VanillaContainerWrapper.of(be)
        );
    }

    // Client-only: register sound handlers via FMLClientSetupEvent to avoid loading client classes on server
    private void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(SilencerHandler::onPlaySound);
        NeoForge.EVENT_BUS.addListener(WitherSilencerHandler::onPlaySound);
    }

    private void onFinalizeSpawn(FinalizeSpawnEvent event) {
        var spawnerOpt = event.getSpawner();
        if (spawnerOpt == null) return;
        // left = BlockEntity (spawner block), right = Entity (mob summoner) — only act on spawner blocks
        spawnerOpt.ifLeft(be -> {
            if (be instanceof SpawnerBlockEntity
                    && SpawnerAgitatorBlockEntity.isAgitatedSpawnerAt(be.getBlockPos())) {
                event.getEntity().setPersistenceRequired();
            }
        });
    }

    private void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        EEClockBuddingCrystalBlock.tryTransformAt(level, event.getPos());
        TeleporterCrystalBlock.tryTransformAt(level, event.getPos());
    }

    private void onRegisterBrewingRecipes(RegisterBrewingRecipesEvent event) {
        event.getBuilder().addMix(Potions.AWKWARD, Items.BOOK,         WnirRegistries.MEGA_CHANTER_POTION);
        event.getBuilder().addMix(Potions.AWKWARD, Items.GOLDEN_SWORD, WnirRegistries.MARTIAL_LIGHTNING_POTION);
        event.getBuilder().addMix(Potions.AWKWARD, Items.WHITE_WOOL,   WnirRegistries.SILENCE_POTION);
        event.getBuilder().addMix(WnirRegistries.SILENCE_POTION, Items.GLOWSTONE_DUST, WnirRegistries.STRONG_SILENCE_POTION);
    }

    // Load configs before any chunks load (spawn chunks load during ServerStarting)
    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        CelluloserConfig.load();
        BlockDeleteConfig.load();
    }

    // Re-force chunks whenever any dimension loads (handles lazy/custom dimensions too)
    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkLoaderData.get(level).forceAll(level);
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        // Pre-load personal dimension manager so biome source map is populated before any player joins
        PersonalDimensionManager.get(event.getServer());
        // configs already loaded in onServerAboutToStart
    }

    private void onServerStopping(ServerStoppingEvent event) {
        BlockDeleteConfig.pruneDeleted();
        SpawnerAgitatorBlockEntity.unbindAll();
        WardingColumnBlockEntity.clearRegistry();
        WitherSilencerBlockEntity.clearRegistry();
        ChunkLoaderData.reset();
        PersonalDimensionManager.reset();
        MouseyCompassSearchManager.reset();
    }
}
