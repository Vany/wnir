package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wireless Fuel — permanent fuel item that pulls FE from a linked block.
 * Shift+right-click a block with an energy capability to link.
 *
 * States (CustomModelData float[0]):
 *   0 = DARK_GRAY — no link
 *   1 = GRAY      — linked, idle
 *   2 = ORANGE    — just provided fuel (decays to GRAY after 250 ticks)
 *   3 = RED       — FE extraction failed; cleared only by re-linking
 *
 * Buffer: 0–64 000 FE stored in CUSTOM_DATA. Visible as durability bar.
 * Each burn cycle costs 4 000 FE → 201 ticks.
 */
public final class WirelessFuelItem extends Item {

    static final int STATE_NO_LINK = 0;
    static final int STATE_IDLE    = 1;
    static final int STATE_ACTIVE  = 2;
    static final int STATE_ERROR   = 3;

    private static final String KEY_DIM    = "dim";
    private static final String KEY_BX     = "bx";
    private static final String KEY_BY     = "by";
    private static final String KEY_BZ     = "bz";
    private static final String KEY_BUF    = "buf";
    private static final String KEY_STATE  = "state";
    private static final String KEY_ASINCE    = "asince";
    private static final String KEY_RETRYTIME = "retrytime";

    static final int  EXTRACT_RETRY_TICKS = 10;
    static final long MAX_BUFFER          = 64_000L;

    /**
     * Items currently sitting in a furnace fuel slot that need their buffer filled
     * before the next block-entity tick phase. Populated by onFurnaceFuelBurnTime
     * when a Transaction is already active (e.g. JumboFurnace) and drained by
     * onServerTickPre which runs before block-entity ticks.
     * Identity-keyed so that the exact ItemStack object in the furnace slot is tracked.
     */
    private static final java.util.Set<ItemStack> PENDING_FURNACE_FILLS =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    static final int  FUEL_PER_USE       = 4_000;
    static final int  BURN_TIME          = 201;
    static final int  ACTIVE_DECAY_TICKS = 250;

    public WirelessFuelItem(Properties props) {
        super(props);
    }

    // ── Linking ───────────────────────────────────────────────────────────────

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        BlockPos pos = ctx.getClickedPos();
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, ctx.getClickedFace());
        if (handler == null) {
            player.displayClientMessage(Component.literal("No energy source here"), true);
            return InteractionResult.FAIL;
        }

        ItemStack stack = ctx.getItemInHand();
        CompoundTag tag = getOrCreate(stack);
        tag.putString(KEY_DIM, level.dimension().identifier().toString());
        tag.putInt(KEY_BX, pos.getX());
        tag.putInt(KEY_BY, pos.getY());
        tag.putInt(KEY_BZ, pos.getZ());
        tag.putInt(KEY_STATE, STATE_IDLE);
        save(stack, tag);
        setModelState(stack, STATE_IDLE);

        player.displayClientMessage(
            Component.literal("Linked to " + pos.toShortString()), true);
        return InteractionResult.SUCCESS;
    }

    // ── Fuel logic ────────────────────────────────────────────────────────────

    @Override
    public int getBurnTime(ItemStack stack, @Nullable RecipeType<?> recipeType, FuelValues fuelValues) {
        CompoundTag tag = getOrCreate(stack);
        if (tag.getInt(KEY_STATE).orElse(STATE_NO_LINK) == STATE_NO_LINK) return 0;

        String dimStr = tag.getString(KEY_DIM).orElse("");
        if (dimStr.isEmpty()) return 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;  // client side — no side effects

        // Buffer is filled by onPlayerTick — no transaction here (getBurnTime may be
        // called inside another mod's open transaction, which would crash on openRoot).
        // Do NOT set STATE_ERROR here: an empty buffer just means onPlayerTick hasn't
        // filled it yet. Error state is owned exclusively by onPlayerTick.
        long buffer = tag.getLong(KEY_BUF).orElse(0L);

        if (buffer >= FUEL_PER_USE) {
            ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse(dimStr));
            ServerLevel linkedLevel = server.getLevel(dim);
            if (linkedLevel == null) return 0;

            buffer -= FUEL_PER_USE;
            tag.putLong(KEY_BUF, buffer);
            tag.putInt(KEY_STATE, STATE_ACTIVE);
            tag.putLong(KEY_ASINCE, linkedLevel.getGameTime());
            save(stack, tag);
            setModelState(stack, STATE_ACTIVE);
            return BURN_TIME;
        }
        return 0;
    }

    private static void markError(ItemStack stack, CompoundTag tag) {
        tag.putInt(KEY_STATE, STATE_ERROR);
        save(stack, tag);
        setModelState(stack, STATE_ERROR);
    }

    // ── Permanent device ──────────────────────────────────────────────────────

    @Override
    public ItemStack getCraftingRemainder(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    // ── Durability bar = buffer level ─────────────────────────────────────────

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getOrCreate(stack).getInt(KEY_STATE).orElse(STATE_NO_LINK) != STATE_NO_LINK;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        long buffer = getOrCreate(stack).getLong(KEY_BUF).orElse(0L);
        return (int) (buffer * 13L / MAX_BUFFER);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x4488FF;
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(
        ItemStack stack,
        Item.TooltipContext context,
        TooltipDisplay tooltipDisplay,
        Consumer<Component> consumer,
        TooltipFlag flag
    ) {
        CompoundTag tag = getOrCreate(stack);
        int state = tag.getInt(KEY_STATE).orElse(STATE_NO_LINK);
        long buffer = tag.getLong(KEY_BUF).orElse(0L);

        switch (state) {
            case STATE_NO_LINK -> consumer.accept(Component.literal("Not linked — shift+right-click an energy block"));
            case STATE_IDLE    -> consumer.accept(Component.literal("Buffer: " + buffer + " / " + MAX_BUFFER + " FE"));
            case STATE_ACTIVE  -> consumer.accept(Component.literal("Active — Buffer: " + buffer + " / " + MAX_BUFFER + " FE"));
            case STATE_ERROR   -> consumer.accept(Component.literal("Error: could not extract FE"));
        }
        if (state != STATE_NO_LINK) {
            String dimStr = tag.getString(KEY_DIM).orElse("");
            int bx = tag.getInt(KEY_BX).orElse(0);
            int by = tag.getInt(KEY_BY).orElse(0);
            int bz = tag.getInt(KEY_BZ).orElse(0);
            String dimName = dimStr.contains(":") ? dimStr.substring(dimStr.lastIndexOf(':') + 1) : dimStr;
            consumer.accept(Component.literal(
                "Linked: " + bx + ", " + by + ", " + bz + " [" + dimName + "]"));
        }
    }

    // ── Server tick: fair multi-source fill ───────────────────────────────────
    //
    // Algorithm (runs once per server tick, across ALL online players):
    //
    // Phase 1 — scan every player's inventory:
    //   • Apply ACTIVE → IDLE decay per item.
    //   • Collect items with buffer ≤ MAX_BUFFER/2 that are off retry-cooldown
    //     into a map keyed by (dim, pos) — their linked source block.
    //
    // Phase 2 — per source block, one transaction:
    //   • Extract sum(needs) from the source in a single Transaction.openRoot().
    //   • Distribute extracted FE proportionally to each item's share of totalNeeded.
    //   • Rounding remainder goes to the first item in the group.
    //   • If nothing extracted → stamp retry cooldown on all items in the group.
    //
    // Guarantees:
    //   • Exactly one transaction per live source block per tick — no double-dip.
    //   • Fair share: items with the same need get equal FE; a nearly-full item
    //     never steals from an empty one.
    //   • 10-tick back-off when the source is dry, so we don't hammer empty blocks.

    private record SourceKey(String dim, int bx, int by, int bz) {}

    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();

        // Phase 1: decay + collect fill candidates grouped by source
        java.util.Map<SourceKey, java.util.List<ItemStack>> fillQueue = new java.util.LinkedHashMap<>();

        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (!(stack.getItem() instanceof WirelessFuelItem)) continue;
                CompoundTag tag = getOrCreate(stack);
                int state = tag.getInt(KEY_STATE).orElse(STATE_NO_LINK);
                if (state == STATE_NO_LINK) continue;

                // ACTIVE → IDLE decay
                if (state == STATE_ACTIVE) {
                    long asince = tag.getLong(KEY_ASINCE).orElse(0L);
                    if (now - asince >= ACTIVE_DECAY_TICKS) {
                        state = STATE_IDLE;
                        tag.putInt(KEY_STATE, STATE_IDLE);
                        tag.remove(KEY_ASINCE);
                        save(stack, tag);
                        setModelState(stack, STATE_IDLE);
                    }
                }

                if (state == STATE_ERROR) continue;

                long buffer = tag.getLong(KEY_BUF).orElse(0L);
                if (buffer > MAX_BUFFER / 2) continue;

                long retryTime = tag.getLong(KEY_RETRYTIME).orElse(0L);
                if (now - retryTime < EXTRACT_RETRY_TICKS) continue;

                String dimStr = tag.getString(KEY_DIM).orElse("");
                if (dimStr.isEmpty()) continue;

                SourceKey key = new SourceKey(
                    dimStr,
                    tag.getInt(KEY_BX).orElse(0),
                    tag.getInt(KEY_BY).orElse(0),
                    tag.getInt(KEY_BZ).orElse(0)
                );
                fillQueue.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(stack);
            }
        }

        // Phase 2: one transaction per source, proportional distribution
        for (var entry : fillQueue.entrySet()) {
            SourceKey key = entry.getKey();
            java.util.List<ItemStack> stacks = entry.getValue();

            ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse(key.dim()));
            ServerLevel linkedLevel = server.getLevel(dim);
            if (linkedLevel == null) {
                for (ItemStack stack : stacks) markError(stack, getOrCreate(stack));
                continue;
            }

            BlockPos pos = new BlockPos(key.bx(), key.by(), key.bz());
            EnergyHandler handler = linkedLevel.getCapability(Capabilities.Energy.BLOCK, pos, null);
            if (handler == null) {
                for (ItemStack stack : stacks) {
                    CompoundTag tag = getOrCreate(stack);
                    tag.putLong(KEY_RETRYTIME, now);
                    save(stack, tag);
                }
                continue;
            }

            // Calculate each item's need and the total
            long[] needs = new long[stacks.size()];
            long totalNeeded = 0;
            for (int i = 0; i < stacks.size(); i++) {
                long buffer = getOrCreate(stacks.get(i)).getLong(KEY_BUF).orElse(0L);
                needs[i] = MAX_BUFFER - buffer;
                totalNeeded += needs[i];
            }

            try (var tx = Transaction.openRoot()) {
                int extracted = handler.extract((int) Math.min(totalNeeded, Integer.MAX_VALUE), tx);
                if (extracted > 0) {
                    long remaining = extracted;
                    for (int i = 0; i < stacks.size(); i++) {
                        long share = (long) extracted * needs[i] / totalNeeded;
                        share = Math.min(share, remaining);
                        CompoundTag tag = getOrCreate(stacks.get(i));
                        long buffer = tag.getLong(KEY_BUF).orElse(0L);
                        tag.putLong(KEY_BUF, buffer + share);
                        tag.remove(KEY_RETRYTIME);
                        save(stacks.get(i), tag);
                        remaining -= share;
                    }
                    // Rounding remainder to first item (bounded by its remaining need)
                    if (remaining > 0) {
                        CompoundTag tag = getOrCreate(stacks.get(0));
                        long buffer = tag.getLong(KEY_BUF).orElse(0L);
                        tag.putLong(KEY_BUF, Math.min(buffer + remaining, MAX_BUFFER));
                        save(stacks.get(0), tag);
                    }
                    tx.commit();
                } else {
                    tx.commit();
                    for (ItemStack stack : stacks) {
                        CompoundTag tag = getOrCreate(stack);
                        tag.putLong(KEY_RETRYTIME, now);
                        save(stack, tag);
                    }
                }
            }
        }
    }

    // ── Furnace slot fill (FurnaceFuelBurnTimeEvent) ──────────────────────────
    //
    // Fires when any furnace-type block queries burn time for an item in its fuel slot.
    // If the wireless fuel's buffer is empty at that moment, we fill it here so the
    // furnace can consume it immediately — no need to wait for the next server tick.
    //
    // JumboFurnace wraps getBurnTime inside a Transaction.openRoot(), so we would
    // crash if we try to openRoot() here.  Catch the IllegalStateException and instead
    // queue the item into PENDING_FURNACE_FILLS, which onServerTickPre drains BEFORE
    // the next block-entity tick phase — so JumboFurnace sees a full buffer next tick.

    public static void onFurnaceFuelBurnTime(net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent event) {
        // Skip if getBurnTime() already handled it (returned BURN_TIME, buffer was full)
        if (event.getBurnTime() > 0) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof WirelessFuelItem)) return;

        CompoundTag tag = getOrCreate(stack);
        int state = tag.getInt(KEY_STATE).orElse(STATE_NO_LINK);
        if (state == STATE_NO_LINK || state == STATE_ERROR) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        long now = server.overworld().getGameTime();
        long buffer = tag.getLong(KEY_BUF).orElse(0L);

        // Try to fill if below threshold
        if (buffer <= MAX_BUFFER / 2) {
            long retryTime = tag.getLong(KEY_RETRYTIME).orElse(0L);
            if (now - retryTime >= EXTRACT_RETRY_TICKS) {
                String dimStr = tag.getString(KEY_DIM).orElse("");
                if (!dimStr.isEmpty()) {
                    ResourceKey<Level> dim = ResourceKey.create(
                        Registries.DIMENSION, Identifier.parse(dimStr));
                    ServerLevel linkedLevel = server.getLevel(dim);
                    if (linkedLevel != null) {
                        BlockPos pos = new BlockPos(
                            tag.getInt(KEY_BX).orElse(0),
                            tag.getInt(KEY_BY).orElse(0),
                            tag.getInt(KEY_BZ).orElse(0)
                        );
                        EnergyHandler handler = linkedLevel.getCapability(Capabilities.Energy.BLOCK, pos, null);
                        if (handler != null) {
                            try {
                                try (var tx = Transaction.openRoot()) {
                                    long needed = MAX_BUFFER - buffer;
                                    int got = handler.extract((int) Math.min(needed, Integer.MAX_VALUE), tx);
                                    if (got > 0) {
                                        buffer += got;
                                        tag.putLong(KEY_BUF, buffer);
                                        tag.remove(KEY_RETRYTIME);
                                        save(stack, tag);
                                    } else {
                                        tag.putLong(KEY_RETRYTIME, now);
                                        save(stack, tag);
                                    }
                                    tx.commit();
                                }
                            } catch (IllegalStateException ignored) {
                                // Inside another mod's open transaction (e.g. JumboFurnace).
                                // Queue for pre-fill before next block-entity tick phase.
                                PENDING_FURNACE_FILLS.add(stack);
                            }
                        } else {
                            tag.putLong(KEY_RETRYTIME, now);
                            save(stack, tag);
                        }
                    }
                }
            }
        }

        // If buffer is now enough, consume and report burn time
        buffer = tag.getLong(KEY_BUF).orElse(0L);
        if (buffer >= FUEL_PER_USE) {
            buffer -= FUEL_PER_USE;
            tag.putLong(KEY_BUF, buffer);
            tag.putInt(KEY_STATE, STATE_ACTIVE);
            tag.putLong(KEY_ASINCE, now);
            save(stack, tag);
            setModelState(stack, STATE_ACTIVE);
            event.setBurnTime(BURN_TIME);
        }
    }

    // ── Pre-tick fill for furnace-slot items (JumboFurnace path) ─────────────
    //
    // Runs before block entities tick, so the buffer is full when JumboFurnace
    // opens its transaction and queries burn time.

    public static void onServerTickPre(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
        if (PENDING_FURNACE_FILLS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();

        // Group by source for fair distribution (same algorithm as onServerTick)
        java.util.Map<SourceKey, java.util.List<ItemStack>> fillQueue = new java.util.LinkedHashMap<>();
        java.util.Iterator<ItemStack> it = PENDING_FURNACE_FILLS.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            it.remove();
            CompoundTag tag = getOrCreate(stack);
            int state = tag.getInt(KEY_STATE).orElse(STATE_NO_LINK);
            if (state == STATE_NO_LINK || state == STATE_ERROR) continue;
            long buffer = tag.getLong(KEY_BUF).orElse(0L);
            if (buffer > MAX_BUFFER / 2) continue;
            long retryTime = tag.getLong(KEY_RETRYTIME).orElse(0L);
            if (now - retryTime < EXTRACT_RETRY_TICKS) continue;
            String dimStr = tag.getString(KEY_DIM).orElse("");
            if (dimStr.isEmpty()) continue;
            SourceKey key = new SourceKey(dimStr,
                tag.getInt(KEY_BX).orElse(0),
                tag.getInt(KEY_BY).orElse(0),
                tag.getInt(KEY_BZ).orElse(0));
            fillQueue.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(stack);
        }

        // Reuse same fair-extraction logic
        for (var entry : fillQueue.entrySet()) {
            SourceKey key = entry.getKey();
            java.util.List<ItemStack> stacks = entry.getValue();
            ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse(key.dim()));
            ServerLevel linkedLevel = server.getLevel(dim);
            if (linkedLevel == null) { for (ItemStack s : stacks) markError(s, getOrCreate(s)); continue; }
            BlockPos pos = new BlockPos(key.bx(), key.by(), key.bz());
            EnergyHandler handler = linkedLevel.getCapability(Capabilities.Energy.BLOCK, pos, null);
            if (handler == null) {
                for (ItemStack s : stacks) { CompoundTag t = getOrCreate(s); t.putLong(KEY_RETRYTIME, now); save(s, t); }
                continue;
            }
            long[] needs = new long[stacks.size()];
            long totalNeeded = 0;
            for (int i = 0; i < stacks.size(); i++) {
                long buf = getOrCreate(stacks.get(i)).getLong(KEY_BUF).orElse(0L);
                needs[i] = MAX_BUFFER - buf;
                totalNeeded += needs[i];
            }
            try (var tx = Transaction.openRoot()) {
                int extracted = handler.extract((int) Math.min(totalNeeded, Integer.MAX_VALUE), tx);
                if (extracted > 0) {
                    long remaining = extracted;
                    for (int i = 0; i < stacks.size(); i++) {
                        long share = Math.min((long) extracted * needs[i] / totalNeeded, remaining);
                        CompoundTag t = getOrCreate(stacks.get(i));
                        t.putLong(KEY_BUF, t.getLong(KEY_BUF).orElse(0L) + share);
                        t.remove(KEY_RETRYTIME);
                        save(stacks.get(i), t);
                        remaining -= share;
                    }
                    if (remaining > 0) {
                        CompoundTag t = getOrCreate(stacks.get(0));
                        t.putLong(KEY_BUF, Math.min(t.getLong(KEY_BUF).orElse(0L) + remaining, MAX_BUFFER));
                        save(stacks.get(0), t);
                    }
                    tx.commit();
                } else {
                    tx.commit();
                    for (ItemStack s : stacks) { CompoundTag t = getOrCreate(s); t.putLong(KEY_RETRYTIME, now); save(s, t); }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CompoundTag getOrCreate(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    private static void save(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void setModelState(ItemStack stack, int state) {
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) state), List.of(), List.of(), List.of()));
    }
}
