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
 * Celluloser container menu.
 *
 * Slots:
 *   0        — enchanted book input
 *   1–27     — player inventory (rows 0-2)
 *   28–36    — hotbar
 *
 * ContainerData (6 ints, synced server→client):
 *   0  energy low 16 bits
 *   1  energy high bits
 *   2  water mB
 *   3  cellulose mB
 *   4  remaining XP (capped at 32767 for display)
 *   5  total XP     (capped at 32767 for display)
 */
public class CelluloserMenu extends AbstractContainerMenu {

    public static final int BOOK_SLOT       = 0;
    public static final int PLAYER_INV_START = 1;
    public static final int PLAYER_INV_END  = 28;
    public static final int HOTBAR_END      = 37;

    private final Container container;
    private final ContainerData data;

    /** Client-side constructor (called by MenuType factory). */
    public CelluloserMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(1), new SimpleContainerData(6));
    }

    /** Server-side constructor (called from BlockEntity.createMenu). */
    public CelluloserMenu(int id, Inventory playerInv, Container container, ContainerData data) {
        super(WnirRegistries.CELLULOSER_MENU.get(), id);
        this.container = container;
        this.data      = data;

        checkContainerSize(container, 1);

        // Book input slot — only enchanted books allowed
        addSlot(new Slot(container, 0, 80, 36) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.ENCHANTED_BOOK);
            }
        });

        // Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 9 + col * 18, 143));
        }

        addDataSlots(data);
    }

    // ── Synced data getters (client reads these) ─────────────────────────────

    public int getEnergy() {
        return ((data.get(1) & 0xFFFF) << 16) | (data.get(0) & 0xFFFF);
    }
    public int getWater()        { return data.get(2); }
    public int getCellulose()    { return data.get(3); }
    public int getRemainingXp()  { return data.get(4); }
    public int getTotalXp()      { return data.get(5); }

    // ── Shift-click ──────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        if (index == BOOK_SLOT) {
            // Book slot → player inventory
            if (!moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, result);
        } else {
            // Player inventory → book slot (enchanted books only)
            if (stack.is(Items.ENCHANTED_BOOK)) {
                if (!moveItemStackTo(stack, BOOK_SLOT, PLAYER_INV_START, false)) return ItemStack.EMPTY;
            } else if (index < PLAYER_INV_END) {
                // Inventory row → hotbar
                if (!moveItemStackTo(stack, PLAYER_INV_END, HOTBAR_END, false)) return ItemStack.EMPTY;
            } else {
                // Hotbar → inventory rows
                if (!moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, false)) return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == result.getCount()) return ItemStack.EMPTY;
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
