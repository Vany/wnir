package com.wnir;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Block;
import javax.annotation.Nullable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * BlockItem subclass used for all WNIR blocks.
 * Adds a one-sentence description and shift-expandable usage tooltip
 * using keys "tooltip.wnir.<name>" and "tooltip.wnir.<name>.detail".
 *
 * Blocks with stored data (energy, fluids) supply an optional {@code dataLines}
 * consumer that reads the BLOCK_ENTITY_DATA CompoundTag and emits extra lines.
 * These lines are always shown (not gated by shift) below the description section.
 */
public class WnirBlockItem extends BlockItem {

    private final String name;
    @Nullable private final BiConsumer<CompoundTag, Consumer<Component>> headerLines;
    @Nullable private final BiConsumer<CompoundTag, Consumer<Component>> dataLines;

    public WnirBlockItem(Block block, Item.Properties props, String name) {
        this(block, props, name, null, null);
    }

    public WnirBlockItem(Block block, Item.Properties props, String name,
            @Nullable BiConsumer<CompoundTag, Consumer<Component>> dataLines) {
        this(block, props, name, null, dataLines);
    }

    public WnirBlockItem(Block block, Item.Properties props, String name,
            @Nullable BiConsumer<CompoundTag, Consumer<Component>> headerLines,
            @Nullable BiConsumer<CompoundTag, Consumer<Component>> dataLines) {
        super(block, props);
        this.name = name;
        this.headerLines = headerLines;
        this.dataLines = dataLines;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext ctx,
            TooltipDisplay display,
            Consumer<Component> out,
            TooltipFlag flag) {
        super.appendHoverText(stack, ctx, display, out, flag);

        if (headerLines != null) {
            TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            CompoundTag tag = data != null ? data.copyTagWithoutId() : new CompoundTag();
            headerLines.accept(tag, out);
        }

        WnirTooltips.add(out, flag,
            Component.translatable("tooltip.wnir." + name),
            Component.translatable("tooltip.wnir." + name + ".detail"));

        if (dataLines != null) {
            TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            CompoundTag tag = data != null ? data.copyTagWithoutId() : new CompoundTag();
            out.accept(Component.empty());
            dataLines.accept(tag, out);
        }
    }

    // ── Static data-line helpers for specific blocks ──────────────────────────

    static void accumulatorHeaderLines(CompoundTag tag, Consumer<Component> out) {
        long energy   = tag.getLong("energy").orElse(0L);
        long capacity = tag.getLong("capacity").orElse(AccumulatorBlockEntity.BASE_CAPACITY);
        double fill = capacity > 0 ? (double) energy / capacity : 0;
        ChatFormatting fillColor = fill > 0.66 ? ChatFormatting.GREEN
                                 : fill > 0.33 ? ChatFormatting.YELLOW
                                 :               ChatFormatting.RED;
        out.accept(
            Component.literal(formatFe(energy)).withStyle(fillColor)
                .append(Component.literal(" / " + formatFe(capacity) + " FE")
                    .withStyle(ChatFormatting.GRAY))
        );
    }

    static void celluloserDataLines(CompoundTag tag, Consumer<Component> out) {
        // Energy: nested "Energy" compound → "energy" int
        int energy = tag.getCompound("Energy").flatMap(e -> e.getInt("energy")).orElse(0);
        out.accept(Component.literal("Energy: " + formatFe(energy) + " / " + formatFe(CelluloserBlockEntity.ENERGY_CAPACITY) + " FE")
            .withStyle(ChatFormatting.AQUA));

        // Fluids: "Fluids" → "stacks" list → slot 0 = water, slot 1 = magic cellulose
        int waterMb     = 0;
        int celluloseMb = 0;
        var fluidsCompound = tag.getCompound("Fluids").orElse(null);
        if (fluidsCompound != null) {
            var stacksList = fluidsCompound.getList("stacks").orElse(null);
            if (stacksList != null) {
                waterMb     = stacksList.getCompound(0).flatMap(s -> s.getInt("amount")).orElse(0);
                celluloseMb = stacksList.getCompound(1).flatMap(s -> s.getInt("amount")).orElse(0);
            }
        }
        int tankCap = CelluloserBlockEntity.TANK_CAPACITY;
        out.accept(Component.literal("Water: " + waterMb + " / " + tankCap + " mB")
            .withStyle(ChatFormatting.BLUE));
        out.accept(Component.literal("Magic Cellulose: " + celluloseMb + " / " + tankCap + " mB")
            .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    static String formatFe(long fe) {
        if (fe >= 1_000_000_000L) return String.format("%.1fG", fe / 1_000_000_000.0);
        if (fe >= 1_000_000L)     return String.format("%.1fM", fe / 1_000_000.0);
        if (fe >= 1_000L)         return String.format("%.1fk", fe / 1_000.0);
        return Long.toString(fe);
    }
}
