package com.wnir;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Trader container menu. No item slots — all item I/O goes through the inventory below.
 *
 * ContainerData (2 ints, server→client continuously):
 *   [0] fluid mB low 16 bits
 *   [1] fluid mB high 16 bits
 *
 * Trader list and trade state are pushed via TraderPayloads.TraderSyncPayload
 * (sent on menu open and after each rescan / checkbox toggle).
 */
public class TraderMenu extends AbstractContainerMenu {

    @Nullable
    private final TraderBlockEntity blockEntity;

    private final ContainerData data;

    // ── Client-side state (populated from TraderSyncPayload) ──────────────────

    private int syncedFluidMb  = 0;
    private int syncedStoredXp = 0;
    private List<TraderPayloads.TraderEntry> traders = new ArrayList<>();

    public static final int CONTAINER_SLOT_START = 0;
    public static final int CONTAINER_SLOT_END   = TraderBlockEntity.CONTAINER_SIZE; // exclusive

    /** Server-side constructor (called from BlockEntity.createMenu). */
    public TraderMenu(int id, ContainerData data, TraderBlockEntity be) {
        super(WnirRegistries.TRADER_MENU.get(), id);
        this.blockEntity = be;
        this.data        = data;
        addContainerSlots(be);
        addDataSlots(data);
    }

    /** Client-side constructor (called by MenuType factory). */
    public TraderMenu(int id, Inventory inv) {
        super(WnirRegistries.TRADER_MENU.get(), id);
        this.blockEntity = null;
        this.data        = new SimpleContainerData(2);
        addContainerSlots(new SimpleContainer(TraderBlockEntity.CONTAINER_SIZE));
        addDataSlots(data);
    }

    /** Adds the 9 trader container slots — 1×9 vertical column, second column after the cellulose tank. */
    private void addContainerSlots(net.minecraft.world.Container container) {
        // 1×9 column at x=30, y=9 (panel at x=29,y=8, w=20, h=164)
        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(container, i, 30, 9 + i * 18));
        }
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    /** Called by TraderSyncPayload.handle on the client main thread. */
    public void setTraderData(int fluidMb, int storedXp, List<TraderPayloads.TraderEntry> entries) {
        this.syncedFluidMb  = fluidMb;
        this.syncedStoredXp = storedXp;
        this.traders        = new ArrayList<>(entries);
    }

    // ── Data accessors ─────────────────────────────────────────────────────────

    /** Returns fluid mB — reads ContainerData (always fresh from server). */
    public int getFluidMb() {
        return ((data.get(1) & 0xFFFF) << 16) | (data.get(0) & 0xFFFF);
    }

    public int getSyncedStoredXp() { return syncedStoredXp; }

    public List<TraderPayloads.TraderEntry> getTraders() { return traders; }

    @Nullable
    public TraderBlockEntity getBlockEntity() { return blockEntity; }

    // ── AbstractContainerMenu ─────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Only container slots are in this menu — no player inventory, so no shift-click routing
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return true; // client-side
        if (blockEntity.isRemoved()) return false;
        var pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
