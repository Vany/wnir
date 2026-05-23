package com.wnir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Per-server-tick handler that processes hunger and healing for all bound pets on loaded levels.
 */
public final class WeddingRingTickHandler {

    private static final long HUNGER_CAPTION_COOLDOWN = 60 * 20L;
    private static final int  HEAL_COOLDOWN_TICKS     = 40;
    private static final int  FEED_CHECK_INTERVAL     = 100;
    private static final int  EAT_INTERVAL            = 80; // ticks between food consumption

    private WeddingRingTickHandler() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            level.getEntities().getAll().forEach(entity -> {
                if (entity instanceof Mob mob && mob.isAlive()) {
                    WeddingRingData data = WeddingRingData.get(mob);
                    if (data != null) {
                        tickPet(mob, data, level);
                    }
                }
            });
        }
    }

    private static void tickPet(Mob pet, WeddingRingData data, ServerLevel level) {
        long gameTime = level.getGameTime();

        WeddingRingTargetFilter.onServerTick(pet, gameTime);

        // ── Hunger simulation ─────────────────────────────────────────────
        data.addExhaustion(0.005f); // base passive exhaustion per tick

        int foodLevel  = data.getFoodLevel();
        float saturation = data.getSaturation();
        int foodTimer  = data.getFoodTimer() + 1;
        data.setFoodTimer(foodTimer);

        boolean inCombat = pet.getTarget() != null;

        // HP regen (mirrors vanilla FoodData)
        if (saturation > 0 && foodLevel >= 20) {
            if (foodTimer >= 10) {
                pet.heal(1.0f);
                data.setSaturation(Math.max(0, saturation - 3.0f));
                data.setFoodTimer(0);
            }
        } else if (foodLevel >= 18) {
            if (foodTimer >= 80) {
                pet.heal(1.0f);
                data.setFoodTimer(0);
            }
        } else if (foodLevel <= 0 && pet.getHealth() > 1.0f) {
            if (foodTimer >= 80) {
                pet.hurt(level.damageSources().starve(), 1.0f);
                data.setFoodTimer(0);
            }
        }

        // Food consumption: at most once per EAT_INTERVAL ticks, only when genuinely hungry
        if (!inCombat && foodLevel < 16
                && gameTime % EAT_INTERVAL == Math.abs(pet.getId() % EAT_INTERVAL)) {
            boolean fed = false;
            for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
                ItemStack s = data.getStdSlot(i);
                if (s.isEmpty()) continue;
                var foodData = s.get(DataComponents.FOOD);
                if (foodData == null) continue;
                int nutrition = foodData.nutrition();
                float satMod  = foodData.saturation();
                data.setFoodLevel(Math.min(20, foodLevel + nutrition));
                data.setSaturation(Math.min(20, saturation + nutrition * satMod * 2.0f));
                data.setStdSlot(i, s.getCount() > 1 ? s.copyWithCount(s.getCount() - 1) : ItemStack.EMPTY);
                fed = true;
                break;
            }
            if (!fed && data.getFoodLevel() <= 6 && isCaptionDue(pet, gameTime)) {
                sendHungerCaption(pet, data, gameTime);
            }
        }

        // ── Healing potion auto-use ───────────────────────────────────────

        if (inCombat
                && pet.getHealth() < pet.getMaxHealth() * 0.5f
                && data.getTotalHealingCount() > 0) {

            long lastHeal = pet.getPersistentData().getLongOr("WRHealCooldown", 0L);
            if (gameTime - lastHeal >= HEAL_COOLDOWN_TICKS) {
                int amplifier = data.useHealingPotion(); // consumes 1; -1 if empty
                if (amplifier >= 0) {
                    pet.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, amplifier));
                    pet.getPersistentData().putLong("WRHealCooldown", gameTime);
                }
            }
        }

        // ── Drain XP buffer to owner ──────────────────────────────────────

        int xp = data.drainXp();
        if (xp > 0) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
            if (owner != null) {
                owner.giveExperiencePoints(xp);
            } else {
                data.addXp(xp); // owner offline — keep buffered
            }
        }

        // ── Feed other hungry pets from standard storage ──────────────────

        if (gameTime % FEED_CHECK_INTERVAL == Math.abs(pet.getId() % FEED_CHECK_INTERVAL)) {
            tryFeedOtherPets(pet, data, level, gameTime);
        }
    }

    /**
     * Feeds the lowest-health tamed pet of the same owner using food from the feeder's
     * standard slots. Only runs when the feeder is calm or has no active target.
     */
    private static void tryFeedOtherPets(Mob feeder, WeddingRingData feederData,
                                          ServerLevel level, long gameTime) {
        // Only feed when calm or not actively fighting
        if (!feederData.isCalm() && feeder.getTarget() != null) return;

        UUID ownerUUID = feederData.getOwnerUUID();

        // Find the lowest-health tamed pet of the same owner within 32 blocks
        Mob target = null;
        float lowestHealthRatio = 1.0f;
        for (var entity : level.getEntitiesOfClass(Mob.class,
                feeder.getBoundingBox().inflate(32.0))) {
            if (!entity.isAlive() || entity == feeder) continue;
            if (!(entity instanceof OwnableEntity oe)) continue;
            var petOwner = oe.getOwner();
            if (petOwner == null || !ownerUUID.equals(petOwner.getUUID())) continue;
            float ratio = entity.getHealth() / entity.getMaxHealth();
            if (ratio < 1.0f && ratio < lowestHealthRatio) {
                lowestHealthRatio = ratio;
                target = entity;
            }
        }
        if (target == null) return;

        // Find the first food item in the feeder's standard slots
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            ItemStack stack = feederData.getStdSlot(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;

            // Directly heal the target (works for any pet, ring-bound or not)
            target.heal(food.nutrition() * 0.5f);

            // Consume one item from the feeder's slot
            feederData.setStdSlot(i, stack.getCount() > 1
                ? stack.copyWithCount(stack.getCount() - 1)
                : ItemStack.EMPTY);

            // Notify the owner
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUUID);
            if (owner != null) {
                WeddingRingCaptionPayload.send(owner,
                    feeder.getDisplayName().getString() + " fed "
                    + target.getDisplayName().getString()
                    + " a " + stack.getHoverName().getString());
            }
            return;
        }
    }

    private static boolean isCaptionDue(Mob pet, long gameTime) {
        long last = pet.getPersistentData().getLongOr("WRHungerCaption", 0L);
        return gameTime - last >= HUNGER_CAPTION_COOLDOWN;
    }

    private static void sendHungerCaption(Mob pet, WeddingRingData data, long gameTime) {
        UUID ownerUUID = data.getOwnerUUID();
        if (!(pet.level() instanceof ServerLevel sl)) return;
        ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner != null) {
            WeddingRingCaptionPayload.send(owner,
                pet.getDisplayName().getString() + " is hungry!");
            pet.getPersistentData().putLong("WRHungerCaption", gameTime);
        }
    }

    // Called when a pet with ring data dies — drops items and destroys ring
    public static void onPetDeath(Mob pet, WeddingRingData data, ServerLevel level) {
        UUID ownerUUID = data.getOwnerUUID();

        // Drop all ring items at pet's position
        dropIfNonEmpty(level, pet, data.getWeapon());
        dropIfNonEmpty(level, pet, data.getShield());
        if (data.hasArmor()) {
            dropIfNonEmpty(level, pet, data.getArmorHead());
            dropIfNonEmpty(level, pet, data.getArmorChest());
            dropIfNonEmpty(level, pet, data.getArmorLegs());
            dropIfNonEmpty(level, pet, data.getArmorFeet());
        }

        // Drop all stored healing potions
        for (ItemStack drop : data.drainHealingPotions()) {
            level.addFreshEntity(new ItemEntity(level, pet.getX(), pet.getY(), pet.getZ(), drop));
        }

        // Drop all standard storage slot contents
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            dropIfNonEmpty(level, pet, data.getStdSlot(i));
        }

        // Break the ring into 7 gold for the owner
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner != null) {
            ItemStack gold = new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 7);
            if (!owner.addItem(gold)) {
                level.addFreshEntity(new ItemEntity(level,
                    owner.getX(), owner.getY(), owner.getZ(), gold));
            }
            // Remove ring from owner's inventory — match by bound UUID so the right ring is consumed
            removeRingFromInventory(owner, pet.getUUID());
        }

        WeddingRingLlmHandler.onPetDeath(pet.getUUID());
        WeddingRingTargetFilter.clear(pet.getUUID());
        WeddingRingData.remove(pet);
        WeddingRingGoalManager.removeGoals(pet);
        WeddingRingAttributeManager.recalculate(pet);
        // Weapon was already dropped explicitly above; clear MAINHAND so vanilla doesn't drop it again
        pet.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static void dropIfNonEmpty(ServerLevel level, Mob pet, ItemStack stack) {
        if (!stack.isEmpty()) {
            level.addFreshEntity(new ItemEntity(level,
                pet.getX(), pet.getY(), pet.getZ(), stack));
        }
    }

    private static void removeRingFromInventory(ServerPlayer owner, UUID petUUID) {
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            ItemStack stack = owner.getInventory().getItem(i);
            if (!(stack.getItem() instanceof WeddingRingItem)) continue;
            UUID stored = WeddingRingItem.readUUID(WeddingRingItem.getOrCreate(stack));
            if (petUUID.equals(stored)) {
                owner.getInventory().setItem(i, ItemStack.EMPTY);
                return;
            }
        }
    }
}
