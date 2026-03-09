package com.wnir;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

// bus= is ignored in NeoForge FML 4; routing is automatic based on IModBusEvent.
@EventBusSubscriber(modid = WnirMod.MOD_ID, value = Dist.CLIENT)
public class WnirClientSetup {

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(WnirRegistries.MOSSY_HOPPER_MENU.get(), MossyHopperScreen::new);
    }
}
