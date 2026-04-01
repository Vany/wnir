package com.wnir;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.function.Consumer;

/**
 * Shared tooltip helper: one-line description + shift-to-expand usage detail.
 * Called from WnirBlockItem and from item appendHoverText overrides.
 *
 * TooltipFlag.hasShiftDown() is a NeoForge-patched default method on the interface.
 */
public final class WnirTooltips {

    private WnirTooltips() {}

    /**
     * Emits:
     *   shortDesc  (always, gray)
     *   "Hold SHIFT for more"  (when shift not held, dark gray italic)
     *   OR blank line + detail  (when shift held, gray)
     */
    public static void add(Consumer<Component> out, TooltipFlag flag, Component shortDesc, Component detail) {
        out.accept(shortDesc.copy().withStyle(ChatFormatting.GRAY));
        if (flag.hasShiftDown()) {
            out.accept(Component.empty());
            out.accept(detail.copy().withStyle(ChatFormatting.GRAY));
        } else {
            out.accept(Component.translatable("tooltip.wnir.shift_for_more")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
