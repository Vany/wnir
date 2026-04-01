package com.wnir;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu for the Steel Hopper — 10 hopper slots (2 rows × 5) + standard player inventory.
 * Same layout as MossyHopperMenu; uses a different MenuType.
 */
public class SteelHopperMenu extends AbstractContainerMenu {

    static final int HOPPER_SLOTS = 10;
    static final int IMAGE_HEIGHT = 149;

    private final Container hopper;

    public SteelHopperMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(HOPPER_SLOTS));
    }

    public SteelHopperMenu(int id, Inventory playerInv, Container container) {
        super(WnirRegistries.STEEL_HOPPER_MENU.get(), id);
        this.hopper = container;
        checkContainerSize(container, HOPPER_SLOTS);
        container.startOpen(playerInv.player);

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                addSlot(new Slot(container, row * 5 + col, 44 + col * 18, 20 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 64 + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 122));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return hopper.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < HOPPER_SLOTS) {
                if (!moveItemStackTo(stack, HOPPER_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(stack, 0, HOPPER_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        hopper.stopOpen(player);
    }
}
