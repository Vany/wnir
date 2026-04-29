package com.wnir;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Shared screen for EEClockBuddingCrystalBlock and TeleporterCrystalBlock.
 * Each variant supplies its own background texture and progress bar color.
 */
public class GrowingCrystalScreen extends AbstractContainerScreen<GrowingCrystalMenu> {

    private static final int BAR_FUEL_X = 20, BAR_FUEL_Y = 40, BAR_FUEL_W = 80,  BAR_FUEL_H = 6;
    private static final int BAR_PROG_X = 20, BAR_PROG_Y = 50, BAR_PROG_W = 136, BAR_PROG_H = 10;
    private static final int COLOR_BAR_FUEL = 0xFF44AADD;
    private static final int COLOR_WHITE    = 0xFFFFFFFF;
    private static final int COLOR_GRAY     = 0xFFAAAAAA;

    private final Identifier background;
    private final int colorProgress;

    public GrowingCrystalScreen(GrowingCrystalMenu menu, Inventory inv, Component title,
                                Identifier background, int colorProgress) {
        super(menu, inv, title, GrowingCrystalMenu.IMG_W, GrowingCrystalMenu.IMG_H);
        this.background    = background;
        this.colorProgress = colorProgress;
        inventoryLabelY    = Integer.MAX_VALUE;
        titleLabelY        = Integer.MAX_VALUE;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blit(RenderPipelines.GUI_TEXTURED, background,
               leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);

        int fuelFilled = (int)(BAR_FUEL_W * (menu.getPearlFuel() / 1000f));
        if (fuelFilled > 0) {
            g.fill(leftPos + BAR_FUEL_X, topPos + BAR_FUEL_Y,
                   leftPos + BAR_FUEL_X + fuelFilled, topPos + BAR_FUEL_Y + BAR_FUEL_H,
                   COLOR_BAR_FUEL);
        }

        int progFilled = (int)(BAR_PROG_W * (menu.getProgress() / 10000f));
        if (progFilled > 0) {
            g.fill(leftPos + BAR_PROG_X, topPos + BAR_PROG_Y,
                   leftPos + BAR_PROG_X + progFilled, topPos + BAR_PROG_Y + BAR_PROG_H,
                   colorProgress);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.centeredText(font, title, imageWidth / 2, 6, COLOR_WHITE);
        g.centeredText(font, Component.literal(menu.getProgress() / 100 + "%"), imageWidth / 2, 64, COLOR_WHITE);

        if (menu.getProgress() >= 10000) {
            g.centeredText(font, Component.literal("Ready!"), imageWidth / 2, 74, colorProgress);
        } else if (menu.getClockCount() == 0) {
            g.centeredText(font, Component.literal("No clocks below!"), imageWidth / 2, 74, 0xFFFF5555);
        } else {
            long ticksLeft = (long) GrowingCrystalBlockEntity.BASE_TICKS
                * (10000 - menu.getProgress()) / 10000 / menu.getClockCount();
            g.centeredText(font, Component.literal(
                ticksLeft / 24000 + "d " + (ticksLeft % 24000) / 1000 + "h left"),
                imageWidth / 2, 74, COLOR_GRAY);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        extractTooltip(g, mouseX, mouseY);
    }

    /** Returns a screen constructor for {@link net.neoforged.neoforge.client.event.RegisterMenuScreensEvent}. */
    static MenuScreens.ScreenConstructor<GrowingCrystalMenu, GrowingCrystalScreen> factory(
        String textureName, int colorProgress
    ) {
        Identifier tex = Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/" + textureName + ".png");
        return (menu, inv, title) -> new GrowingCrystalScreen(menu, inv, title, tex, colorProgress);
    }
}
