package com.wnir;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Shared screen for all 10-slot WNIR hoppers (Mossy, Steel, Nether).
 * Each variant supplies its own background texture at construction time.
 */
public class WnirHopperScreen extends AbstractContainerScreen<WnirHopperMenu> {

    private final Identifier background;

    public WnirHopperScreen(WnirHopperMenu menu, Inventory playerInv, Component title, Identifier background) {
        super(menu, playerInv, title);
        this.background   = background;
        this.imageWidth   = 176;
        this.imageHeight  = WnirHopperMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = 53;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, background, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
    }

    /** Returns a screen constructor for use with {@link RegisterMenuScreensEvent#register}. */
    static MenuScreens.ScreenConstructor<WnirHopperMenu, WnirHopperScreen> factory(String textureName) {
        Identifier tex = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/" + textureName + ".png");
        return (menu, inv, title) -> new WnirHopperScreen(menu, inv, title, tex);
    }
}
