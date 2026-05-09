package com.wnir;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared menu for all three zygote variants (EE Clock, Teleporter, Warding Post).
 *
 * Slots:
 *   0     — fuel slot (ender pearls or torches depending on variant; empty for no-fuel variants)
 *   1-27  — player inventory (3 rows × 9)
 *   28-36 — player hotbar
 *
 * Synced ContainerData (4 values):
 *   [0] progress     0-10000
 *   [1] clockCount
 *   [2] fuelProgress 0-1000  (items consumed / total)
 *   [3] baseTicks / 24       (client multiplies by 24 for time-remaining display)
 */
public class ZygoteMenu extends AbstractContainerMenu {

    static final int IMG_W       = 176;
    static final int IMG_H       = 166;
    static final int FUEL_SLOT_X = 80;
    static final int FUEL_SLOT_Y = 20;
    static final int INV_X       = 9;
    static final int INV_Y       = 85;
    static final int HOTBAR_Y    = 143;

    private final ContainerData data;
    private final ZygoteVariant variant;

    // ── Client-side factories ─────────────────────────────────────────────

    static ZygoteMenu eeClockZygote  (int id, Inventory inv) { return new ZygoteMenu(WnirRegistries.EE_CLOCK_ZYGOTE_MENU.get(),   id, inv, ZygoteVariant.EE_CLOCK);   }
    static ZygoteMenu teleporterZygote(int id, Inventory inv) { return new ZygoteMenu(WnirRegistries.TELEPORTER_ZYGOTE_MENU.get(), id, inv, ZygoteVariant.TELEPORTER); }
    static ZygoteMenu wardingZygote  (int id, Inventory inv) { return new ZygoteMenu(WnirRegistries.WARDING_ZYGOTE_MENU.get(),    id, inv, ZygoteVariant.WARDING);    }

    private ZygoteMenu(MenuType<ZygoteMenu> type, int id, Inventory playerInv, ZygoteVariant variant) {
        this(type, id, playerInv, new SimpleContainerData(4), new SimpleContainer(1), variant);
    }

    /** Server-side constructor called by ZygoteBlockEntity.createMenu. */
    ZygoteMenu(MenuType<ZygoteMenu> type, int id, Inventory playerInv,
               ContainerData data, Container fuel, ZygoteVariant variant) {
        super(type, id);
        this.data    = data;
        this.variant = variant;
        addDataSlots(data);

        addSlot(new Slot(fuel, 0, FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override public boolean mayPlace(ItemStack stack) {
                return variant.fuelItem != null && stack.is(variant.fuelItem);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    public ZygoteVariant getVariant()    { return variant; }
    public int           getProgress()   { return data.get(0); }
    public int           getClockCount() { return data.get(1); }
    public int           getFuelProgress(){ return data.get(2); }
    public int           getBaseTicks()  { return data.get(3) * 24; }

    @Override public boolean stillValid(Player player) { return true; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack  = slot.getItem();
        ItemStack result = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return result;
    }
}
