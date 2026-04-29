package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Mousey Compass — right-click with a block in offhand to search for that block
 * in loaded chunks. BFS chunk-spiral, nearest-first. Needle spins while searching,
 * locks onto found block when done. Lock persists until new search begins.
 *
 * State stored in CUSTOM_DATA:
 *   "target"    (String)  — registry ID of block being searched for
 *   "searching" (boolean) — search in progress
 *   "fx/fy/fz"  (ints)    — found position (for tooltip display)
 *
 * LODESTONE_TRACKER drives the compass needle:
 *   empty target  → needle spins
 *   valid target  → needle points
 */
public final class MouseyCompassItem extends Item {

    private static final String KEY_TARGET    = "target";
    private static final String KEY_SEARCHING = "searching";
    private static final String KEY_FOUND_X   = "fx";
    private static final String KEY_FOUND_Y   = "fy";
    private static final String KEY_FOUND_Z   = "fz";

    public MouseyCompassItem(Properties props) {
        super(props);
    }

    // ── Right-click: begin search ────────────────────────────────────────────

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        ItemStack offhand = player.getOffhandItem();

        if (offhand.getItem() instanceof BlockItem blockItem) {
            // Block in offhand — search by registry ID
            if (!level.isClientSide()) {
                Identifier targetId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
                if (targetId == null) return InteractionResult.FAIL;
                beginSearch(player.getItemInHand(hand), targetId, player, (ServerLevel) level);
            }
            return InteractionResult.SUCCESS;
        }

        if (offhand.is(Items.NAME_TAG) && offhand.has(DataComponents.CUSTOM_NAME)) {
            // Anvil-renamed paper in offhand — search by block display name
            if (!level.isClientSide()) {
                String name = offhand.get(DataComponents.CUSTOM_NAME).getString();
                Block block = findBlockByName(name);
                if (block == null || block == Blocks.AIR) {
                    player.sendOverlayMessage(Component.literal("No block named '" + name + "'"));
                    return InteractionResult.FAIL;
                }
                Identifier targetId = BuiltInRegistries.BLOCK.getKey(block);
                if (targetId == null) return InteractionResult.FAIL;
                beginSearch(player.getItemInHand(hand), targetId, player, (ServerLevel) level);
            }
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide()) {
            player.sendOverlayMessage(Component.literal("Hold a block or a named paper in your offhand to search"));
        }
        return InteractionResult.FAIL;
    }

    // ── Server tick handler (registered in WnirMod) ──────────────────────────

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        ItemStack main = player.getMainHandItem();
        boolean holdingSearching = main.getItem() instanceof MouseyCompassItem && isSearching(main);

        if (!holdingSearching) {
            // Lost focus — cancel any running search and clear flag from inventory
            if (MouseyCompassSearchManager.isSearching(player.getUUID())) {
                cancelSearch(player);
            }
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        Identifier targetId = getTargetId(main);
        if (targetId == null) { cancelSearch(player); return; }

        BlockPos ppos = player.blockPosition();
        MouseyCompassSearchManager.TickResult result = MouseyCompassSearchManager.tick(
            player.getUUID(), level, new ChunkPos(ppos.getX() >> 4, ppos.getZ() >> 4)
        );

        // Point needle at current scanned chunk
        if (result.scannedChunk() != null) {
            BlockPos chunkCenter = new BlockPos(
                result.scannedChunk().getMiddleBlockX(),
                player.getBlockY(),
                result.scannedChunk().getMiddleBlockZ()
            );
            main.set(DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), chunkCenter)), false));
        }

        // Caption when radius ring increases
        if (result.newRadius() > 0) {
            player.sendOverlayMessage(Component.literal("Searching... " + result.newRadius() + " chunks"));
        }

        if (result.found() != null) {
            lock(main, result.found(), level.dimension());
            player.sendOverlayMessage(Component.literal(
                "Found at " + result.found().getX() + ", " + result.found().getY() + ", " + result.found().getZ()));
        } else if (!MouseyCompassSearchManager.isSearching(player.getUUID())) {
            // Search exhausted without finding
            clearSearching(main);
            player.sendOverlayMessage(Component.literal("Block not found"));
        }
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(
        ItemStack stack,
        Item.TooltipContext context,
        TooltipDisplay tooltipDisplay,
        Consumer<Component> consumer,
        TooltipFlag flag
    ) {
        WnirTooltips.add(consumer, flag,
            Component.translatable("tooltip.wnir.mousey_compass"),
            Component.translatable("tooltip.wnir.mousey_compass.detail"));

        Identifier targetId = getTargetId(stack);
        if (targetId == null) return;
        consumer.accept(Component.empty());

        Block block = BuiltInRegistries.BLOCK.getValue(targetId);
        consumer.accept(Component.literal("Target: ").append(block.getName()));

        if (isSearching(stack)) {
            consumer.accept(Component.literal("Searching..."));
        } else {
            BlockPos found = getFoundPos(stack);
            if (found != null) {
                consumer.accept(Component.literal(
                    "Found at " + found.getX() + ", " + found.getY() + ", " + found.getZ()
                ));
            }
        }
    }

    // ── Block name lookup ─────────────────────────────────────────────────────

    /** Find the first registered block whose display name matches (case-insensitive). */
    private static Block findBlockByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.getName().getString().toLowerCase(Locale.ROOT).equals(lower)) {
                return block;
            }
        }
        return null;
    }

    // ── State mutations ───────────────────────────────────────────────────────

    private static void beginSearch(ItemStack stack, Identifier targetId, Player player, ServerLevel level) {
        CompoundTag tag = getOrCreate(stack);
        tag.putString(KEY_TARGET, targetId.toString());
        tag.putBoolean(KEY_SEARCHING, true);
        tag.remove(KEY_FOUND_X); tag.remove(KEY_FOUND_Y); tag.remove(KEY_FOUND_Z);
        save(stack, tag);

        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.empty(), false));
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);

        BlockPos startPos = player.blockPosition();
        MouseyCompassSearchManager.startSearch(
            player.getUUID(), targetId, new ChunkPos(startPos.getX() >> 4, startPos.getZ() >> 4)
        );
        player.sendOverlayMessage(Component.literal("Searching for " + targetId.getPath() + "..."));
    }

    private static void lock(ItemStack stack, BlockPos pos, ResourceKey<Level> dimension) {
        CompoundTag tag = getOrCreate(stack);
        tag.putBoolean(KEY_SEARCHING, false);
        tag.putInt(KEY_FOUND_X, pos.getX());
        tag.putInt(KEY_FOUND_Y, pos.getY());
        tag.putInt(KEY_FOUND_Z, pos.getZ());
        save(stack, tag);

        stack.set(DataComponents.LODESTONE_TRACKER,
            new LodestoneTracker(Optional.of(GlobalPos.of(dimension, pos)), false));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    static void clearSearching(ItemStack stack) {
        CompoundTag tag = getOrCreate(stack);
        tag.putBoolean(KEY_SEARCHING, false);
        save(stack, tag);
        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.empty(), false));
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    private static void cancelSearch(Player player) {
        MouseyCompassSearchManager.cancel(player.getUUID());
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (s.getItem() instanceof MouseyCompassItem && isSearching(s)) {
                clearSearching(s);
            }
        }
    }

    // ── State readers ─────────────────────────────────────────────────────────

    static boolean isSearching(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getBoolean(KEY_SEARCHING).orElse(false);
    }

    private static Identifier getTargetId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String str = data.copyTag().getString(KEY_TARGET).orElse("");
        return str.isEmpty() ? null : Identifier.tryParse(str);
    }

    private static BlockPos getFoundPos(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(KEY_FOUND_X)) return null;
        return new BlockPos(
            tag.getInt(KEY_FOUND_X).orElse(0),
            tag.getInt(KEY_FOUND_Y).orElse(0),
            tag.getInt(KEY_FOUND_Z).orElse(0)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CompoundTag getOrCreate(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    private static void save(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
