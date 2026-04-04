package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Grows into a PersonalDimensionTeleporter block when on an EEClock column with pearl fuel. */
public class TeleporterCrystalBlockEntity extends GrowingCrystalBlockEntity {

    public TeleporterCrystalBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.TELEPORTER_CRYSTAL_BE.get(), pos, state);
    }

    @Override protected BlockState transformState() { return WnirRegistries.PERSONAL_DIMENSION_TELEPORTER_BLOCK.get().defaultBlockState(); }
    @Override protected BlockState revertState()    { return Blocks.CRYING_OBSIDIAN.defaultBlockState(); }
    @Override protected MenuType<GrowingCrystalMenu> menuType() { return WnirRegistries.TELEPORTER_CRYSTAL_MENU.get(); }
    @Override public Component getDisplayName()     { return Component.translatable("block.wnir.teleporter_crystal"); }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TeleporterCrystalBlockEntity be) {
        tick(level, pos, state, be);
    }
}
