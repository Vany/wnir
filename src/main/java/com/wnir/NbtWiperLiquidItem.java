package com.wnir;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public class NbtWiperLiquidItem extends Item {

    public NbtWiperLiquidItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx,
            TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        WnirTooltips.add(tooltip, flag,
            Component.translatable("tooltip.wnir.nbt_wiper_liquid"),
            Component.translatable("tooltip.wnir.nbt_wiper_liquid.detail"));
    }
}
