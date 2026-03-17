package com.wnir;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;

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
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(WnirRegistries.MOSSY_HOPPER_MENU.get(), MossyHopperScreen::new);
        event.register(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_MENU.get(), EEClockBuddingCrystalScreen::new);
        event.register(WnirRegistries.TELEPORTER_CRYSTAL_MENU.get(), TeleporterCrystalScreen::new);
    }
}
