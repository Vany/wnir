package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Seed Bundle — a green bundle that holds up to 9 seed types and can mass-seed farmland.
 *
 * Capacity: up to 9 distinct seed types; total weight ≤ 1.0 (= 64 seeds of maxStackSize 64).
 * Insertion: left-click a slot item in inventory to insert (uses BundleContents.Mutable).
 * Seeding: right-click on farmland/soul sand to flood-fill all connected farmland and plant seeds.
 *   BFS spreads through any adjacent farmland, seeded or not. Plants only where space is empty.
 *   Detection is capability-based: each seed's own useOn() determines if planting is valid.
 */
public class SeedBundleItem extends BundleItem {

    /** Maximum number of distinct item stacks the bundle can hold. */
    private static final int MAX_STACKS = 9;
    /** Maximum blocks searched by the flood-fill. */
    private static final int MAX_FLOOD = 64;

    public SeedBundleItem(Item.Properties props) {
        super(props);
    }

    // ── Capacity override: 9 distinct stacks instead of weight=1 ─────────

    @Override
    public boolean overrideOtherStackedOnMe(
        ItemStack bundleStack, ItemStack incoming, Slot slot, ClickAction action, Player player, SlotAccess access
    ) {
        if (bundleStack.getCount() != 1) return false;
        BundleContents contents = bundleStack.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null) return false;

        if (action == ClickAction.PRIMARY && !incoming.isEmpty()) {
            if (!slot.allowModification(player)) return false;
            if (tryInsertInto(bundleStack, contents, incoming)) {
                playInsertSound(player);
            } else {
                playInsertFailSound(player);
            }
            broadcastChanges(player);
            return true;
        } else if (action == ClickAction.SECONDARY && incoming.isEmpty()) {
            if (!slot.allowModification(player)) return false;
            ItemStack removed = removeOne(bundleStack, contents);
            if (removed != null) {
                playRemoveOneSound(player);
                access.set(removed);
            }
            broadcastChanges(player);
            return true;
        }
        return false;
    }

    @Override
    public boolean overrideStackedOnOther(
        ItemStack bundleStack, Slot slot, ClickAction action, Player player
    ) {
        if (bundleStack.getCount() != 1) return false;
        BundleContents contents = bundleStack.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null) return false;

        ItemStack slotItem = slot.getItem();
        if (action == ClickAction.PRIMARY && !slotItem.isEmpty()) {
            if (!BundleContents.canItemBeInBundle(slotItem)) {
                playInsertFailSound(player);
                return true;
            }
            int canTake = slotItem.getCount();
            ItemStack taken = slot.safeTake(canTake, canTake, player);
            if (tryInsertInto(bundleStack, contents, taken)) {
                playInsertSound(player);
                if (!taken.isEmpty()) slot.safeInsert(taken); // return leftovers
            } else {
                slot.safeInsert(taken); // couldn't insert at all, return
                playInsertFailSound(player);
            }
            broadcastChanges(player);
            return true;
        } else if (action == ClickAction.SECONDARY && slotItem.isEmpty()) {
            // refresh contents reference after potential insert above
            contents = bundleStack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
            ItemStack removed = removeOne(bundleStack, contents);
            if (removed != null) {
                playRemoveOneSound(player);
                slot.safeInsert(removed);
            }
            broadcastChanges(player);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the item is a plantable seed (wheat/carrot/potato/beetroot/nether wart/
     * melon+pumpkin seeds/torchflower/pitcher pod).
     * Check is block-based — CropBlock covers all vanilla crops, StemBlock covers melon+pumpkin,
     * NetherWartBlock covers nether wart.
     */
    static boolean isPlantable(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        net.minecraft.world.level.block.Block b = blockItem.getBlock();
        return b instanceof CropBlock || b instanceof StemBlock || b instanceof NetherWartBlock;
    }

    /** Returns true if the block at pos is plantable soil (farmland or soul sand for nether wart). */
    static boolean isPlantableSoil(Level level, BlockPos pos) {
        net.minecraft.world.level.block.Block b = level.getBlockState(pos).getBlock();
        return b instanceof net.minecraft.world.level.block.FarmBlock
            || b == net.minecraft.world.level.block.Blocks.SOUL_SAND;
    }

    /**
     * Inserts as many items as possible from {@code incoming} into the bundle.
     * Up to MAX_STACKS distinct seed types; each stack capped at its maxStackSize.
     * Shrinks {@code incoming} by the inserted count.
     * Returns true if at least one item was inserted.
     *
     * Uses manual list manipulation rather than BundleContents.Mutable.tryInsert() because
     * tryInsert() only transfers 1 item per call in this build, making bulk loading impossible.
     */
    private static boolean tryInsertInto(ItemStack bundleStack, BundleContents contents, ItemStack incoming) {
        if (!BundleContents.canItemBeInBundle(incoming) || incoming.isEmpty()) return false;
        if (!isPlantable(incoming)) return false;

        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : contents.items()) items.add(s.copy());

        int inserted = 0;
        boolean merged = false;

        // Merge with existing matching stack up to maxStackSize
        for (int i = 0; i < items.size(); i++) {
            ItemStack existing = items.get(i);
            if (ItemStack.isSameItemSameComponents(existing, incoming)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                int add = Math.min(space, incoming.getCount());
                if (add > 0) {
                    items.set(i, existing.copyWithCount(existing.getCount() + add));
                    items.add(0, items.remove(i)); // move to front
                    incoming.shrink(add);
                    inserted += add;
                }
                merged = true;
                break;
            }
        }

        // New type: add a new stack if under the type limit
        if (!merged && items.size() < MAX_STACKS && !incoming.isEmpty()) {
            int add = Math.min(incoming.getMaxStackSize(), incoming.getCount());
            items.add(0, incoming.split(add));
            inserted += add;
        }

        if (inserted > 0) {
            bundleStack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
            return true;
        }
        return false;
    }

    /** Removes and returns one item from the front of the bundle, or null if empty. */
    private static ItemStack removeOne(ItemStack bundleStack, BundleContents contents) {
        if (contents.isEmpty()) return null;

        int idx = contents.getSelectedItem();
        if (idx < 0 || idx >= contents.size()) idx = 0;

        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : contents.items()) items.add(s.copy());

        ItemStack removed = items.remove(idx);
        bundleStack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
        return removed;
    }

    // ── Fullness bar: normalize to MAX_STACKS ─────────────────────────────

    @Override
    public boolean isBarVisible(ItemStack stack) {
        BundleContents c = stack.get(DataComponents.BUNDLE_CONTENTS);
        return c != null && !c.isEmpty();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        // Normalize to MAX_STACKS (not vanilla weight) since we allow weight > 1.0
        BundleContents c = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        return Math.min(1 + (int) Math.round(c.size() / (double) MAX_STACKS * 12), 13);
    }

    // ── Right-click on block: flood-fill and plant seeds ──────────────────

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        ItemStack bundleStack = ctx.getItemInHand();
        BundleContents contents = bundleStack.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null || contents.isEmpty()) return InteractionResult.PASS;

        BlockPos origin = ctx.getClickedPos();
        if (!isPlantableSoil(level, origin)) return InteractionResult.PASS;
        int planted = floodFillAndPlant(level, player, bundleStack, origin);

        if (planted > 0) {
            level.playSound(null, origin, net.minecraft.sounds.SoundEvents.CROP_PLANTED,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /**
     * BFS from {@code origin} across same-Y adjacent blocks.
     * For each position, tries to plant one seed from the bundle on top.
     * Detection is purely capability-based: each seed's own useOn() determines if planting is valid.
     * Returns the number of seeds planted.
     */
    private static int floodFillAndPlant(Level level, Player player, ItemStack bundleStack, BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);
        int planted = 0;
        int processed = 0;

        while (!queue.isEmpty() && processed < MAX_FLOOD) {
            BlockPos pos = queue.poll();
            processed++;

            // Try to plant a seed here
            if (tryPlantAt(level, player, bundleStack, pos)) {
                planted++;
                // Refresh contents check — stop if bundle now empty
                BundleContents c = bundleStack.get(DataComponents.BUNDLE_CONTENTS);
                if (c == null || c.isEmpty()) break;
            }

            // Expand BFS to horizontal neighbors (same Y, or ±1 for slopes).
            // Enqueue ANY farmland/soul sand, whether seeded or not — we plant only where there's room.
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos neighbor = pos.relative(dir).above(dy);
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        if (isPlantableSoil(level, neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return planted;
    }

    /**
     * Tries to plant any seed from the bundle on top of {@code surface}.
     * Uses each seed's own useOn() so any plantable item on any compatible surface works.
     * Returns true and updates the bundle if a seed was planted.
     */
    private static boolean tryPlantAt(Level level, Player player, ItemStack bundleStack, BlockPos surface) {
        if (!level.getBlockState(surface.above()).canBeReplaced()) return false;

        BundleContents contents = bundleStack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);

        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(surface.above()), Direction.UP, surface, false
        );

        for (ItemStack seed : contents.items()) {
            if (seed.isEmpty()) continue;

            // Pass a copy so the seed's useOn() can shrink it freely
            ItemStack testCopy = seed.copyWithCount(1);
            UseOnContext plantCtx = new UseOnContext(level, player, InteractionHand.MAIN_HAND, testCopy, hit);
            InteractionResult result = seed.getItem().useOn(plantCtx);

            if (result.consumesAction()) {
                // Planted successfully — remove one of this seed from the bundle
                removeSeedFromBundle(bundleStack, seed);
                return true;
            }
        }
        return false;
    }

    /** Removes exactly one item from the stack in the bundle that matches {@code seed}. */
    private static void removeSeedFromBundle(ItemStack bundleStack, ItemStack seed) {
        BundleContents contents = bundleStack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        List<ItemStack> newItems = new ArrayList<>();
        boolean removed = false;
        for (ItemStack s : contents.items()) {
            if (!removed && ItemStack.isSameItemSameComponents(s, seed)) {
                if (s.getCount() > 1) newItems.add(s.copyWithCount(s.getCount() - 1));
                // count==1: don't add (removes the stack entirely)
                removed = true;
            } else {
                newItems.add(s.copy());
            }
        }
        bundleStack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(newItems));
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext ctx, TooltipDisplay display,
        Consumer<Component> out, TooltipFlag flag
    ) {
        super.appendHoverText(stack, ctx, display, out, flag);
        WnirTooltips.add(out, flag,
            Component.translatable("tooltip.wnir.seed_bundle"),
            Component.translatable("tooltip.wnir.seed_bundle.detail"));
    }

    // ── Sound helpers (private in BundleItem — re-declared here) ──────────

    private static void playInsertSound(Player player) {
        player.playSound(net.minecraft.sounds.SoundEvents.BUNDLE_INSERT, 0.8f,
            0.8f + player.level().getRandom().nextFloat() * 0.4f);
    }

    private static void playInsertFailSound(Player player) {
        player.playSound(net.minecraft.sounds.SoundEvents.BUNDLE_INSERT_FAIL, 1.0f, 1.0f);
    }

    private static void playRemoveOneSound(Player player) {
        player.playSound(net.minecraft.sounds.SoundEvents.BUNDLE_REMOVE_ONE, 0.8f,
            0.8f + player.level().getRandom().nextFloat() * 0.4f);
    }

    private static void broadcastChanges(Player player) {
        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        if (menu != null) menu.slotsChanged(player.getInventory());
    }
}
