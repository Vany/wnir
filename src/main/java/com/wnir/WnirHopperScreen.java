package com.wnir;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        super(menu, playerInv, title, 176, WnirHopperMenu.IMAGE_HEIGHT);
        this.background   = background;
        this.inventoryLabelY = 53;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        extractTooltip(g, mouseX, mouseY);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blit(RenderPipelines.GUI_TEXTURED, background, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
        super.extractContents(g, mouseX, mouseY, partialTick);
    }

    /** Returns a screen constructor for use with {@link RegisterMenuScreensEvent#register}. */
    static MenuScreens.ScreenConstructor<WnirHopperMenu, WnirHopperScreen> factory(String textureName) {
        Identifier tex = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/" + textureName + ".png");
        return (menu, inv, title) -> new WnirHopperScreen(menu, inv, title, tex);
    }
}
