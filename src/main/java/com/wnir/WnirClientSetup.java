package com.wnir;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
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
    public static void onRegisterFluidModels(RegisterFluidModelsEvent event) {
        event.register(
            new FluidModel.Unbaked(
                new Material(Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "block/magic_cellulose_still")),
                new Material(Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "block/magic_cellulose_flow")),
                null,  // no overlay
                null   // no tint source — texture has color baked in
            ),
            WnirRegistries.MAGIC_CELLULOSE_STILL,
            WnirRegistries.MAGIC_CELLULOSE_FLOWING
        );
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(
            new IClientFluidTypeExtensions() {
                public org.joml.Vector3f modifyFogColor(net.minecraft.client.Camera camera, float partialTick,
                        net.minecraft.client.multiplayer.ClientLevel level, int renderDistance,
                        float darkenWorldAmount, org.joml.Vector3f fluidFogColor) {
                    return fluidFogColor.set(1.0f, 0.70f, 0.85f);
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
        event.register(WnirRegistries.EE_CLOCK_ZYGOTE_MENU.get(),    ZygoteScreen::new);
        event.register(WnirRegistries.TELEPORTER_ZYGOTE_MENU.get(), ZygoteScreen::new);
        event.register(WnirRegistries.WARDING_ZYGOTE_MENU.get(),    ZygoteScreen::new);
        event.register(WnirRegistries.SKULL_BEEHIVE_MENU.get(), SkullBeehiveScreen::new);
        event.register(WnirRegistries.CELLULOSER_MENU.get(), CelluloserScreen::new);
        event.register(WnirRegistries.TRADER_MENU.get(), TraderScreen::new);
    }
}
