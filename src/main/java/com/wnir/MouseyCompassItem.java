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
import net.minecraft.server.level.ServerPlayer;
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
import java.util.Set;
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

    static final String KEY_TARGET    = "target";
    static final String KEY_SEARCHING = "searching";
    static final String KEY_FOUND_X   = "fx";
    static final String KEY_FOUND_Y   = "fy";
    static final String KEY_FOUND_Z   = "fz";
    static final String KEY_Y_MODE    = "yMode";   // 0=all, 1=±32, 2=±16
    static final String KEY_RADIUS    = "radius";  // current search chunk radius (synced to client)

    public MouseyCompassItem(Properties props) {
        super(props);
    }

    // ── Right-click: begin search ────────────────────────────────────────────

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        ItemStack compass  = player.getItemInHand(hand);
        ItemStack offhand  = player.getOffhandItem();
        boolean hasBlock   = offhand.getItem() instanceof BlockItem;
        boolean hasNameTag = offhand.is(Items.NAME_TAG) && offhand.has(DataComponents.CUSTOM_NAME);

        // Right-click while searching with no applicable offhand item → cycle Y mode
        if (isSearching(compass) && !hasBlock && !hasNameTag) {
            if (!level.isClientSide()) {
                cycleYMode(compass, player, (ServerLevel) level);
            }
            return InteractionResult.SUCCESS;
        }

        if (hasBlock && offhand.getItem() instanceof BlockItem blockItem) {
            // Block in offhand — search by registry ID
            if (!level.isClientSide()) {
                Identifier targetId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
                if (targetId == null) return InteractionResult.FAIL;
                beginSearch(player.getItemInHand(hand), targetId, player, (ServerLevel) level);
            }
            return InteractionResult.SUCCESS;
        }

        if (hasNameTag) {
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
        if (!(main.getItem() instanceof MouseyCompassItem)) {
            if (MouseyCompassSearchManager.isSearching(player.getUUID())) cancelSearch(player);
            return;
        }

        if (!isSearching(main)) {
            if (MouseyCompassSearchManager.isSearching(player.getUUID())) cancelSearch(player);
            // Show live distance to found block every tick while held
            CompoundTag ft = getOrCreate(main);
            if (ft.contains(KEY_FOUND_X)) {
                int fx = ft.getInt(KEY_FOUND_X).orElse(0);
                int fy = ft.getInt(KEY_FOUND_Y).orElse(0);
                int fz = ft.getInt(KEY_FOUND_Z).orElse(0);
                double dx = fx - player.getX(), dy = fy - player.getY(), dz = fz - player.getZ();
                player.sendOverlayMessage(Component.literal((int) Math.sqrt(dx*dx + dy*dy + dz*dz) + " blocks"));
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

        // Update stored radius when ring expands
        if (result.newRadius() > 0) {
            CompoundTag tag = getOrCreate(main);
            tag.putInt(KEY_RADIUS, result.newRadius());
            save(main, tag);
        }

        // Overlay every tick — resets fade timer, keeping it visible while searching
        CompoundTag tag = getOrCreate(main);
        int radius = tag.getInt(KEY_RADIUS).orElse(0);
        int yMode  = tag.getInt(KEY_Y_MODE).orElse(0);
        String suffix = switch (yMode) { case 1 -> " [±32]"; case 2 -> " [±16]"; default -> ""; };
        player.sendOverlayMessage(Component.literal(
            radius > 0 ? radius * 16 + " blocks" + suffix : "Searching..."));

        if (result.found() != null) {
            lock(main, result.found(), level.dimension());
            BlockPos freePos = findFreeSpace(level, result.found());
            if (freePos != null && hasNbtWiper(player) && player instanceof ServerPlayer sp) {
                sp.teleportTo(level, freePos.getX() + 0.5, freePos.getY(), freePos.getZ() + 0.5,
                    Set.of(), player.getYRot(), player.getXRot(), false);
                consumeNbtWiper(player);
                player.sendOverlayMessage(Component.literal(
                    "Teleported to " + result.found().getX() + ", " + result.found().getY() + ", " + result.found().getZ()));
            } else {
                player.sendOverlayMessage(Component.literal(
                    "Found at " + result.found().getX() + ", " + result.found().getY() + ", " + result.found().getZ()));
            }
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
            int yMode = getOrCreate(stack).getInt(KEY_Y_MODE).orElse(0);
            String range = switch (yMode) {
                case 1 -> " [±32]";
                case 2 -> " [±16]";
                default -> "";
            };
            consumer.accept(Component.literal("Searching..." + range));
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
        tag.putInt(KEY_Y_MODE, 0);
        tag.putInt(KEY_RADIUS, 0);
        tag.remove(KEY_FOUND_X); tag.remove(KEY_FOUND_Y); tag.remove(KEY_FOUND_Z);
        save(stack, tag);

        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.empty(), false));
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);

        BlockPos startPos = player.blockPosition();
        MouseyCompassSearchManager.startSearch(
            player.getUUID(), targetId,
            new ChunkPos(startPos.getX() >> 4, startPos.getZ() >> 4),
            Integer.MIN_VALUE, Integer.MAX_VALUE
        );
        player.sendOverlayMessage(Component.literal("Searching for " + targetId.getPath() + "..."));
    }

    private static void cycleYMode(ItemStack stack, Player player, ServerLevel level) {
        CompoundTag tag = getOrCreate(stack);
        int mode = (tag.getInt(KEY_Y_MODE).orElse(0) + 1) % 3;
        tag.putInt(KEY_Y_MODE, mode);
        save(stack, tag);

        int playerY = player.getBlockY();
        int yMin, yMax;
        String msg;
        switch (mode) {
            case 1 -> { yMin = playerY - 32; yMax = playerY + 32; msg = "Height ±32 from Y=" + playerY; }
            case 2 -> { yMin = playerY - 16; yMax = playerY + 16; msg = "Height ±16 from Y=" + playerY; }
            default -> { yMin = Integer.MIN_VALUE; yMax = Integer.MAX_VALUE; msg = "All heights"; }
        }
        player.sendOverlayMessage(Component.literal(msg));

        Identifier targetId = getTargetId(stack);
        if (targetId == null) return;
        BlockPos startPos = player.blockPosition();
        MouseyCompassSearchManager.startSearch(
            player.getUUID(), targetId,
            new ChunkPos(startPos.getX() >> 4, startPos.getZ() >> 4),
            yMin, yMax
        );
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

    private static boolean hasNbtWiper(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(WnirRegistries.NBT_WIPER_LIQUID_ITEM.get())) return true;
        }
        return false;
    }

    /** Consumes one nbt_wiper_liquid from the player's inventory; returns true if found. */
    private static boolean consumeNbtWiper(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(WnirRegistries.NBT_WIPER_LIQUID_ITEM.get())) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /**
     * 3D Chebyshev shell search — returns the nearest air block to origin.
     * Accepts 1×1×1 or larger air spaces; both are valid teleport destinations.
     */
    private static BlockPos findFreeSpace(ServerLevel level, BlockPos origin) {
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) < r && Math.abs(dy) < r && Math.abs(dz) < r) continue;
                        BlockPos pos = origin.offset(dx, dy, dz);
                        int y = pos.getY();
                        if (y < level.getMinY() || y >= level.getMaxY()) continue;
                        if (!level.getBlockState(pos.below()).isAir()
                                && level.getBlockState(pos).isAir()
                                && level.getBlockState(pos.above()).isAir()) return pos;
                    }
                }
            }
        }
        return null;
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
