package com.wnir;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-side: renders queued captions below the screen center for 5 seconds each.
 * Registered on NeoForge EVENT_BUS in WnirClientSetup.
 */
@OnlyIn(Dist.CLIENT)
public class WeddingRingCaptionRenderer {

    private static final long DISPLAY_TICKS = 5 * 20L;

    private static long currentExpire = -1;
    private static String currentText = null;
    private static final Deque<String> queue = new ArrayDeque<>();

    public static void addCaption(String text) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : System.currentTimeMillis() / 50;
        if (currentText == null) {
            currentText   = text;
            currentExpire = now + DISPLAY_TICKS;
        } else {
            queue.addLast(text);
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || currentText == null) return;

        long now = mc.level.getGameTime();

        if (now > currentExpire) {
            if (!queue.isEmpty()) {
                currentText   = queue.pollFirst();
                currentExpire = now + DISPLAY_TICKS;
            } else {
                currentText = null;
                return;
            }
        }

        long remaining = currentExpire - now;
        float alpha = 1.0f;
        long elapsed = DISPLAY_TICKS - remaining;
        if (elapsed < 10) alpha = elapsed / 10.0f;
        if (remaining < 10) alpha = Math.min(alpha, remaining / 10.0f);
        int a = Math.max(8, (int)(alpha * 255));
        int color = (a << 24) | 0xFFFFAA;

        GuiGraphicsExtractor g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int textW   = mc.font.width(currentText);
        int x       = (screenW - textW) / 2;
        int y       = screenH / 2 + 30;

        g.text(mc.font, currentText, x + 1, y + 1, (a << 24), false);
        g.text(mc.font, currentText, x, y, color, false);
    }
}
