package com.wnir;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Pet inventory screen for the Wedding Ring.
 *
 * Layout (local coords):
 *   Title bar          y=0..18
 *   Ring slot viewport y=20..102  (scrollable, 4 visible rows × 20 px)
 *   Standard 3×9 grid  y=106..164 (fixed)
 *   Player inventory   y=172..250
 *
 * Ring slots (0–7): pose.translate(0, -scrollOffset) + scissor; isHovering adjusted.
 * Standard slots (8–34): fixed, no scroll adjustment.
 */
@OnlyIn(Dist.CLIENT)
public class WeddingRingScreen extends AbstractContainerScreen<WeddingRingMenu> {

    private static final Identifier SLOT_HIGHLIGHT_BACK  = Identifier.withDefaultNamespace("container/slot_highlight_back");
    private static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
    private static final int TEXT_X       = WeddingRingMenu.SLOT_X + 20;
    private static final int SCROLLBAR_X  = WeddingRingMenu.IMG_W - 8;
    private static final int SCROLLBAR_W  = 4;
    private static final int VIEWPORT_H   = WeddingRingMenu.VIEWPORT_H;
    private static final int VIEWPORT_TOP = WeddingRingMenu.SLOT_AREA_TOP;

    private static final String[] SLOT_NAMES = {"Weapon", "Shield", "Healing Potions",
                                                 "Helmet", "Chestplate", "Leggings", "Boots"};
    private static final String[] SLOT_DESCS = {"Sword, axe, spear, or mace",
                                                 "Reduces incoming damage",
                                                 "Instant Health I or II",
                                                 "Head armor", "Chest armor", "Leg armor", "Foot armor"};

    private boolean isDraggingScrollbar = false;
    private double dragStartY = 0;
    private int dragStartOffset = 0;

    public WeddingRingScreen(WeddingRingMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, WeddingRingMenu.IMG_W, WeddingRingMenu.IMG_H);
        titleLabelY     = Integer.MAX_VALUE;
        inventoryLabelY = Integer.MAX_VALUE;
    }

    private static void drawSlotBg(GuiGraphicsExtractor g, int sx, int sy) {
        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF1E1E1E);
        g.fill(sx,     sy,     sx + 16, sy + 16,  0xFF2D2D2D);
    }

    // ── extractContents ───────────────────────────────────────────────────────

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x      = leftPos, y = topPos;
        int petBot = WeddingRingMenu.PET_AREA_BOTTOM;
        int stdBot = WeddingRingMenu.STD_AREA_BOTTOM;
        int offset = menu.getScrollOffset();

        // Background (screen coords)
        g.fill(x,   y,   x + imageWidth,     y + imageHeight,     0xFF666666);
        g.fill(x+1, y+1, x + imageWidth - 1, y + WeddingRingMenu.SLOT_AREA_TOP, 0xFF1E1E1E);
        g.fill(x+1, y + WeddingRingMenu.SLOT_AREA_TOP, x + imageWidth - 1, y + petBot, 0xFF353535);
        g.fill(x+1, y + petBot, x + imageWidth - 1, y + petBot + 1,   0xFF5A5A5A);
        g.fill(x+1, y + petBot+1, x + imageWidth - 1, y + stdBot,     0xFF2B2B2B);
        g.fill(x+1, y + stdBot, x + imageWidth - 1, y + stdBot + 1,   0xFF5A5A5A);
        g.fill(x+1, y + stdBot+1, x + imageWidth - 1, y + imageHeight-1, 0xFF2B2B2B);

        g.pose().pushMatrix();
        g.pose().translate(x, y);

        hoveredSlot = findHoveredSlot(mouseX, mouseY);
        extractLabels(g, mouseX, mouseY);
        drawButtons(g);

        // ── Ring slots (scrollable) ───────────────────────────────────────────
        int active = menu.getActiveSlotCount();
        g.enableScissor(1, VIEWPORT_TOP, imageWidth - 8, petBot);
        g.pose().pushMatrix();
        g.pose().translate(0, -(float) offset);

        for (int i = 0; i < active; i++) drawSlotBg(g, menu.slots.get(i).x, menu.slots.get(i).y);

        if (hoveredSlot != null && isRingSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_BACK, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);
        for (int i = 0; i < active; i++) {
            Slot s = menu.slots.get(i);
            if (s.isActive()) extractSlot(g, s, mouseX, mouseY);
        }
        if (hoveredSlot != null && isRingSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_FRONT, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);

        g.pose().popMatrix();
        g.disableScissor();

        // ── Standard storage grid (fixed) ─────────────────────────────────────
        for (int i = WeddingRingMenu.MAX_RING_SLOTS; i < WeddingRingMenu.MAX_PET_SLOTS; i++)
            drawSlotBg(g, menu.slots.get(i).x, menu.slots.get(i).y);

        if (hoveredSlot != null && isStdSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_BACK, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);
        for (int i = WeddingRingMenu.MAX_RING_SLOTS; i < WeddingRingMenu.MAX_PET_SLOTS; i++) {
            Slot s = menu.slots.get(i);
            if (s.isActive()) extractSlot(g, s, mouseX, mouseY);
        }
        if (hoveredSlot != null && isStdSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_FRONT, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);

        // ── Player inventory (fixed) ──────────────────────────────────────────
        for (int i = WeddingRingMenu.MAX_PET_SLOTS; i < menu.slots.size(); i++)
            drawSlotBg(g, menu.slots.get(i).x, menu.slots.get(i).y);

        if (hoveredSlot != null && isPlayerSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_BACK, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);
        for (int i = WeddingRingMenu.MAX_PET_SLOTS; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.isActive()) extractSlot(g, s, mouseX, mouseY);
        }
        if (hoveredSlot != null && isPlayerSlot(hoveredSlot) && hoveredSlot.isHighlightable())
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                SLOT_HIGHLIGHT_FRONT, hoveredSlot.x - 4, hoveredSlot.y - 4, 24, 24);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
            new net.neoforged.neoforge.client.event.ContainerScreenEvent.Render.Foreground(
                this, g, mouseX, mouseY));

        g.pose().popMatrix();
        renderScrollbar(g);
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.centeredText(font, title, imageWidth / 2, 5, 0xFFEEEEEE);

        int active = menu.getActiveSlotCount();
        if (active == 0) {
            g.centeredText(font, "No slots available",
                imageWidth / 2, VIEWPORT_TOP + VIEWPORT_H / 2 - 4, 0xFF777777);
        } else {
            int offset   = menu.getScrollOffset();
            int maxDescW = imageWidth - TEXT_X - 12;
            g.enableScissor(1, VIEWPORT_TOP, imageWidth - 8, WeddingRingMenu.PET_AREA_BOTTOM);
            for (int i = 0; i < active; i++) {
                int slotY = WeddingRingMenu.petSlotY(i) - offset;
                String name = i < SLOT_NAMES.length ? SLOT_NAMES[i] : "Slot " + (i + 1);
                String desc;
                if (i == 2) {
                    int c1 = menu.getHealingCount1(), c2 = menu.getHealingCount2();
                    if (c1 > 0 && c2 > 0) desc = "I×" + c1 + "  II×" + c2;
                    else if (c1 > 0)       desc = "Healing I ×" + c1;
                    else if (c2 > 0)       desc = "Healing II ×" + c2;
                    else                   desc = SLOT_DESCS[2];
                } else {
                    desc = i < SLOT_DESCS.length ? SLOT_DESCS[i] : "";
                }
                g.text(font, name, TEXT_X, slotY + 1, 0xFFE8E8E8, false);
                if (!desc.isEmpty())
                    g.text(font, font.plainSubstrByWidth(desc, maxDescW), TEXT_X, slotY + 10, 0xFF888888, false);
            }
            g.disableScissor();
        }

        g.text(font, "Storage",   WeddingRingMenu.INV_X, WeddingRingMenu.STD_AREA_TOP - 8,  0xFFA0A0A0, false);
        g.text(font, "Inventory", WeddingRingMenu.INV_X, WeddingRingMenu.INV_Y - 10,         0xFFA0A0A0, false);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void drawButtons(GuiGraphicsExtractor g) {
        drawToggleButton(g,
            WeddingRingMenu.CALM_BTN_X, WeddingRingMenu.CALM_BTN_Y,
            WeddingRingMenu.BUTTON_W,   WeddingRingMenu.BUTTON_H,
            "Calm", menu.isCalm());
        drawToggleButton(g,
            WeddingRingMenu.AI_BTN_X, WeddingRingMenu.AI_BTN_Y,
            WeddingRingMenu.BUTTON_W, WeddingRingMenu.BUTTON_H,
            "AI", menu.isAiEnabled());
    }

    private void drawToggleButton(GuiGraphicsExtractor g, int bx, int by, int bw, int bh,
                                   String label, boolean active) {
        int bg     = active ? 0xFF1E4A1E : 0xFF2A2A2A;
        int border = active ? 0xFF4AAA4A : 0xFF555555;
        int text   = active ? 0xFF88FF88 : 0xFFAAAAAA;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, border);
        g.fill(bx,     by,     bx + bw,     by + bh,     bg);
        g.centeredText(font, label, bx + bw / 2, by + (bh - 7) / 2, text);
    }

    // ── Scrollbar ─────────────────────────────────────────────────────────────

    private void renderScrollbar(GuiGraphicsExtractor g) {
        int active = menu.getActiveSlotCount();
        if (active <= WeddingRingMenu.VIEWPORT_ROWS) return;
        int totalH = active * WeddingRingMenu.SLOT_ROW_HEIGHT;
        int x      = leftPos + SCROLLBAR_X;
        int y      = topPos  + VIEWPORT_TOP;
        g.fill(x, y, x + SCROLLBAR_W, y + VIEWPORT_H, 0xFF444444);
        float ratio    = (float) VIEWPORT_H / totalH;
        int   thumbH   = Math.max(10, (int)(VIEWPORT_H * ratio));
        int   maxScroll = totalH - VIEWPORT_H;
        int   thumbY   = maxScroll > 0
            ? (int)(y + (VIEWPORT_H - thumbH) * (float) menu.getScrollOffset() / maxScroll) : y;
        g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
    }

    private int maxScrollOffset() {
        return Math.max(0, menu.getActiveSlotCount() * WeddingRingMenu.SLOT_ROW_HEIGHT - VIEWPORT_H);
    }

    // ── Slot classification ───────────────────────────────────────────────────

    private boolean isRingSlot(Slot slot) {
        return menu.slots.indexOf(slot) < WeddingRingMenu.MAX_RING_SLOTS;
    }

    private boolean isStdSlot(Slot slot) {
        int idx = menu.slots.indexOf(slot);
        return idx >= WeddingRingMenu.MAX_RING_SLOTS && idx < WeddingRingMenu.MAX_PET_SLOTS;
    }

    private boolean isPlayerSlot(Slot slot) {
        return menu.slots.indexOf(slot) >= WeddingRingMenu.MAX_PET_SLOTS;
    }

    private boolean isButtonHit(MouseButtonEvent event, int bx, int by, int bw, int bh) {
        int sx = leftPos + bx, sy = topPos + by;
        return event.x() >= sx && event.x() < sx + bw && event.y() >= sy && event.y() < sy + bh;
    }

    private Slot findHoveredSlot(int mouseX, int mouseY) {
        for (Slot slot : menu.slots) {
            if (slot.isActive() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY))
                return slot;
        }
        return null;
    }

    /**
     * Ring slots (0–MAX_RING_SLOTS-1) need scroll-adjusted hit testing.
     * Standard slots and player slots use super directly.
     */
    @Override
    protected boolean isHovering(int left, int top, int w, int h, double mouseX, double mouseY) {
        int active = menu.getActiveSlotCount();
        for (int i = 0; i < WeddingRingMenu.MAX_RING_SLOTS; i++) {
            Slot s = menu.slots.get(i);
            if (s.x == left && s.y == top) {
                if (i >= active) return false;
                int visualTop = top - menu.getScrollOffset();
                if (visualTop < VIEWPORT_TOP || visualTop + h > WeddingRingMenu.PET_AREA_BOTTOM) return false;
                return super.isHovering(left, visualTop, w, h, mouseX, mouseY);
            }
        }
        return super.isHovering(left, top, w, h, mouseX, mouseY);
    }

    // ── Mouse interaction ─────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int newOffset = menu.getScrollOffset() - (int)(scrollY * WeddingRingMenu.SLOT_ROW_HEIGHT);
        menu.setScrollOffset(Math.max(0, Math.min(newOffset, maxScrollOffset())));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Button row
        if (isButtonHit(event, WeddingRingMenu.CALM_BTN_X, WeddingRingMenu.CALM_BTN_Y,
                WeddingRingMenu.BUTTON_W, WeddingRingMenu.BUTTON_H)) {
            ClientPacketDistributor.sendToServer(new WeddingRingButtonPayload(0));
            return true;
        }
        if (isButtonHit(event, WeddingRingMenu.AI_BTN_X, WeddingRingMenu.AI_BTN_Y,
                WeddingRingMenu.BUTTON_W, WeddingRingMenu.BUTTON_H)) {
            ClientPacketDistributor.sendToServer(new WeddingRingButtonPayload(1));
            return true;
        }

        int sbX = leftPos + SCROLLBAR_X, sbY = topPos + VIEWPORT_TOP;
        if (event.x() >= sbX && event.x() <= sbX + SCROLLBAR_W + 2
                && event.y() >= sbY && event.y() <= sbY + VIEWPORT_H) {
            isDraggingScrollbar = true;
            dragStartY      = event.y();
            dragStartOffset = menu.getScrollOffset();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (isDraggingScrollbar) {
            int maxScroll = Math.max(1, menu.getActiveSlotCount() * WeddingRingMenu.SLOT_ROW_HEIGHT - VIEWPORT_H);
            int pixelOffset = (int)((event.y() - dragStartY) * (float) maxScroll / VIEWPORT_H);
            menu.setScrollOffset(Math.max(0, Math.min(dragStartOffset + pixelOffset, maxScroll)));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        isDraggingScrollbar = false;
        return super.mouseReleased(event);
    }
}
