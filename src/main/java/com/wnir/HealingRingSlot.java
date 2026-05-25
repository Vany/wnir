package com.wnir;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Deposit-only slot for healing potions.
 * Potions placed here are immediately absorbed into the per-tier counter.
 * The slot always appears empty so the player can keep depositing.
 */
public class HealingRingSlot extends Slot {

    private final WeddingRingData data;
    private static final SimpleContainer DUMMY = new SimpleContainer(1);

    public HealingRingSlot(WeddingRingData data, int x, int y) {
        super(DUMMY, 0, x, y);
        this.data = data;
    }

    @Override public boolean mayPlace(ItemStack stack) { return WeddingRingData.isPotion(stack); }

    // Always report empty so vanilla keeps the slot open for more deposits
    @Override public ItemStack getItem()           { return ItemStack.EMPTY; }
    @Override public boolean  hasItem()            { return false; }
    @Override public boolean  mayPickup(Player p)  { return false; }
    @Override public ItemStack remove(int amount)  { return ItemStack.EMPTY; }
    @Override public int getMaxStackSize()         { return 64; }
    @Override public int getMaxStackSize(ItemStack s) { return 64; }

    @Override
    public void set(ItemStack stack) {
        // Vanilla calls set() with whatever the player dropped in; absorb it immediately
        if (!stack.isEmpty()) data.addPotion(stack);
    }

    @Override public void onTake(Player player, ItemStack stack) {}
}
