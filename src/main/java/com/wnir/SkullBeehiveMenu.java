package com.wnir;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu for the Skull Beehive turret.
 *
 * Slot layout (GUI-relative coordinates):
 *   Bow slots  (0-5):   y=20,  x = 7, 25, 43, 61, 79, 97
 *   Arrow recv (6):     y=42,  x = 7
 *   Gunpwd recv(7):     y=42,  x = 25
 *   [slots 8-135 not shown in GUI — accessible via pipes/hoppers only]
 *   Player inv (9×3):   y=82,  x = 8..152
 *   Hotbar (9×1):       y=140, x = 8..152
 *
 * Progress bars (rendered in screen only):
 *   Arrow count:     y=64, drawn by SkullBeehiveScreen
 *   Gunpowder count: y=76, drawn by SkullBeehiveScreen
 *
 * ContainerData (2 shorts synced to client):
 *   0 → arrow count in storage (0-1024)
 *   1 → gunpowder count in storage (0-1024)
 */
public class SkullBeehiveMenu extends AbstractContainerMenu {

    static final int DISPLAYED_CONTAINER_SLOTS = 8; // slots 0-7 shown in GUI
    static final int IMAGE_HEIGHT = 165;

    private final Container container;
    final ContainerData data;

    /** Client-side constructor (called by MenuType factory). */
    public SkullBeehiveMenu(int id, Inventory playerInv) {
        this(id, playerInv,
            new SimpleContainer(SkullBeehiveBlockEntity.TOTAL_SLOTS),
            new SimpleContainerData(2));
    }

    /** Server-side constructor (called by SkullBeehiveBlockEntity.createMenu). */
    public SkullBeehiveMenu(int id, Inventory playerInv, Container container, ContainerData data) {
        super(WnirRegistries.SKULL_BEEHIVE_MENU.get(), id);
        this.container = container;
        this.data = data;
        checkContainerSize(container, SkullBeehiveBlockEntity.TOTAL_SLOTS);
        container.startOpen(playerInv.player);
        addDataSlots(data);

        // ── Bow / crossbow slots (0-5) ────────────────────────────────────
        for (int i = 0; i < SkullBeehiveBlockEntity.WEAPON_SLOTS; i++) {
            final int slotIdx = SkullBeehiveBlockEntity.WEAPON_START + i;
            addSlot(new Slot(container, slotIdx, 7 + i * 18, 20) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return SkullBeehiveBlockEntity.isWeapon(stack);
                }
            });
        }

        // ── Arrow receiver (slot 6) — left column, y=42 ──────────────────
        addSlot(new Slot(container, SkullBeehiveBlockEntity.ARROW_RECEIVER, 7, 42) {
            @Override public boolean mayPlace(ItemStack stack) {
                return SkullBeehiveBlockEntity.isArrow(stack);
            }
        });

        // ── Gunpowder receiver (slot 7) — below arrow receiver, y=60 ────
        addSlot(new Slot(container, SkullBeehiveBlockEntity.GUNPOWDER_RECEIVER, 7, 60) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(net.minecraft.world.item.Items.GUNPOWDER);
            }
        });

        // Note: storage slots 8-135 are NOT added to the menu UI.
        // They are accessible to hoppers/pipes via the IItemHandler capability.

        // ── Player inventory (3×9) ────────────────────────────────────────
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 82 + row * 18));
            }
        }

        // ── Hotbar (1×9) ─────────────────────────────────────────────────
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 140));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    /**
     * Shift-click routing:
     *   bow/recv slot → player inventory
     *   player inv    → bow slots (weapons) or arrow recv or gunpowder recv
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        if (index < DISPLAYED_CONTAINER_SLOTS) {
            // Container slot → player inventory.
            if (!moveItemStackTo(stack, DISPLAYED_CONTAINER_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Player inventory → appropriate container slot.
            if (SkullBeehiveBlockEntity.isWeapon(stack)) {
                if (!moveItemStackTo(stack,
                    SkullBeehiveBlockEntity.WEAPON_START,
                    SkullBeehiveBlockEntity.WEAPON_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (SkullBeehiveBlockEntity.isArrow(stack)) {
                if (!moveItemStackTo(stack,
                    SkullBeehiveBlockEntity.ARROW_RECEIVER,
                    SkullBeehiveBlockEntity.ARROW_RECEIVER + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.is(net.minecraft.world.item.Items.GUNPOWDER)) {
                if (!moveItemStackTo(stack,
                    SkullBeehiveBlockEntity.GUNPOWDER_RECEIVER,
                    SkullBeehiveBlockEntity.GUNPOWDER_RECEIVER + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();

        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
    }
}
