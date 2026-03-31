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
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
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
    private static final String KEY_ASINCE = "asince";

    static final long MAX_BUFFER         = 64_000L;
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

        ResourceKey<Level> dim = ResourceKey.create(
            Registries.DIMENSION, Identifier.parse(dimStr));
        ServerLevel linkedLevel = server.getLevel(dim);
        if (linkedLevel == null) {
            markError(stack, tag);
            return 0;
        }

        BlockPos pos = new BlockPos(
            tag.getInt(KEY_BX).orElse(0),
            tag.getInt(KEY_BY).orElse(0),
            tag.getInt(KEY_BZ).orElse(0)
        );
        EnergyHandler handler = linkedLevel.getCapability(Capabilities.Energy.BLOCK, pos, null);

        // Fill buffer from source
        long buffer = tag.getLong(KEY_BUF).orElse(0L);
        long needed = MAX_BUFFER - buffer;
        if (needed > 0 && handler != null) {
            try (var tx = Transaction.openRoot()) {
                int extracted = handler.extract((int) Math.min(needed, Integer.MAX_VALUE), tx);
                buffer += extracted;
                tx.commit();
            }
        }

        if (buffer >= FUEL_PER_USE) {
            buffer -= FUEL_PER_USE;
            tag.putLong(KEY_BUF, buffer);
            tag.putInt(KEY_STATE, STATE_ACTIVE);
            tag.putLong(KEY_ASINCE, linkedLevel.getGameTime());
            save(stack, tag);
            setModelState(stack, STATE_ACTIVE);
            return BURN_TIME;
        } else {
            tag.putLong(KEY_BUF, buffer);
            markError(stack, tag);
            return 0;
        }
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

    // ── Tick: ACTIVE → IDLE decay ─────────────────────────────────────────────

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();

        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!(stack.getItem() instanceof WirelessFuelItem)) continue;
            CompoundTag tag = getOrCreate(stack);
            if (tag.getInt(KEY_STATE).orElse(STATE_NO_LINK) != STATE_ACTIVE) continue;
            long asince = tag.getLong(KEY_ASINCE).orElse(0L);
            if (now - asince >= ACTIVE_DECAY_TICKS) {
                tag.putInt(KEY_STATE, STATE_IDLE);
                tag.remove(KEY_ASINCE);
                save(stack, tag);
                setModelState(stack, STATE_IDLE);
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
