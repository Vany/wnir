package com.wnir;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All Wedding Ring state for a bound pet — stored in pet.getPersistentData() under "WeddingRingData".
 * Reads/writes directly to the live CompoundTag; caller must call save() after mutations.
 *
 * Structure of WeddingRingData tag:
 *   OwnerUUID     String
 *   weapon        ItemStack NBT (optional)
 *   shield        ItemStack NBT (optional)
 *   HealingSample ItemStack NBT (1 unit, preserves PotionContents)
 *   HealingCount  int

 *   food        ItemStack NBT (optional)
 *   armor_head  ItemStack NBT (optional)   — absent for wolves
 *   armor_chest ItemStack NBT (optional)
 *   armor_legs  ItemStack NBT (optional)
 *   armor_feet  ItemStack NBT (optional)
 *   hasArmor    byte (1 = armor slots present)
 *   FoodLevel   int   (0-20)
 *   Saturation  float
 *   FoodExhaustion float
 *   FoodTimer   int
 */
public final class WeddingRingData {

    private static final String ROOT_KEY       = "WeddingRingData";
    private static final String KEY_OWNER      = "OwnerUUID";
    private static final String KEY_WEAPON     = "weapon";
    private static final String KEY_SHIELD     = "shield";
    private static final String KEY_STD_INV       = "si"; // prefix for 27 standard storage slots

    private static final String KEY_HEAL_SAMPLE_1 = "HealSample1"; // ItemStack NBT for Healing I
    private static final String KEY_HEAL_SAMPLE_2 = "HealSample2"; // ItemStack NBT for Healing II
    private static final String KEY_HEAL_COUNT_1  = "HealCount1";
    private static final String KEY_HEAL_COUNT_2  = "HealCount2";
    private static final String KEY_ARMOR_HEAD  = "armor_head";
    private static final String KEY_ARMOR_CHEST = "armor_chest";
    private static final String KEY_ARMOR_LEGS  = "armor_legs";
    private static final String KEY_ARMOR_FEET  = "armor_feet";
    private static final String KEY_HAS_ARMOR  = "hasArmor";
    private static final String KEY_CALM        = "calm";
    private static final String KEY_AI_ENABLED  = "aiEnabled";
    private static final String KEY_FOOD_LEVEL  = "FoodLevel";
    private static final String KEY_SATURATION  = "Saturation";
    private static final String KEY_EXHAUSTION  = "FoodExhaustion";
    private static final String KEY_FOOD_TIMER  = "FoodTimer";
    private static final String KEY_XP_BUFFER   = "xp";

    private static final String KEY_LLM_MEMORY  = "LlmMemory";
    private static final String KEY_LLM_TODO    = "LlmTodo";
    private static final String KEY_LLM_HISTORY = "LlmHistory";

    private static final Gson GSON = new Gson();
    @SuppressWarnings("unchecked")
    private static final Type HISTORY_TYPE = new TypeToken<List<Map<String, Object>>>(){}.getType();

    private final Mob pet;
    private final CompoundTag root;

    private WeddingRingData(Mob pet, CompoundTag root) {
        this.pet  = pet;
        this.root = root;
    }

    /** Returns null if the pet is not bound (no WeddingRingData tag). */
    public static WeddingRingData get(Mob pet) {
        CompoundTag pd = pet.getPersistentData();
        Tag existing = pd.get(ROOT_KEY);
        if (existing instanceof CompoundTag ct) return new WeddingRingData(pet, ct);
        return null;
    }

    /** Binds the pet: creates the WeddingRingData tag with owner UUID and empty slots. */
    public static WeddingRingData bind(Mob pet, UUID ownerUUID, boolean addArmor) {
        CompoundTag root = new CompoundTag();
        root.putString(KEY_OWNER, ownerUUID.toString());
        root.putByte(KEY_HAS_ARMOR, (byte)(addArmor ? 1 : 0));
        root.putInt(KEY_FOOD_LEVEL, 20);
        root.putFloat(KEY_SATURATION, 5.0f);
        root.putFloat(KEY_EXHAUSTION, 0.0f);
        root.putInt(KEY_FOOD_TIMER, 0);
        pet.getPersistentData().put(ROOT_KEY, root);
        return new WeddingRingData(pet, root);
    }

    public static void remove(Mob pet) {
        pet.getPersistentData().remove(ROOT_KEY);
    }

    public UUID getOwnerUUID() {
        try { return UUID.fromString(root.getString(KEY_OWNER).orElse("")); }
        catch (Exception e) { return new UUID(0, 0); }
    }

    public boolean hasArmor()    { return root.getByteOr(KEY_HAS_ARMOR, (byte) 0) != 0; }
    public boolean isCalm()      { return root.getByteOr(KEY_CALM, (byte) 0) != 0; }
    public void    setCalm(boolean v) { root.putByte(KEY_CALM, (byte)(v ? 1 : 0)); }
    public boolean isAiEnabled() { return root.getByteOr(KEY_AI_ENABLED, (byte) 1) != 0; }
    public void    setAiEnabled(boolean v) { root.putByte(KEY_AI_ENABLED, (byte)(v ? 1 : 0)); }

    // ── Slots ─────────────────────────────────────────────────────────────

    public ItemStack getWeapon()     { return readStack(KEY_WEAPON); }
    public ItemStack getShield()     { return readStack(KEY_SHIELD); }
    public ItemStack getArmorHead()  { return readStack(KEY_ARMOR_HEAD); }
    public ItemStack getArmorChest() { return readStack(KEY_ARMOR_CHEST); }
    public ItemStack getArmorLegs()  { return readStack(KEY_ARMOR_LEGS); }
    public ItemStack getArmorFeet()  { return readStack(KEY_ARMOR_FEET); }

    public void setWeapon(ItemStack s)     { writeStack(KEY_WEAPON, s); }
    public void setShield(ItemStack s)     { writeStack(KEY_SHIELD, s); }
    public void setArmorHead(ItemStack s)  { writeStack(KEY_ARMOR_HEAD, s); }
    public void setArmorChest(ItemStack s) { writeStack(KEY_ARMOR_CHEST, s); }
    public void setArmorLegs(ItemStack s)  { writeStack(KEY_ARMOR_LEGS, s); }
    public void setArmorFeet(ItemStack s)  { writeStack(KEY_ARMOR_FEET, s); }

    // ── Standard storage (27 slots) ──────────────────────────────────────

    public ItemStack getStdSlot(int i)            { return readStack(KEY_STD_INV + i); }
    public void      setStdSlot(int i, ItemStack s) { writeStack(KEY_STD_INV + i, s); }

    // ── Healing slot ──────────────────────────────────────────────────────

    public int getHealingCount1() { return root.getIntOr(KEY_HEAL_COUNT_1, 0); }
    public int getHealingCount2() { return root.getIntOr(KEY_HEAL_COUNT_2, 0); }
    public int getTotalHealingCount() { return getHealingCount1() + getHealingCount2(); }

    /**
     * Absorbs the stack into the internal buffer. Does not modify the ItemStack.
     * The first potion of each tier is stored as a sample to reconstruct drops on death.
     */
    public void addHealingPotion(ItemStack stack) {
        if (stack.isEmpty() || !isHealingPotion(stack)) return;
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        boolean isStrong = contents != null && contents.is(Potions.STRONG_HEALING);
        int amount = stack.getCount();
        if (isStrong) {
            if (getHealingCount2() == 0) writeStack(KEY_HEAL_SAMPLE_2, stack.copyWithCount(1));
            root.putInt(KEY_HEAL_COUNT_2, getHealingCount2() + amount);
        } else {
            if (getHealingCount1() == 0) writeStack(KEY_HEAL_SAMPLE_1, stack.copyWithCount(1));
            root.putInt(KEY_HEAL_COUNT_1, getHealingCount1() + amount);
        }
    }

    /**
     * Consumes one potion for healing. Uses Healing I first, then Healing II.
     * Returns amplifier (0 = Healing I, 1 = Healing II), or -1 if none available.
     */
    public int useHealingPotion() {
        int c1 = getHealingCount1();
        if (c1 > 0) {
            root.putInt(KEY_HEAL_COUNT_1, c1 - 1);
            if (c1 - 1 <= 0) root.remove(KEY_HEAL_SAMPLE_1);
            return 0;
        }
        int c2 = getHealingCount2();
        if (c2 > 0) {
            root.putInt(KEY_HEAL_COUNT_2, c2 - 1);
            if (c2 - 1 <= 0) root.remove(KEY_HEAL_SAMPLE_2);
            return 1;
        }
        return -1;
    }

    /** Drains all stored potions as ItemStacks (batches of 64). Clears both tiers. */
    public List<ItemStack> drainHealingPotions() {
        List<ItemStack> out = new ArrayList<>();
        drainHealingTier(KEY_HEAL_SAMPLE_1, KEY_HEAL_COUNT_1, out);
        drainHealingTier(KEY_HEAL_SAMPLE_2, KEY_HEAL_COUNT_2, out);
        return out;
    }

    private void drainHealingTier(String sampleKey, String countKey, List<ItemStack> out) {
        int count = root.getIntOr(countKey, 0);
        if (count <= 0) return;
        ItemStack sample = readStack(sampleKey);
        root.remove(sampleKey);
        root.putInt(countKey, 0);
        if (sample.isEmpty()) return;
        while (count > 0) {
            int batch = Math.min(count, 64);
            out.add(sample.copyWithCount(batch));
            count -= batch;
        }
    }

    public static boolean isHealingPotion(ItemStack stack) {
        if (stack.isEmpty()) return false;
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        return contents.is(Potions.HEALING) || contents.is(Potions.STRONG_HEALING);
    }

    // ── Hunger simulation ─────────────────────────────────────────────────

    public int   getFoodLevel()     { return root.getIntOr(KEY_FOOD_LEVEL, 20); }
    public float getSaturation()    { return root.getFloatOr(KEY_SATURATION, 5.0f); }
    public float getExhaustion()    { return root.getFloatOr(KEY_EXHAUSTION, 0.0f); }
    public int   getFoodTimer()     { return root.getIntOr(KEY_FOOD_TIMER, 0); }

    public void setFoodLevel(int v)       { root.putInt(KEY_FOOD_LEVEL, Math.max(0, Math.min(20, v))); }
    public void setSaturation(float v)    { root.putFloat(KEY_SATURATION, Math.max(0, Math.min(20, v))); }
    public void setExhaustion(float v)    { root.putFloat(KEY_EXHAUSTION, v); }
    public void setFoodTimer(int v)       { root.putInt(KEY_FOOD_TIMER, v); }

    // ── XP buffer ─────────────────────────────────────────────────────────

    public int  getXp()           { return root.getIntOr(KEY_XP_BUFFER, 0); }
    public void addXp(int amount) { if (amount > 0) root.putInt(KEY_XP_BUFFER, getXp() + amount); }

    /** Returns all buffered XP and clears the buffer. */
    public int  drainXp()         { int v = getXp(); if (v > 0) root.remove(KEY_XP_BUFFER); return v; }

    public void addExhaustion(float amount) {
        float ex = getExhaustion() + amount;
        while (ex >= 4.0f) {
            ex -= 4.0f;
            float sat = getSaturation();
            if (sat > 0) {
                setSaturation(Math.max(0, sat - 1.0f));
            } else {
                setFoodLevel(getFoodLevel() - 1);
            }
        }
        setExhaustion(ex);
    }

    // ── LLM state ────────────────────────────────────────────────────────────

    public List<String> getLlmMemory() {
        Tag tag = root.get(KEY_LLM_MEMORY);
        if (!(tag instanceof ListTag list)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Tag t : list) {
            if (t instanceof StringTag st) out.add(st.value());
        }
        return out;
    }

    public void setLlmMemory(List<String> entries) {
        ListTag list = new ListTag();
        for (String s : entries) list.add(StringTag.valueOf(s));
        root.put(KEY_LLM_MEMORY, list);
    }

    public List<String> getLlmTodo() {
        Tag tag = root.get(KEY_LLM_TODO);
        if (!(tag instanceof ListTag list)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Tag t : list) {
            if (t instanceof StringTag st) out.add(st.value());
        }
        return out;
    }

    public void setLlmTodo(List<String> entries) {
        ListTag list = new ListTag();
        for (String s : entries) list.add(StringTag.valueOf(s));
        root.put(KEY_LLM_TODO, list);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLlmHistory() {
        Tag tag = root.get(KEY_LLM_HISTORY);
        if (tag instanceof ListTag list) {
            // Current format: one StringTag per message
            List<Map<String, Object>> result = new ArrayList<>();
            for (Tag t : list) {
                if (t instanceof StringTag st) {
                    try {
                        Map<String, Object> msg = GSON.fromJson(st.value(), Map.class);
                        if (msg != null) result.add(msg);
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }
        // Legacy format: entire history as one JSON string — migrate silently
        String json = root.getString(KEY_LLM_HISTORY).orElse("");
        if (json.isEmpty()) return new ArrayList<>();
        try {
            List<Map<String, Object>> result = GSON.fromJson(json, HISTORY_TYPE);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) { return new ArrayList<>(); }
    }

    public void setLlmHistory(List<Map<String, Object>> history) {
        // Store as ListTag so no single NBT string exceeds the 65535-byte writeUTF limit
        ListTag list = new ListTag();
        for (Map<String, Object> msg : history) {
            list.add(StringTag.valueOf(GSON.toJson(msg)));
        }
        root.put(KEY_LLM_HISTORY, list);
    }

    // ── Serialization helpers ─────────────────────────────────────────────

    private ItemStack readStack(String key) {
        Tag tag = root.get(key);
        if (!(tag instanceof CompoundTag ct)) return ItemStack.EMPTY;
        DataResult<ItemStack> result = ItemStack.OPTIONAL_CODEC.parse(
            pet.registryAccess().createSerializationContext(NbtOps.INSTANCE), ct);
        return result.result().orElse(ItemStack.EMPTY);
    }

    private void writeStack(String key, ItemStack stack) {
        if (stack.isEmpty()) {
            root.remove(key);
            return;
        }
        DataResult<Tag> result = ItemStack.OPTIONAL_CODEC.encodeStart(
            pet.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        result.result().ifPresent(tag -> root.put(key, tag));
    }
}
