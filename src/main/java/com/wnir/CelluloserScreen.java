package com.wnir;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Celluloser GUI (176×166).
 *
 * Layout (left to right):
 *   [8,14]  Energy bar    16×56 frame, 14×54 fill
 *   [29,14] Water tank    20×56 frame, 18×54 fill
 *   [79,35] Book slot     18×18
 *   [101,37] Progress arrow  22×15
 *   [127,14] Cellulose tank  20×56 frame, 18×54 fill
 *
 * Fill textures are packed at y=168 in the 256×256 PNG:
 *   x=0   w=14 h=54  energy fill  (orange)
 *   x=14  w=18 h=54  water fill   (blue)
 *   x=32  w=18 h=54  cellulose fill (pink)
 *   x=50  w=22 h=15  arrow fill   (gold)
 */
public class CelluloserScreen extends AbstractContainerScreen<CelluloserMenu> {

    private static final Identifier BG =
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/celluloser.png");

    // Fill texture origins in the PNG
    private static final int FILL_Y     = 168;
    private static final int E_FILL_X   = 0,  E_FILL_W  = 14, FILL_H = 54;
    private static final int W_FILL_X   = 14, W_FILL_W  = 18;
    private static final int C_FILL_X   = 32, C_FILL_W  = 18;
    private static final int ARR_FILL_X = 50, ARR_FILL_W = 22, ARR_FILL_H = 15;

    // GUI element positions (relative to leftPos/topPos)
    private static final int E_X = 9,   E_Y = 15;   // energy inner fill origin
    private static final int W_X = 30,  W_Y = 15;   // water inner fill origin
    private static final int C_X = 128, C_Y = 15;   // cellulose inner fill origin
    private static final int ARR_X = 101, ARR_Y = 38; // progress arrow

    public CelluloserScreen(CelluloserMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth  = 176;
        this.imageHeight = 166;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelY = this.imageHeight + 10;  // push off-screen — no "Inventory" label
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Background
        g.blit(RenderPipelines.GUI_TEXTURED, BG,
            leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);

        // Energy bar (fills from bottom)
        int energy  = menu.getEnergy();
        int energyH = (int)(FILL_H * (long) energy / CelluloserBlockEntity.ENERGY_CAPACITY);
        if (energyH > 0) {
            int yOff = FILL_H - energyH;
            g.blit(RenderPipelines.GUI_TEXTURED, BG,
                leftPos + E_X, topPos + E_Y + yOff,
                E_FILL_X, FILL_Y + yOff,
                E_FILL_W, energyH, 256, 256);
        }

        // Water tank (fills from bottom)
        int water  = menu.getWater();
        int waterH = (int)(FILL_H * (long) water / CelluloserBlockEntity.TANK_CAPACITY);
        if (waterH > 0) {
            int yOff = FILL_H - waterH;
            g.blit(RenderPipelines.GUI_TEXTURED, BG,
                leftPos + W_X, topPos + W_Y + yOff,
                W_FILL_X, FILL_Y + yOff,
                W_FILL_W, waterH, 256, 256);
        }

        // Cellulose tank (fills from bottom)
        int cell  = menu.getCellulose();
        int cellH = (int)(FILL_H * (long) cell / CelluloserBlockEntity.TANK_CAPACITY);
        if (cellH > 0) {
            int yOff = FILL_H - cellH;
            g.blit(RenderPipelines.GUI_TEXTURED, BG,
                leftPos + C_X, topPos + C_Y + yOff,
                C_FILL_X, FILL_Y + yOff,
                C_FILL_W, cellH, 256, 256);
        }

        // Progress arrow (fills from left)
        int rem = menu.getRemainingXp();
        int tot = menu.getTotalXp();
        if (tot > 0) {
            int arrowW = (int)(ARR_FILL_W * (long)(tot - rem) / tot);
            if (arrowW > 0) {
                g.blit(RenderPipelines.GUI_TEXTURED, BG,
                    leftPos + ARR_X, topPos + ARR_Y,
                    ARR_FILL_X, FILL_Y,
                    arrowW, ARR_FILL_H, 256, 256);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);

        int rx = mouseX - leftPos, ry = mouseY - topPos;

        // Energy bar tooltip
        if (rx >= 8 && rx <= 23 && ry >= 13 && ry <= 69) {
            int energy = menu.getEnergy();
            g.setComponentTooltipForNextFrame(font, java.util.List.of(
                Component.literal("Energy"),
                Component.literal(energy + " / " + CelluloserBlockEntity.ENERGY_CAPACITY + " FE")
            ), mouseX, mouseY);
        }
        // Water tank tooltip
        else if (rx >= 29 && rx <= 49 && ry >= 13 && ry <= 69) {
            int water = menu.getWater();
            g.setComponentTooltipForNextFrame(font, java.util.List.of(
                Component.literal("Water"),
                Component.literal(water + " / " + CelluloserBlockEntity.TANK_CAPACITY + " mB")
            ), mouseX, mouseY);
        }
        // Cellulose tank tooltip
        else if (rx >= 127 && rx <= 147 && ry >= 13 && ry <= 69) {
            int cell = menu.getCellulose();
            g.setComponentTooltipForNextFrame(font, java.util.List.of(
                Component.literal("Magic Cellulose"),
                Component.literal(cell + " / " + CelluloserBlockEntity.TANK_CAPACITY + " mB")
            ), mouseX, mouseY);
        }
        // Progress arrow tooltip
        else if (rx >= 101 && rx <= 122 && ry >= 37 && ry <= 52) {
            int rem = menu.getRemainingXp();
            int tot = menu.getTotalXp();
            if (tot > 0) {
                int pct = (tot - rem) * 100 / tot;
                g.setComponentTooltipForNextFrame(font, java.util.List.of(
                    Component.literal("Processing"),
                    Component.literal(pct + "% (" + rem + " XP remaining)")
                ), mouseX, mouseY);
            } else {
                g.setComponentTooltipForNextFrame(font, java.util.List.of(
                    Component.literal("Idle — insert enchanted book")
                ), mouseX, mouseY);
            }
        }
    }
}
