package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Grows into an EEClock block when on an EEClock column with pearl fuel. */
public class EEClockBuddingCrystalBlockEntity extends GrowingCrystalBlockEntity {

    public EEClockBuddingCrystalBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_BE.get(), pos, state);
    }

    @Override protected BlockState transformState() { return WnirRegistries.EE_CLOCK_BLOCK.get().defaultBlockState(); }
    @Override protected BlockState revertState()    { return Blocks.BUDDING_AMETHYST.defaultBlockState(); }
    @Override protected MenuType<GrowingCrystalMenu> menuType() { return WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_MENU.get(); }
    @Override public Component getDisplayName()     { return Component.translatable("block.wnir.ee_clock_budding_crystal"); }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EEClockBuddingCrystalBlockEntity be) {
        tick(level, pos, state, be);
    }
}
