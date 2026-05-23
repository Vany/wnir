package com.wnir;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wedding Ring — right-click (non-shift) opens inventory of bound pet.
 * Shift+right-click on an owned pet binds (or re-binds) the ring.
 *
 * State stored in CUSTOM_DATA:
 *   "BoundUUID" (String)  — UUID of the bound entity
 *   "BoundName" (String)  — display name snapshot for tooltip
 *
 * Pet data stored in pet.getPersistentData()["WeddingRingData"] via WeddingRingData.
 */
public final class WeddingRingItem extends Item {

    private static final String KEY_UUID = "BoundUUID";
    private static final String KEY_NAME = "BoundName";

    public WeddingRingItem(Properties props) {
        super(props);
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                   LivingEntity entity, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(entity instanceof OwnableEntity ownable)) return InteractionResult.PASS;

        LivingEntity owner = ownable.getOwner();
        if (owner == null || !owner.getUUID().equals(player.getUUID())) return InteractionResult.PASS;
        if (!(entity instanceof Mob pet)) return InteractionResult.PASS;

        if (player.level().isClientSide()) return InteractionResult.SUCCESS;

        CompoundTag tag  = getOrCreate(stack);
        UUID boundUUID   = readUUID(tag);

        if (player.isShiftKeyDown()) {
            // ── Shift+RClick: bind only if not already bound to this pet ──
            WeddingRingData existing = WeddingRingData.get(pet);
            boolean alreadyBound = entity.getUUID().equals(boundUUID)
                || (existing != null && existing.getOwnerUUID().equals(player.getUUID()));
            if (alreadyBound) {
                player.sendSystemMessage(Component.translatable(
                    "item.wnir.wedding_ring.already_bound", pet.getDisplayName())
                    .withStyle(ChatFormatting.GRAY));
                return InteractionResult.SUCCESS;
            }
            bindRing(stack, tag, pet, player);
        } else {
            // ── RClick: open inventory ────────────────────────────────────
            // Accept by UUID match OR by ring-data ownership — the latter handles
            // pet-storage / teleport mods that re-create the entity with a new UUID.
            WeddingRingData data = WeddingRingData.get(pet);
            boolean ownerMatch = data != null && data.getOwnerUUID().equals(player.getUUID());
            boolean uuidMatch  = entity.getUUID().equals(boundUUID);

            if (!uuidMatch && !ownerMatch) {
                player.sendSystemMessage(Component.translatable(
                    "item.wnir.wedding_ring.hint_shift").withStyle(ChatFormatting.GRAY));
                return InteractionResult.SUCCESS;
            }
            if (data == null) {
                player.sendSystemMessage(Component.translatable(
                    "item.wnir.wedding_ring.no_slots", entity.getDisplayName()));
                return InteractionResult.SUCCESS;
            }
            // Silently re-anchor if UUID drifted (pet-storage / teleport re-spawn)
            if (!uuidMatch) {
                tag.putString(KEY_UUID, pet.getUUID().toString());
                tag.putString(KEY_NAME, pet.getDisplayName().getString());
                save(stack, tag);
            }
            ((ServerPlayer) player).openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WeddingRingMenu(id, inv, pet, data),
                entity.getDisplayName()
            ));
        }

        return InteractionResult.SUCCESS;
    }

    private static void bindRing(ItemStack stack, CompoundTag tag, Mob pet, Player player) {
        // If previously bound to another pet, remove ring data from that old pet
        UUID oldUUID = readUUID(tag);
        if (oldUUID != null && !(pet.level() instanceof ServerLevel)) return;
        if (pet.level() instanceof ServerLevel sl && oldUUID != null) {
            var oldEntity = sl.getEntity(oldUUID);
            if (oldEntity instanceof Mob oldMob) {
                WeddingRingData.remove(oldMob);
                WeddingRingGoalManager.removeGoals(oldMob);
            }
        }

        // Wolves keep their own BODY armor slot — no extra armor slots
        boolean addArmor = !(pet instanceof Wolf);

        WeddingRingData data = WeddingRingData.bind(pet, player.getUUID(), addArmor);
        WeddingRingGoalManager.removeGoals(pet);
        WeddingRingGoalManager.addGoals(pet);
        WeddingRingAttributeManager.recalculate(pet);

        // Update ring item tag
        tag.putString(KEY_UUID, pet.getUUID().toString());
        tag.putString(KEY_NAME, pet.getDisplayName().getString());
        save(stack, tag);

        player.sendSystemMessage(Component.translatable(
            "item.wnir.wedding_ring.bound", pet.getDisplayName()));
    }

    // ── Custom display name ───────────────────────────────────────────────────

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = getOrCreate(stack);
        UUID bound = readUUID(tag);
        if (bound != null) {
            String name = tag.getString(KEY_NAME).orElse("?");
            return Component.literal("Wedding Ring with " + name);
        }
        return Component.translatable("item.wnir.wedding_ring");
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display,
                                 Consumer<Component> lines, TooltipFlag flag) {
        CompoundTag tag = getOrCreate(stack);
        UUID bound = readUUID(tag);
        if (bound != null) {
            String name = tag.getString(KEY_NAME).orElse("?");
            lines.accept(Component.translatable("item.wnir.wedding_ring.bound_to", name)
                    .withStyle(ChatFormatting.GRAY));
            lines.accept(Component.literal("Right-click to open inventory")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.accept(Component.translatable("item.wnir.wedding_ring.unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.accept(Component.literal("Shift+right-click a pet to bind")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ── Death handler ─────────────────────────────────────────────────────────

    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        WeddingRingData data = WeddingRingData.get(mob);
        if (data == null) return;
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;

        WeddingRingTickHandler.onPetDeath(mob, data, serverLevel);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static UUID readUUID(CompoundTag tag) {
        String str = tag.getString(KEY_UUID).orElse("");
        if (str.isEmpty()) return null;
        try { return UUID.fromString(str); } catch (Exception e) { return null; }
    }

    static CompoundTag getOrCreate(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    private static void save(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * Updates the ring's stored UUID and name to match the given pet.
     * Called when a pet-storage mod re-spawns the pet with a new UUID.
     */
    static void reanchor(ItemStack stack, Mob pet) {
        CompoundTag tag = getOrCreate(stack);
        tag.putString(KEY_UUID, pet.getUUID().toString());
        tag.putString(KEY_NAME, pet.getDisplayName().getString());
        save(stack, tag);
    }
}
