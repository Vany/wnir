package com.wnir;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screen for the Skull Beehive turret.
 *
 * Layout (GUI-relative, matches skull_beehive.png texture):
 *   y=0-15   header band
 *   y=20     6 weapon slots (x=7,25,43,61,79,97)
 *   y=38,39  section separator
 *   y=42     arrow receiver (x=7)  + arrow bar (x=29, w=130, h=10)
 *   y=60     gunpwdr receiver (x=7) + gunpowder bar (x=29, w=130, h=10)
 *   y=78,79  separator
 *   y=82     player inventory (3×9)
 *   y=135,136 separator
 *   y=140    hotbar
 */
public class SkullBeehiveScreen extends AbstractContainerScreen<SkullBeehiveMenu> {

    private static final Identifier BACKGROUND =
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "textures/gui/container/skull_beehive.png");

    // Bar position in GUI-relative coords (matching the texture bar backgrounds)
    private static final int BAR_X           = 29;
    private static final int BAR_ARROW_Y     = 45;
    private static final int BAR_GUNPOWDER_Y = 63;
    private static final int BAR_WIDTH       = 130;
    private static final int BAR_HEIGHT      = 10;

    // Colors (ARGB)
    private static final int COLOR_ARROW_FULL  = 0xFF44AA44;
    private static final int COLOR_ARROW_EMPTY = 0xFF224422;
    private static final int COLOR_GUN_FULL    = 0xFF888888;
    private static final int COLOR_GUN_EMPTY   = 0xFF333333;

    public SkullBeehiveScreen(SkullBeehiveMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, 176, SkullBeehiveMenu.IMAGE_HEIGHT);
        this.inventoryLabelY = 80;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
            leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);

        renderBar(g,
            leftPos + BAR_X, topPos + BAR_ARROW_Y,
            menu.data.get(0), SkullBeehiveBlockEntity.MAX_AMMO,
            COLOR_ARROW_FULL, COLOR_ARROW_EMPTY);

        renderBar(g,
            leftPos + BAR_X, topPos + BAR_GUNPOWDER_Y,
            menu.data.get(1), SkullBeehiveBlockEntity.MAX_AMMO,
            COLOR_GUN_FULL, COLOR_GUN_EMPTY);
        super.extractContents(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Draw container title only — skip playerInventoryTitle ("Inventory" is redundant).
        g.text(font, title, titleLabelX, titleLabelY, 0x404040, false);
        int arrowCount = menu.data.get(0);
        int gunCount   = menu.data.get(1);
        // Count labels to the right of each bar
        g.text(font, String.valueOf(arrowCount),
            BAR_X + BAR_WIDTH + 4, BAR_ARROW_Y + 1, 0x446644, false);
        g.text(font, String.valueOf(gunCount),
            BAR_X + BAR_WIDTH + 4, BAR_GUNPOWDER_Y + 1, 0x666666, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        extractTooltip(g, mouseX, mouseY);
    }

    /**
     * Fills the bar area (texture inset already drawn as background).
     * No extra border — the texture provides the inset shadow/highlight.
     */
    private static void renderBar(
        GuiGraphicsExtractor g, int x, int y, int current, int max,
        int colorFull, int colorEmpty
    ) {
        g.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, colorEmpty);
        if (max > 0 && current > 0) {
            int fillW = (int) ((long) current * BAR_WIDTH / max);
            if (fillW > 0) g.fill(x, y, x + fillW, y + BAR_HEIGHT, colorFull);
        }
    }
}
