package com.wnir;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Menu for EEClockBuddingCrystalBlock.
 *
 * Slots:
 *   0          — ender pearl fuel slot (accepts ender pearls only)
 *   1-27       — player inventory (rows 0-2)
 *   28-36      — player hotbar
 *
 * Synced ContainerData (3 slots, all fit in a short):
 *   [0] progress     0-10000 (per-ten-mille)
 *   [1] clockCount   current EEClock column height below
 *   [2] pearlFuel    0-1000  (current pearl fuel, scaled)
 *
 * GUI size: 176 × 166 (standard inventory height).
 * Pearl slot position (local): x=80, y=20.
 * Player inventory: x=8, y=84 (standard).
 */
public class EEClockBuddingCrystalMenu extends AbstractContainerMenu {

    static final int IMG_W            = 176;
    static final int IMG_H            = 166;
    static final int PEARL_SLOT_X     = 80;
    static final int PEARL_SLOT_Y     = 20;
    static final int INV_X            = 9;
    static final int INV_Y            = 85;
    static final int HOTBAR_Y         = 143;

    private final ContainerData data;

    /** Client-side constructor (called by MenuType factory). */
    public EEClockBuddingCrystalMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainerData(3), new SimpleContainer(1));
    }

    /** Server-side constructor (called by BE.createMenu). */
    public EEClockBuddingCrystalMenu(int id, Inventory playerInv, ContainerData data, Container pearls) {
        super(WnirRegistries.EE_CLOCK_BUDDING_CRYSTAL_MENU.get(), id);
        this.data = data;
        addDataSlots(data);

        // Pearl fuel slot — ender pearls only
        addSlot(new Slot(pearls, 0, PEARL_SLOT_X, PEARL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.ENDER_PEARL);
            }
        });

        // Player inventory (3 rows × 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }

        // Hotbar (1 row × 9)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    public int getProgress()    { return data.get(0); } // 0-10000
    public int getClockCount()  { return data.get(1); }
    public int getPearlFuel()   { return data.get(2); } // 0-1000

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        if (index == 0) {
            // Pearl slot → player inventory
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Player inventory/hotbar → pearl slot
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();

        return result;
    }
}
