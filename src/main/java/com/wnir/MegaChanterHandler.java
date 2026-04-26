package com.wnir;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.event.AnvilUpdateEvent;

/**
 * Bypasses the vanilla "Too Expensive" (cost >= 40) cap on anvils for players
 * with the mega_chanter effect. Replicates vanilla AnvilMenu.createResultInternal()
 * logic but omits the `cost >= 40 → empty output` guard.
 */
public final class MegaChanterHandler {

    /** Vanilla threshold that triggers "Too Expensive" and blocks the result. */
    private static final int VANILLA_TOO_EXPENSIVE = 40;
    /** Cost we display instead — just below the vanilla cap so AnvilScreen stays happy. */
    private static final int DISPLAY_COST_CAP = 39;

    private MegaChanterHandler() {}

    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        if (!event.getPlayer().hasEffect(WnirRegistries.MEGA_CHANTER)) return;

        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || !EnchantmentHelper.canStoreEnchantments(left)) return;

        ItemStack result = left.copy();
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(
            EnchantmentHelper.getEnchantmentsForCrafting(result)
        );

        long baseCost = left.getOrDefault(DataComponents.REPAIR_COST, 0)
                      + right.getOrDefault(DataComponents.REPAIR_COST, 0);

        int workCost = 0;
        int materialCost = 0;

        if (!right.isEmpty()) {
            boolean isBook = right.has(DataComponents.STORED_ENCHANTMENTS);

            if (result.isDamageableItem() && left.isValidRepairItem(right)) {
                int consumed = applyMaterialRepair(result, right);
                if (consumed < 0) return;
                materialCost = consumed;
                workCost += consumed;
            } else {
                if (!isBook && (!result.is(right.getItem()) || !result.isDamageableItem())) return;

                if (result.isDamageableItem() && !isBook) {
                    workCost += applyCombineDurability(result, left, right);
                }

                int enchCost = applyEnchantments(enchantments, result, right, isBook, event);
                if (enchCost < 0) return;
                workCost += enchCost;
            }
        }

        int renameCost = applyRename(result, left, event.getName());
        workCost += renameCost;
        if (workCost <= 0) return;

        long totalCost = Mth.clamp(baseCost + workCost, 0L, Integer.MAX_VALUE);
        // KEY DIFFERENCE FROM VANILLA: allow the operation even when cost >= 40,
        // but cap the displayed cost so AnvilScreen doesn't show "Too Expensive".
        if (totalCost >= VANILLA_TOO_EXPENSIVE) totalCost = DISPLAY_COST_CAP;

        updateRepairCost(result, left, right, workCost - renameCost);
        EnchantmentHelper.setEnchantments(result, enchantments.toImmutable());

        event.setOutput(result);
        event.setXpCost((int) Math.min(totalCost, Integer.MAX_VALUE));
        event.setMaterialCost(materialCost);
    }

    /** Repairs with material items. Returns items consumed, or -1 to abort. */
    private static int applyMaterialRepair(ItemStack result, ItemStack right) {
        int repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
        if (repairAmount <= 0) return -1;
        int j = 0;
        for (; repairAmount > 0 && j < right.getCount(); j++) {
            result.setDamageValue(result.getDamageValue() - repairAmount);
            repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
        }
        return j;
    }

    /** Combines two matching items for durability. Returns xp work cost added. */
    private static int applyCombineDurability(ItemStack result, ItemStack left, ItemStack right) {
        int leftDur  = left.getMaxDamage() - left.getDamageValue();
        int rightDur = right.getMaxDamage() - right.getDamageValue();
        int combined = rightDur + result.getMaxDamage() * 12 / 100;
        int newDamage = Math.max(0, result.getMaxDamage() - (leftDur + combined));
        if (newDamage < result.getDamageValue()) {
            result.setDamageValue(newDamage);
            return 2;
        }
        return 0;
    }

    /** Merges enchantments from right into result. Returns xp work cost, or -1 to abort. */
    private static int applyEnchantments(
        ItemEnchantments.Mutable enchantments,
        ItemStack result,
        ItemStack right,
        boolean isBook,
        AnvilUpdateEvent event
    ) {
        ItemEnchantments rightEnchants = EnchantmentHelper.getEnchantmentsForCrafting(right);
        boolean anyCompatible = false;
        boolean anyIncompatible = false;
        int workCost = 0;

        for (Object2IntMap.Entry<Holder<Enchantment>> entry : rightEnchants.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            int curLevel = enchantments.getLevel(holder);
            int newLevel = (curLevel == entry.getIntValue()) ? entry.getIntValue() + 1
                                                             : Math.max(entry.getIntValue(), curLevel);

            boolean compatible = result.supportsEnchantment(holder);
            if (event.getPlayer().getAbilities().instabuild) compatible = true;
            for (Holder<Enchantment> existing : enchantments.keySet()) {
                if (!existing.equals(holder) && !Enchantment.areCompatible(holder, existing)) {
                    compatible = false;
                    workCost++;
                }
            }

            if (!compatible) {
                anyIncompatible = true;
            } else {
                anyCompatible = true;
                enchantments.set(holder, newLevel);
                int anvilCost = holder.value().getAnvilCost();
                if (isBook) anvilCost = Math.max(1, anvilCost / 2);
                workCost += anvilCost * newLevel;
                if (result.getCount() > 1) workCost = VANILLA_TOO_EXPENSIVE;
            }
        }

        if (anyIncompatible && !anyCompatible) return -1;
        return workCost;
    }

    /** Applies rename or name removal. Returns xp work cost added. */
    private static int applyRename(ItemStack result, ItemStack left, String name) {
        if (name != null && !StringUtil.isBlank(name)) {
            if (!name.equals(left.getHoverName().getString())) {
                result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                return 1;
            }
        } else if (left.has(DataComponents.CUSTOM_NAME)) {
            result.remove(DataComponents.CUSTOM_NAME);
            return 1;
        }
        return 0;
    }

    /** Updates REPAIR_COST on the result item. */
    private static void updateRepairCost(ItemStack result, ItemStack left, ItemStack right, int nonRenameCost) {
        int repairCost = result.getOrDefault(DataComponents.REPAIR_COST, 0);
        if (repairCost < right.getOrDefault(DataComponents.REPAIR_COST, 0)) {
            repairCost = right.getOrDefault(DataComponents.REPAIR_COST, 0);
        }
        if (nonRenameCost != 0) {
            repairCost = (int) Math.min((long) repairCost * 2L + 1L, Integer.MAX_VALUE);
        }
        result.set(DataComponents.REPAIR_COST, repairCost);
    }
}
