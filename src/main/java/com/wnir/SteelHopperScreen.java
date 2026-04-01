package com.wnir;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class SteelHopperScreen extends AbstractContainerScreen<SteelHopperMenu> {

    private static final Identifier BACKGROUND =
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/steel_hopper.png");

    public SteelHopperScreen(SteelHopperMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth  = 176;
        this.imageHeight = SteelHopperMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = 53;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
    }
}
