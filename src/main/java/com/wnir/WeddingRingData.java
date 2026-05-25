package com.wnir;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

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
 *   PotionList    ListTag of {Key:String, Sample:ItemStack NBT, Count:int}
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
    private static final String KEY_STD_INV    = "si"; // prefix for 27 standard storage slots

    // Potion map: ListTag of CompoundTag {Key:String, Sample:ItemStack NBT, Count:int}
    private static final String KEY_POTION_LIST  = "PotionList";

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
        if (existing instanceof CompoundTag ct) {
            WeddingRingData data = new WeddingRingData(pet, ct);
            data.migrateOldHealingSlots();
            return data;
        }
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

    // ── Potion map storage ────────────────────────────────────────────────

    /** True for any item that carries POTION_CONTENTS (vanilla or modded potions). */
    public static boolean isPotion(ItemStack stack) {
        return !stack.isEmpty() && stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS) != null;
    }

    /** Returns the total count across all stored potion types. */
    public int getTotalPotionCount() {
        Tag t = root.get(KEY_POTION_LIST);
        if (!(t instanceof ListTag list)) return 0;
        int sum = 0;
        for (Tag entry : list) {
            if (entry instanceof CompoundTag ct) sum += ct.getIntOr("Count", 0);
        }
        return sum;
    }

    /**
     * Absorbs the stack into the potion map. Groups by potion type using a stable fingerprint
     * so vanilla and modded potions are stored separately by type.
     */
    public void addPotion(ItemStack stack) {
        if (!isPotion(stack)) return;
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        assert contents != null;
        String key = potionKey(contents);
        int amount = stack.getCount();

        Tag t = root.get(KEY_POTION_LIST);
        ListTag list = (t instanceof ListTag lt) ? lt : new ListTag();

        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag ct)) continue;
            if (key.equals(ct.getString("Key").orElse(""))) {
                ct.putInt("Count", ct.getIntOr("Count", 0) + amount);
                root.put(KEY_POTION_LIST, list);
                return;
            }
        }

        CompoundTag entry = new CompoundTag();
        entry.putString("Key", key);
        writeStackTo(entry, "Sample", stack.copyWithCount(1));
        entry.putInt("Count", amount);
        list.add(entry);
        root.put(KEY_POTION_LIST, list);
    }

    /**
     * Consumes one potion, preferring those with an INSTANT_HEALTH or REGENERATION effect.
     * Returns a sample ItemStack of the consumed type (for applying effects), or EMPTY if none stored.
     */
    public ItemStack usePotion() {
        Tag t = root.get(KEY_POTION_LIST);
        if (!(t instanceof ListTag list) || list.isEmpty()) return ItemStack.EMPTY;

        int bestIdx = -1;
        boolean bestHasHealing = false;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag ct)) continue;
            if (ct.getIntOr("Count", 0) <= 0) continue;
            ItemStack sample = readStackFrom(ct, "Sample");
            PotionContents contents = sample.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            boolean hasHealing = contents != null && hasHealingEffect(contents);
            if (bestIdx < 0 || (hasHealing && !bestHasHealing)) {
                bestIdx = i;
                bestHasHealing = hasHealing;
            }
            if (bestHasHealing) break;
        }
        if (bestIdx < 0) return ItemStack.EMPTY;

        CompoundTag ct = (CompoundTag) list.get(bestIdx);
        ItemStack sample = readStackFrom(ct, "Sample");
        int count = ct.getIntOr("Count", 0);
        if (count <= 1) {
            list.remove(bestIdx);
        } else {
            ct.putInt("Count", count - 1);
        }
        if (list.isEmpty()) root.remove(KEY_POTION_LIST);
        else root.put(KEY_POTION_LIST, list);
        return sample;
    }

    /** Drains all stored potions as ItemStacks (batches of 64). Used on pet death. */
    public List<ItemStack> drainPotions() {
        List<ItemStack> out = new ArrayList<>();
        Tag t = root.get(KEY_POTION_LIST);
        if (!(t instanceof ListTag list)) return out;
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag ct)) continue;
            int count = ct.getIntOr("Count", 0);
            if (count <= 0) continue;
            ItemStack sample = readStackFrom(ct, "Sample");
            if (sample.isEmpty()) continue;
            while (count > 0) {
                int batch = Math.min(count, 64);
                out.add(sample.copyWithCount(batch));
                count -= batch;
            }
        }
        root.remove(KEY_POTION_LIST);
        return out;
    }

    /** Stable string key for a PotionContents — used to group same-type potions in the map. */
    private static String potionKey(PotionContents contents) {
        var namedOpt = contents.potion();
        if (namedOpt.isPresent()) {
            var keyOpt = namedOpt.get().unwrapKey();
            if (keyOpt.isPresent()) return "p:" + keyOpt.get().identifier();
        }
        // Custom effects fingerprint (no named potion — rare edge case)
        List<String> parts = new ArrayList<>();
        for (MobEffectInstance eff : contents.customEffects()) {
            String effId = eff.getEffect().unwrapKey()
                .map(k -> k.identifier().toString()).orElse("?");
            parts.add(effId + ":" + eff.getAmplifier());
        }
        parts.sort(String::compareTo);
        return "c:" + String.join(",", parts);
    }

    private static boolean hasHealingEffect(PotionContents contents) {
        for (MobEffectInstance eff : contents.getAllEffects()) {
            if (eff.is(MobEffects.INSTANT_HEALTH) || eff.is(MobEffects.REGENERATION)) return true;
        }
        return false;
    }

    /** Migrates the old 2-tier HealSample/HealCount keys to PotionList. No-op if already migrated. */
    private void migrateOldHealingSlots() {
        for (int tier = 1; tier <= 2; tier++) {
            String sampleKey = "HealSample" + tier;
            String countKey  = "HealCount" + tier;
            int count = root.getIntOr(countKey, 0);
            root.remove(countKey);
            if (count <= 0) { root.remove(sampleKey); continue; }
            ItemStack sample = readStack(sampleKey);
            root.remove(sampleKey);
            if (!sample.isEmpty()) addPotion(sample.copyWithCount(count));
        }
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
        return readStackFrom(root, key);
    }

    private ItemStack readStackFrom(CompoundTag src, String key) {
        Tag tag = src.get(key);
        if (!(tag instanceof CompoundTag ct)) return ItemStack.EMPTY;
        DataResult<ItemStack> result = ItemStack.OPTIONAL_CODEC.parse(
            pet.registryAccess().createSerializationContext(NbtOps.INSTANCE), ct);
        return result.result().orElse(ItemStack.EMPTY);
    }

    private void writeStack(String key, ItemStack stack) {
        writeStackTo(root, key, stack);
    }

    private void writeStackTo(CompoundTag dest, String key, ItemStack stack) {
        if (stack.isEmpty()) {
            dest.remove(key);
            return;
        }
        DataResult<Tag> result = ItemStack.OPTIONAL_CODEC.encodeStart(
            pet.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        result.result().ifPresent(tag -> dest.put(key, tag));
    }
}
