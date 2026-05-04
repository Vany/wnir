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

/**
 * Celluloser container menu.
 *
 * Slots:
 *   0        — enchanted book / armor / weapon input
 *   1–9      — disassembly output (extract only)
 *   10–36    — player inventory (rows 0-2)
 *   37–45    — hotbar
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

    public static final int BOOK_SLOT        = 0;
    public static final int OUTPUT_START     = 1;
    public static final int OUTPUT_END       = 10; // exclusive
    public static final int PLAYER_INV_START = 10;
    public static final int PLAYER_INV_END   = 37;
    public static final int HOTBAR_END       = 46;

    // Output slot grid: 9 slots in a single row, x=7..151 y=76
    private static final int OUT_Y = 76;
    private static final int OUT_X = 7;

    private final Container container;
    private final ContainerData data;

    /** Client-side constructor (called by MenuType factory). */
    public CelluloserMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(CelluloserBlockEntity.TOTAL_SLOTS), new SimpleContainerData(6));
    }

    /** Server-side constructor (called from BlockEntity.createMenu). */
    public CelluloserMenu(int id, Inventory playerInv, Container container, ContainerData data) {
        super(WnirRegistries.CELLULOSER_MENU.get(), id);
        this.container = container;
        this.data      = data;

        checkContainerSize(container, CelluloserBlockEntity.TOTAL_SLOTS);

        // Input slot — enchanted items, config sources, or armor/weapons for disassembly
        addSlot(new Slot(container, 0, 80, 36) {
            @Override public boolean mayPlace(ItemStack stack) {
                return CelluloserBlockEntity.isEnchanted(stack)
                    || CelluloserBlockEntity.isConfigSource(stack)
                    || CelluloserBlockEntity.isDisassemblableItem(stack);
            }
        });

        // Output slots 1–9 — extract only, no manual insertion
        for (int i = 0; i < 9; i++) {
            final int slotIndex = i + 1;
            addSlot(new Slot(container, slotIndex, OUT_X + i * 18, OUT_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }

        // Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 9 + col * 18, 109 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 9 + col * 18, 167));
        }

        addDataSlots(data);
    }

    // ── Synced data getters (client reads these) ─────────────────────────────

    public int getEnergy() {
        return ((data.get(1) & 0xFFFF) << 16) | (data.get(0) & 0xFFFF);
    }
    public int getWater()       { return data.get(2); }
    public int getCellulose()   { return data.get(3); }
    public int getRemainingXp() { return data.get(4); }
    public int getTotalXp()     { return data.get(5); }

    // ── Shift-click ──────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        if (index < OUTPUT_END) {
            // Input or output slot → player inventory
            if (!moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, result);
        } else {
            // Player inventory → input slot first (if acceptable), else move within inventory
            if (CelluloserBlockEntity.isEnchanted(stack)
                    || CelluloserBlockEntity.isConfigSource(stack)
                    || CelluloserBlockEntity.isDisassemblableItem(stack)) {
                if (!moveItemStackTo(stack, BOOK_SLOT, OUTPUT_START, false)) return ItemStack.EMPTY;
            } else if (index < PLAYER_INV_END) {
                if (!moveItemStackTo(stack, PLAYER_INV_END, HOTBAR_END, false)) return ItemStack.EMPTY;
            } else {
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
