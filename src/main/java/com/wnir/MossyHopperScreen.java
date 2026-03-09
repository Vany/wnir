package com.wnir;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screen for the Mossy Hopper — 2 rows of 5 hopper slots + standard player inventory.
 *
 * Background: custom 256×256 texture assembled from vanilla hopper slices:
 *   y=0..37   — header + hopper slot row 0 chrome
 *   y=38..55  — hopper slot row 1 (duplicate of row 0 slot pixels)
 *   y=56..63  — divider chrome
 *   y=64..148 — player inventory + hotbar + bottom border
 */
public class MossyHopperScreen extends AbstractContainerScreen<MossyHopperMenu> {

    private static final Identifier BACKGROUND =
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/mossy_hopper.png");

    public MossyHopperScreen(MossyHopperMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth  = 176;
        this.imageHeight = MossyHopperMenu.IMAGE_HEIGHT;  // 149
        this.inventoryLabelY = 53;  // 11px above player inventory (y=64)
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Exact pattern used by vanilla HopperScreen in 1.21.11:
        // blit(RenderPipeline, Identifier, x, y, u, v, width, height, texW, texH)
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
    }
}
