package com.wnir;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

public enum ZygoteVariant {
    EE_CLOCK(
        Blocks.BUDDING_AMETHYST,
        () -> WnirRegistries.EE_CLOCK_BLOCK.get(),
        168_000, Items.ENDER_PEARL, 16, 0xFF55AA44
    ),
    TELEPORTER(
        Blocks.CRYING_OBSIDIAN,
        () -> WnirRegistries.PERSONAL_DIMENSION_TELEPORTER_BLOCK.get(),
        168_000, Items.ENDER_PEARL, 16, 0xFF9955CC
    ),
    WARDING(
        Blocks.END_ROD,
        () -> WnirRegistries.WARDING_POST_BLOCK.get(),
        24_000, Items.TORCH, 64, 0xFFCC8800
    );

    public final Block revertBlock;
    private final Supplier<Block> targetSupplier;
    public final int baseTicks;
    public final @Nullable Item fuelItem;
    public final int fuelCount;
    public final int color;

    ZygoteVariant(Block revertBlock, Supplier<Block> targetSupplier,
                  int baseTicks, @Nullable Item fuelItem, int fuelCount, int color) {
        this.revertBlock    = revertBlock;
        this.targetSupplier = targetSupplier;
        this.baseTicks      = baseTicks;
        this.fuelItem       = fuelItem;
        this.fuelCount      = fuelCount;
        this.color          = color;
    }

    public Block targetBlock() { return targetSupplier.get(); }
}
