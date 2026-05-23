package com.wnir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Menu for the Wedding Ring pet inventory.
 *
 * Slots 0..MAX_PET_SLOTS-1 — pet inventory (active ones backed by WeddingRingData; rest dummy).
 * Slots MAX_PET_SLOTS..+35  — player inventory + hotbar.
 *
 * ContainerData (3 shorts, synced server→client):
 *   [0] entityTypeId  — BuiltInRegistries.ENTITY_TYPE integer ID
 *   [1] activeSlots   — number of active pet slots
 *   [2] hasArmor      — 1 if pet has extra armor slots
 */
public class WeddingRingMenu extends AbstractContainerMenu {

    public static final int MAX_RING_SLOTS  = 8;   // labeled ring slots (weapon/shield/etc.)
    public static final int STD_SLOT_COUNT  = 27;  // standard storage grid
    public static final int MAX_PET_SLOTS   = MAX_RING_SLOTS + STD_SLOT_COUNT; // 35
    static final int STD_SLOT_COLS   = 9;
    static final int IMG_W           = 200;
    static final int SLOT_X          = 8;
    // Button row between title (y=18) and ring slot viewport
    static final int BUTTON_ROW_TOP  = 19;
    static final int BUTTON_H        = 10;
    static final int BUTTON_W        = 44;
    static final int CALM_BTN_X      = SLOT_X;
    static final int CALM_BTN_Y      = BUTTON_ROW_TOP;
    static final int AI_BTN_X        = CALM_BTN_X + BUTTON_W + 4;
    static final int AI_BTN_Y        = BUTTON_ROW_TOP;
    static final int SLOT_AREA_TOP   = BUTTON_ROW_TOP + BUTTON_H + 3; // 32
    static final int SLOT_ROW_HEIGHT = 20;
    static final int VIEWPORT_ROWS   = 4;
    static final int VIEWPORT_H      = VIEWPORT_ROWS * SLOT_ROW_HEIGHT; // 80
    static final int PET_AREA_BOTTOM = SLOT_AREA_TOP + VIEWPORT_H + 2;  // 102
    static final int STD_AREA_TOP    = PET_AREA_BOTTOM + 4;             // 106
    static final int STD_AREA_BOTTOM = STD_AREA_TOP + 3 * 18 + 4;      // 164
    static final int INV_X           = 8;
    static final int INV_Y           = STD_AREA_BOTTOM + 8;             // 172
    static final int HOTBAR_Y        = INV_Y + 58;                      // 230
    static final int IMG_H           = HOTBAR_Y + 20;                   // 250

    /** Full y-position of ring slot i (relative to topPos). */
    static int petSlotY(int i) { return SLOT_AREA_TOP + i * SLOT_ROW_HEIGHT + 2; }
    /** Position of standard storage slot i (0-based within the 27 grid). */
    static int stdSlotX(int i) { return INV_X + (i % STD_SLOT_COLS) * 18; }
    static int stdSlotY(int i) { return STD_AREA_TOP + (i / STD_SLOT_COLS) * 18 + 2; }

    // ── Slot definition record ────────────────────────────────────────────────

    public record SlotDef(
        String name,
        String desc,
        Supplier<ItemStack> getter,
        Consumer<ItemStack> setter,
        Predicate<ItemStack> accepts,
        boolean isHealing
    ) {}

    // ── Fields ────────────────────────────────────────────────────────────────

    private final SimpleContainerData data = new SimpleContainerData(7);
    private final Mob entity;              // null on client
    private final WeddingRingData ringData; // null on client
    private final List<SlotDef> slotDefs;
    private int scrollOffset = 0;          // pixel scroll, used by screen

    // ── Client factory ────────────────────────────────────────────────────────

    static WeddingRingMenu clientSide(int id, Inventory playerInv) {
        return new WeddingRingMenu(id, playerInv);
    }

    private WeddingRingMenu(int id, Inventory playerInv) {
        super(WnirRegistries.WEDDING_RING_MENU.get(), id);
        this.entity   = null;
        this.ringData = null;
        this.slotDefs = List.of();
        addDataSlots(data);
        SimpleContainer dummy = new SimpleContainer(MAX_PET_SLOTS);
        for (int i = 0; i < MAX_RING_SLOTS; i++) {
            addSlot(new Slot(dummy, i, SLOT_X, petSlotY(i)));
        }
        for (int i = 0; i < STD_SLOT_COUNT; i++) {
            addSlot(new Slot(dummy, MAX_RING_SLOTS + i, stdSlotX(i), stdSlotY(i)));
        }
        addStandardInventorySlots(playerInv);
    }

    // ── Server constructor ────────────────────────────────────────────────────

    WeddingRingMenu(int id, Inventory playerInv, Mob entity, WeddingRingData ringData) {
        super(WnirRegistries.WEDDING_RING_MENU.get(), id);
        this.entity   = entity;
        this.ringData = ringData;

        this.slotDefs = buildSlotDefs(ringData);

        data.set(0, BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()));
        data.set(1, slotDefs.size());
        data.set(2, ringData.hasArmor() ? 1 : 0);
        // [3] and [4] are healing counts, updated live in broadcastChanges()
        addDataSlots(data);

        SimpleContainer dummies = new SimpleContainer(MAX_RING_SLOTS);
        for (int i = 0; i < MAX_RING_SLOTS; i++) {
            if (i < slotDefs.size()) {
                final SlotDef def = slotDefs.get(i);
                final int idx = i;
                if (def.isHealing()) {
                    addSlot(new HealingRingSlot(ringData, SLOT_X, petSlotY(i)));
                } else {
                    addSlot(new Slot(dummies, i, SLOT_X, petSlotY(i)) {
                        @Override public boolean mayPlace(ItemStack s) { return def.accepts().test(s); }
                        @Override public ItemStack getItem()           { return def.getter().get(); }
                        @Override public void set(ItemStack s) {
                            def.setter().accept(s);
                            WeddingRingAttributeManager.recalculate(entity);
                        }
                        @Override public ItemStack remove(int amount) {
                            ItemStack cur = def.getter().get();
                            if (cur.isEmpty()) return ItemStack.EMPTY;
                            int take = Math.min(amount, cur.getCount());
                            ItemStack result = cur.copyWithCount(take);
                            def.setter().accept(take >= cur.getCount() ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - take));
                            WeddingRingAttributeManager.recalculate(entity);
                            return result;
                        }
                    });
                }
            } else {
                addSlot(new Slot(dummies, i, SLOT_X, petSlotY(i)) {
                    @Override public boolean mayPlace(ItemStack s) { return false; }
                    @Override public boolean mayPickup(Player p)   { return false; }
                });
            }
        }

        // Standard 27-slot storage grid (indices MAX_RING_SLOTS .. MAX_PET_SLOTS-1)
        SimpleContainer stdDummies = new SimpleContainer(STD_SLOT_COUNT);
        for (int i = 0; i < STD_SLOT_COUNT; i++) {
            final int si = i;
            addSlot(new Slot(stdDummies, si, stdSlotX(si), stdSlotY(si)) {
                @Override public boolean mayPlace(ItemStack s) { return true; }
                @Override public ItemStack getItem()           { return ringData.getStdSlot(si); }
                @Override public void set(ItemStack s)         { ringData.setStdSlot(si, s); }
                @Override public ItemStack remove(int amount) {
                    ItemStack cur = ringData.getStdSlot(si);
                    if (cur.isEmpty()) return ItemStack.EMPTY;
                    int take = Math.min(amount, cur.getCount());
                    ItemStack result = cur.copyWithCount(take);
                    ringData.setStdSlot(si, take >= cur.getCount()
                        ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - take));
                    return result;
                }
            });
        }
        addStandardInventorySlots(playerInv);
    }

    // ── Slot definitions ──────────────────────────────────────────────────────

    static List<SlotDef> buildSlotDefs(WeddingRingData data) {
        List<SlotDef> defs = new ArrayList<>();

        defs.add(new SlotDef("Weapon", "Sword, axe, spear, or mace",
            data::getWeapon, data::setWeapon,
            s -> s.isEmpty()
                || s.has(DataComponents.WEAPON)
                || s.has(DataComponents.KINETIC_WEAPON)
                || s.getItem() instanceof MaceItem
                || s.getItem() instanceof AxeItem,
            false
        ));

        defs.add(new SlotDef("Shield", "Reduces incoming damage",
            data::getShield, data::setShield,
            s -> s.isEmpty() || s.has(DataComponents.BLOCKS_ATTACKS),
            false
        ));

        // Healing potion slot — deposit-only; handled via HealingRingSlot
        defs.add(new SlotDef("Healing Potions", "Instant Health I or II — unlimited storage",
            () -> ItemStack.EMPTY, s -> {},
            WeddingRingData::isHealingPotion,
            true
        ));

        if (data.hasArmor()) {
            defs.add(new SlotDef("Helmet", "Head armor",
                data::getArmorHead, s -> { data.setArmorHead(s); },
                s -> s.isEmpty() || armorFor(s, EquipmentSlot.HEAD), false
            ));
            defs.add(new SlotDef("Chestplate", "Chest armor",
                data::getArmorChest, s -> { data.setArmorChest(s); },
                s -> s.isEmpty() || armorFor(s, EquipmentSlot.CHEST), false
            ));
            defs.add(new SlotDef("Leggings", "Leg armor",
                data::getArmorLegs, s -> { data.setArmorLegs(s); },
                s -> s.isEmpty() || armorFor(s, EquipmentSlot.LEGS), false
            ));
            defs.add(new SlotDef("Boots", "Foot armor",
                data::getArmorFeet, s -> { data.setArmorFeet(s); },
                s -> s.isEmpty() || armorFor(s, EquipmentSlot.FEET), false
            ));
        }

        return defs;
    }

    private static boolean armorFor(ItemStack s, EquipmentSlot slot) {
        var eq = s.get(DataComponents.EQUIPPABLE);
        return eq != null && eq.slot() == slot;
    }

    // ── Player inventory ──────────────────────────────────────────────────────

    private void addStandardInventorySlots(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int getEntityTypeId()    { return data.get(0); }
    public int getActiveSlotCount() { return data.get(1); }
    public boolean hasArmor()       { return data.get(2) != 0; }
    public int getHealingCount1()   { return data.get(3); }
    public int getHealingCount2()   { return data.get(4); }
    public boolean isCalm()         { return data.get(5) != 0; }
    public boolean isAiEnabled()    { return data.get(6) != 0; }
    public List<SlotDef> getSlotDefs() { return slotDefs; }
    public int getScrollOffset()    { return scrollOffset; }
    public void setScrollOffset(int px) { scrollOffset = Math.max(0, px); }

    @Override
    public void broadcastChanges() {
        if (ringData != null) {
            data.set(3, Math.min(ringData.getHealingCount1(), Short.MAX_VALUE));
            data.set(4, Math.min(ringData.getHealingCount2(), Short.MAX_VALUE));
            data.set(5, ringData.isCalm() ? 1 : 0);
            data.set(6, ringData.isAiEnabled() ? 1 : 0);
        }
        super.broadcastChanges();
    }

    /** Called server-side when the client clicks a UI button. */
    public void onButtonClick(int id) {
        if (id == 0 && ringData != null) ringData.setCalm(!ringData.isCalm());
        if (id == 1 && ringData != null) ringData.setAiEnabled(!ringData.isAiEnabled());
    }

    @Override
    public boolean stillValid(Player player) {
        return entity == null || (entity.isAlive() && entity.distanceTo(player) <= 64);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack  = slot.getItem();
        ItemStack result = stack.copy();

        int active   = getActiveSlotCount();
        int invStart = MAX_PET_SLOTS;        // 35
        int invEnd   = MAX_PET_SLOTS + 36;   // 71

        if (index < MAX_PET_SLOTS) {
            // From ring slot or std slot → player inventory
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
        } else if (index < invStart + 27) {
            // From player main inventory → ring slots, then std slots, then hotbar
            if (!moveItemStackTo(stack, 0, active, false)
                && !moveItemStackTo(stack, MAX_RING_SLOTS, MAX_PET_SLOTS, false)
                && !moveItemStackTo(stack, invStart + 27, invEnd, false)) return ItemStack.EMPTY;
        } else {
            // From hotbar → ring slots, then std slots, then main inventory
            if (!moveItemStackTo(stack, 0, active, false)
                && !moveItemStackTo(stack, MAX_RING_SLOTS, MAX_PET_SLOTS, false)
                && !moveItemStackTo(stack, invStart, invStart + 27, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return result;
    }
}
