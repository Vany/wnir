package com.wnir;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared menu for all 10-slot WNIR hoppers (Mossy, Steel, Nether).
 *
 * Slot layout:
 *   Row 0: slots 0-4   y=20, x = 44,62,80,98,116
 *   Row 1: slots 5-9   y=38, x = 44,62,80,98,116
 *   Player inv (3×9):  y=64, x = 8..152
 *   Hotbar (1×9):      y=122, x = 8..152
 *
 * Background image size: 176 × 149 px.
 */
public class WnirHopperMenu extends AbstractContainerMenu {

    static final int HOPPER_SLOTS = 10;
    static final int IMAGE_HEIGHT = 149;

    private final Container hopper;

    // ── Per-variant client-side factories (used by MenuType registration) ────
    // These are static methods instead of constructor references so that the
    // WnirRegistries field initializer can reference them without a self-reference.

    static WnirHopperMenu mossy (int id, Inventory inv) { return new WnirHopperMenu(WnirRegistries.MOSSY_HOPPER_MENU.get(),  id, inv); }
    static WnirHopperMenu steel (int id, Inventory inv) { return new WnirHopperMenu(WnirRegistries.STEEL_HOPPER_MENU.get(),  id, inv); }
    static WnirHopperMenu nether(int id, Inventory inv) { return new WnirHopperMenu(WnirRegistries.NETHER_HOPPER_MENU.get(), id, inv); }

    /** Client-side constructor. */
    private WnirHopperMenu(MenuType<WnirHopperMenu> type, int id, Inventory playerInv) {
        this(type, id, playerInv, new SimpleContainer(HOPPER_SLOTS));
    }

    /** Server-side constructor (called by hopper BlockEntity.createMenu). */
    public WnirHopperMenu(MenuType<WnirHopperMenu> type, int id, Inventory playerInv, Container container) {
        super(type, id);
        this.hopper = container;
        checkContainerSize(container, HOPPER_SLOTS);
        container.startOpen(playerInv.player);

        // ── Hopper slots (2 rows × 5) ─────────────────────────────────────
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                addSlot(new Slot(container, row * 5 + col, 44 + col * 18, 20 + row * 18));
            }
        }

        // ── Player inventory (3 rows × 9) ─────────────────────────────────
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 64 + row * 18));
            }
        }

        // ── Hotbar ────────────────────────────────────────────────────────
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
