package com.wnir;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Progress GUI for EEClockBuddingCrystalBlock.
 * Static chrome (bg, slots, bar grooves) is blitted from texture.
 * Dynamic elements (bar fills, text) are drawn in code.
 *
 * Texture layout (256×256, panel is 176×166):
 *   y=38-47  Fuel bar groove  (inner fill area: x=20, y=39, w=80, h=8)
 *   y=48-61  Progress bar groove (inner fill area: x=20, y=49, w=136, h=12)
 *   y=82-165 Inventory grid (3×9 + 1×9 hotbar)
 */
public class EEClockBuddingCrystalScreen extends AbstractContainerScreen<EEClockBuddingCrystalMenu> {

    private static final Identifier BACKGROUND =
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/ee_clock_budding_crystal.png");

    // Bar fill area positions (local, relative to leftPos/topPos)
    // Must match the inner groove positions baked into the texture.
    private static final int BAR_FUEL_X = 20, BAR_FUEL_Y = 40, BAR_FUEL_W = 80,  BAR_FUEL_H = 6;
    private static final int BAR_PROG_X = 20, BAR_PROG_Y = 50, BAR_PROG_W = 136, BAR_PROG_H = 10;

    private static final int COLOR_BAR_PROGRESS = 0xFF55AA44;
    private static final int COLOR_BAR_FUEL     = 0xFF44AADD;
    private static final int COLOR_WHITE        = 0xFFFFFFFF;
    private static final int COLOR_GRAY         = 0xFFAAAAAA;

    public EEClockBuddingCrystalScreen(EEClockBuddingCrystalMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth  = EEClockBuddingCrystalMenu.IMG_W;
        imageHeight = EEClockBuddingCrystalMenu.IMG_H;
        inventoryLabelY = Integer.MAX_VALUE;
        titleLabelY     = Integer.MAX_VALUE;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Static chrome from texture
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
               leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);

        // Dynamic: fuel bar fill (blue)
        int fuelFilled = (int)(BAR_FUEL_W * (menu.getPearlFuel() / 1000f));
        if (fuelFilled > 0) {
            g.fill(leftPos + BAR_FUEL_X, topPos + BAR_FUEL_Y,
                   leftPos + BAR_FUEL_X + fuelFilled, topPos + BAR_FUEL_Y + BAR_FUEL_H,
                   COLOR_BAR_FUEL);
        }

        // Dynamic: progress bar fill (green)
        int progFilled = (int)(BAR_PROG_W * (menu.getProgress() / 10000f));
        if (progFilled > 0) {
            g.fill(leftPos + BAR_PROG_X, topPos + BAR_PROG_Y,
                   leftPos + BAR_PROG_X + progFilled, topPos + BAR_PROG_Y + BAR_PROG_H,
                   COLOR_BAR_PROGRESS);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Title
        g.drawCenteredString(font, title, imageWidth / 2, 6, COLOR_WHITE);

        // Percentage
        g.drawCenteredString(font, menu.getProgress() / 100 + "%", imageWidth / 2, 64, COLOR_WHITE);

        // Time remaining / status
        if (menu.getProgress() >= 10000) {
            g.drawCenteredString(font, Component.literal("Ready!"), imageWidth / 2, 74, COLOR_BAR_PROGRESS);
        } else if (menu.getClockCount() == 0) {
            g.drawCenteredString(font, Component.literal("No clocks below!"), imageWidth / 2, 74, 0xFFFF5555);
        } else {
            long ticksLeft = (long) EEClockBuddingCrystalBlockEntity.BASE_TICKS
                * (10000 - menu.getProgress()) / 10000 / menu.getClockCount();
            g.drawCenteredString(font, Component.literal(
                ticksLeft / 24000 + "d " + (ticksLeft % 24000) / 1000 + "h left"),
                imageWidth / 2, 74, COLOR_GRAY);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
