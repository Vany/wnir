package com.wnir;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

// bus= is ignored in NeoForge FML 4; routing is automatic based on IModBusEvent.
@EventBusSubscriber(modid = WnirMod.MOD_ID, value = Dist.CLIENT)
public class WnirClientSetup {

    @SubscribeEvent
    public static void onRegisterSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(
            Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "blue_sticky_tape"),
            BlueStickyTapeRenderer.MAP_CODEC
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent
    public static void onRegisterBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // GeckoLib 5.4.5 is missing BlockEntityRenderState from interface_injections.json,
        // so we can't subclass GeoBlockRenderer — instantiate directly with raw types.
        event.registerBlockEntityRenderer(
            (net.minecraft.world.level.block.entity.BlockEntityType) WnirRegistries.SKULL_BEEHIVE_BE.get(),
            (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider) ctx -> new GeoBlockRenderer(new SkullBeehiveGeoModel())
        );
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(
            new IClientFluidTypeExtensions() {
                // Reuse vanilla water sprites (always in atlas, properly animated).
                // Tint color gives the fluid its pale-pink appearance.
                private static final Identifier STILL   = Identifier.withDefaultNamespace("block/water_still");
                private static final Identifier FLOWING = Identifier.withDefaultNamespace("block/water_flow");

                @Override public Identifier getStillTexture()   { return STILL; }
                @Override public Identifier getFlowingTexture() { return FLOWING; }
                @Override public int getTintColor() { return 0xFFFFB3D9; } // pale pink ARGB
            },
            WnirRegistries.MAGIC_CELLULOSE_TYPE.get()
        );
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(WnirRegistries.MOSSY_HOPPER_MENU.get(), MossyHopperScreen::new);
        event.register(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_MENU.get(), EEClockBuddingCrystalScreen::new);
        event.register(WnirRegistries.TELEPORTER_CRYSTAL_MENU.get(), TeleporterCrystalScreen::new);
        event.register(WnirRegistries.SKULL_BEEHIVE_MENU.get(), SkullBeehiveScreen::new);
        event.register(WnirRegistries.CELLULOSER_MENU.get(), CelluloserScreen::new);
    }
}
