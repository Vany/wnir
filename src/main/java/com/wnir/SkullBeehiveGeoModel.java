package com.wnir;

import net.minecraft.resources.Identifier;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

public class SkullBeehiveGeoModel extends GeoModel<SkullBeehiveBlockEntity> {

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "skull_beehive");
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/block/skull_beehive.png");
    }

    @Override
    public Identifier getAnimationResource(SkullBeehiveBlockEntity animatable) {
        return Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "skull_beehive");
    }
}
