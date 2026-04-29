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
import com.geckolib.renderer.GeoBlockRenderer;

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

    @SubscribeEvent
    public static void onRegisterBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
            WnirRegistries.SKULL_BEEHIVE_BE.get(),
            ctx -> new GeoBlockRenderer<>(ctx, new SkullBeehiveGeoModel())
        );
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(
            new IClientFluidTypeExtensions() {
                // In NeoForge 26, IClientFluidTypeExtensions no longer has still/flow/tint methods.
                // Provide pale-pink fog color when the player is submerged in this fluid.
                @Override
                public void modifyFogColor(net.minecraft.client.Camera camera, float partialTick,
                        net.minecraft.client.multiplayer.ClientLevel level, int renderDistance,
                        float darkenWorldAmount, org.joml.Vector4f fluidFogColor) {
                    fluidFogColor.set(1.0f, 0.70f, 0.85f, 1.0f); // pale pink
                }
            },
            WnirRegistries.MAGIC_CELLULOSE_TYPE.get()
        );
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(WnirRegistries.MOSSY_HOPPER_MENU.get(),  WnirHopperScreen.factory("mossy_hopper"));
        event.register(WnirRegistries.STEEL_HOPPER_MENU.get(),  WnirHopperScreen.factory("steel_hopper"));
        event.register(WnirRegistries.NETHER_HOPPER_MENU.get(), WnirHopperScreen.factory("nether_hopper"));
        event.register(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_MENU.get(), GrowingCrystalScreen.factory("ee_clock_budding_crystal", 0xFF55AA44));
        event.register(WnirRegistries.TELEPORTER_CRYSTAL_MENU.get(),        GrowingCrystalScreen.factory("teleporter_crystal",         0xFF9955CC));
        event.register(WnirRegistries.SKULL_BEEHIVE_MENU.get(), SkullBeehiveScreen::new);
        event.register(WnirRegistries.CELLULOSER_MENU.get(), CelluloserScreen::new);
        event.register(WnirRegistries.TRADER_MENU.get(), TraderScreen::new);
    }
}
