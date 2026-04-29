package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.*;

/**
 * Trader block entity.
 *
 * Scans a 3×3 chunk area for villager traders (rescan button in GUI).
 * Remembers traders by UUID — 5 missed scans → forgotten.
 * On redstone rising edge: for each checked trade, either restocks (100 mB cellulose)
 * or performs as many trades as possible, extracting payment from and depositing results
 * into the Container directly below.
 * Accumulated XP: 7 XP = 1 Bottle o' Enchanting, deposited into inventory below.
 *
 * Fluid: 16 000 mB Magic Cellulose. Filled via bucket or Capabilities.Fluid.BLOCK.
 * State preserved on mine via playerWillDestroy + copy_components loot table.
 */
public class TraderBlockEntity extends BlockEntity implements MenuProvider, Container {

    public static final int FORGET_THRESHOLD  = 5;
    public static final int RESTOCK_COST_MB   = 100;
    public static final int TANK_CAPACITY     = 16_000;
    public static final int MAX_TRADERS       = 16;
    /** 32 trades per villager — fits in one int bitmask. */
    public static final int MAX_TRADES        = 32;
    public static final int CONTAINER_SIZE    = 9;

    // ── State ─────────────────────────────────────────────────────────────────

    final NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
    final List<TrackedTrader> traders = new ArrayList<>();
    int storedXp   = 0;
    boolean wasPowered = false;

    /**
     * Single-slot fluid handler (cellulose only, insert from outside, extract by tick).
     */
    final FluidStacksResourceHandler fluidHandler = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        public boolean isValid(int slot, FluidResource resource) {
            return !resource.isEmpty()
                && resource.getFluid() == WnirRegistries.MAGIC_CELLULOSE_STILL.get();
        }
        @Override
        protected void onContentsChanged(int slot, FluidStack previous) {
            TraderBlockEntity.this.setChanged();
        }
    };

    /** ContainerData — 2 ints for fluid mB (fits 16 000 in one 16-bit field). */
    final ContainerData syncData = new ContainerData() {
        @Override
        public int get(int i) {
            int mb = getFluidMb();
            return switch (i) {
                case 0 -> mb & 0xFFFF;
                case 1 -> (mb >> 16) & 0xFFFF;
                default -> 0;
            };
        }
        @Override public void set(int i, int v) {}
        @Override public int getCount() { return 2; }
    };

    // ── Construction ──────────────────────────────────────────────────────────

    public TraderBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.TRADER_BE.get(), pos, state);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fluidHandler.deserialize(input.childOrEmpty("Fluid"));
        storedXp   = input.getIntOr("StoredXp", 0);
        wasPowered = input.getIntOr("WasPowered", 0) != 0;
        ContainerHelper.loadAllItems(input, items);
        loadTraders();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        fluidHandler.serialize(output.child("Fluid"));
        output.putInt("StoredXp", storedXp);
        output.putInt("WasPowered", wasPowered ? 1 : 0);
        ContainerHelper.saveAllItems(output, items);
        saveTraders();
    }

    // ── Container ─────────────────────────────────────────────────────────────

    @Override public int getContainerSize()              { return CONTAINER_SIZE; }
    @Override public boolean isEmpty()                   { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot)         { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return ContainerHelper.removeItem(items, slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot)     { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack s) { items.set(slot, s); if (s.getCount() > getMaxStackSize()) s.setCount(getMaxStackSize()); setChanged(); }
    @Override public boolean stillValid(Player player)   { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent()                 { items.clear(); setChanged(); }

    /**
     * Persists trader list into getPersistentData() (CompoundTag).
     * Called whenever the list changes and from saveAdditional.
     */
    void saveTraders() {
        CompoundTag data = getPersistentData();
        // Clear stale entries
        int oldCount = data.getInt("TraderCount").orElse(0);
        for (int i = 0; i < oldCount; i++) data.remove("Trader_" + i);

        data.putInt("TraderCount", traders.size());
        for (int i = 0; i < traders.size(); i++) {
            CompoundTag t = new CompoundTag();
            TrackedTrader td = traders.get(i);
            t.putLong("UUID_MSB", td.uuid.getMostSignificantBits());
            t.putLong("UUID_LSB", td.uuid.getLeastSignificantBits());
            t.putInt("Miss", td.missCount);
            t.putInt("Checks", td.checkedBits);
            t.putString("Name", td.cachedName);
            t.putInt("TradeCount", MAX_TRADES);
            for (int j = 0; j < MAX_TRADES; j++) {
                if (td.tradesDone[j]   != 0) t.putInt("Done"   + j, td.tradesDone[j]);
                if (td.tradesFailed[j] != 0) t.putInt("Failed" + j, td.tradesFailed[j]);
            }
            data.put("Trader_" + i, t);
        }
    }

    void loadTraders() {
        CompoundTag data = getPersistentData();
        traders.clear();
        int count = data.getInt("TraderCount").orElse(0);
        for (int i = 0; i < count; i++) {
            if (!data.contains("Trader_" + i)) continue;
            CompoundTag t;
            var opt = data.getCompound("Trader_" + i);
            t = opt.orElse(new CompoundTag());
            long msb  = t.getLong("UUID_MSB").orElse(0L);
            long lsb  = t.getLong("UUID_LSB").orElse(0L);
            if (msb == 0 && lsb == 0) continue; // corrupted entry
            UUID uuid  = new UUID(msb, lsb);
            int miss   = t.getInt("Miss").orElse(0);
            int checks = t.getInt("Checks").orElse(0);
            String name = t.getString("Name").orElse("Villager");
            int[] done   = new int[MAX_TRADES];
            int[] failed = new int[MAX_TRADES];
            for (int j = 0; j < MAX_TRADES; j++) {
                done[j]   = t.getInt("Done"   + j).orElse(0);
                failed[j] = t.getInt("Failed" + j).orElse(0);
            }
            traders.add(new TrackedTrader(uuid, miss, checks, done, failed, name));
        }
    }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.wnir.trader");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new TraderMenu(id, syncData, this);
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    public int getFluidMb() {
        return (int) fluidHandler.getAmountAsLong(0);
    }

    /** Returns true on a redstone rising edge; updates wasPowered. */
    public boolean checkAndUpdatePower(boolean nowPowered) {
        boolean rising = nowPowered && !wasPowered;
        wasPowered = nowPowered;
        if (rising) setChanged();
        return rising;
    }

    /** Spawns stored XP as orbs at the player's position and resets storedXp. */
    public void giveXpToPlayer(ServerPlayer sp) {
        if (storedXp <= 0) return;
        ExperienceOrb.award((ServerLevel) sp.level(), sp.position(), storedXp);
        storedXp = 0;
        setChanged();
    }

    /** Called from TraderBlock.useWithoutItem; sends initial sync after menu opens. */
    public void afterMenuOpened(ServerPlayer sp, int menuId) {
        PacketDistributor.sendToPlayer(sp, buildSyncPacket((ServerLevel) sp.level(), menuId));
    }

    public TraderPayloads.TraderSyncPayload buildSyncPacket(ServerLevel level, int menuId) {
        List<TraderPayloads.TraderEntry> entries = new ArrayList<>();
        for (TrackedTrader td : traders) {
            List<Integer> done   = new ArrayList<>(MAX_TRADES);
            List<Integer> failed = new ArrayList<>(MAX_TRADES);
            for (int j = 0; j < MAX_TRADES; j++) { done.add(td.tradesDone[j]); failed.add(td.tradesFailed[j]); }

            // Collect live offer data — empty list if villager not loaded
            List<TraderPayloads.SyncedOffer> offers = new ArrayList<>();
            var entity = level.getEntity(td.uuid);
            if (entity instanceof Villager villager && villager.isAlive()) {
                var villagerOffers = villager.getOffers();
                if (villagerOffers != null) {
                    for (int j = 0; j < villagerOffers.size() && j < MAX_TRADES; j++) {
                        var o = villagerOffers.get(j);
                        offers.add(new TraderPayloads.SyncedOffer(
                            o.getCostA().copy(), o.getCostB().copy(),
                            o.getResult().copy(), o.isOutOfStock()));
                    }
                }
            }

            entries.add(new TraderPayloads.TraderEntry(
                td.uuid, td.cachedName, td.missCount, td.checkedBits, done, failed, offers));
        }
        return new TraderPayloads.TraderSyncPayload(menuId, getFluidMb(), storedXp, entries);
    }

    /** Toggle checkbox; called from server-side packet handler. */
    public void toggleTrade(int traderIdx, int tradeIdx) {
        if (traderIdx < 0 || traderIdx >= traders.size()) return;
        if (tradeIdx < 0 || tradeIdx >= MAX_TRADES) return;
        traders.get(traderIdx).toggleChecked(tradeIdx);
        saveTraders();
        setChanged();
    }

    // ── Rescan ────────────────────────────────────────────────────────────────

    /**
     * Scans the 3×3 chunk grid (full height column) for alive villagers with trades.
     * Increments miss counters for absent traders; forgets at threshold.
     * Adds newly discovered traders up to MAX_TRADERS.
     */
    public void performRescan(ServerLevel level) {
        int cx = worldPosition.getX() >> 4;
        int cz = worldPosition.getZ() >> 4;
        int x0 = (cx - 1) << 4, z0 = (cz - 1) << 4;
        int x1 = (cx + 2) << 4, z1 = (cz + 2) << 4;
        AABB box = new AABB(x0, level.getMinY(), z0, x1, level.getMaxY(), z1);

        List<Villager> found = level.getEntitiesOfClass(Villager.class, box,
            v -> v.isAlive() && v.getOffers() != null && !v.getOffers().isEmpty());

        Set<UUID> foundUUIDs = new HashSet<>();
        for (Villager v : found) foundUUIDs.add(v.getUUID());

        // Update existing traders: reset miss counter if found, increment otherwise
        traders.removeIf(td -> {
            if (foundUUIDs.contains(td.uuid)) {
                td.missCount = 0;
                return false;
            } else {
                td.missCount++;
                return td.missCount >= FORGET_THRESHOLD;
            }
        });

        // Add newly discovered traders (not yet tracked)
        for (Villager v : found) {
            boolean known = traders.stream().anyMatch(t -> t.uuid.equals(v.getUUID()));
            if (!known && traders.size() < MAX_TRADERS) {
                String name = v.getDisplayName().getString();
                traders.add(new TrackedTrader(
                    v.getUUID(), 0, 0, new int[MAX_TRADES], new int[MAX_TRADES], name));
            }
        }

        saveTraders();
        setChanged();
    }

    // ── Trade cycle ───────────────────────────────────────────────────────────

    /**
     * Attempts to perform all checked trades for all remembered traders.
     * Called on redstone rising edge (see TraderBlock.neighborChanged).
     * Payment extracted from / results deposited into Container directly below.
     */
    public void performTradeCycle(ServerLevel level) {
        WnirMod.LOGGER.info("[Trader] cycle start: {} traders tracked", traders.size());

        // Inventory below is only needed for actual trades, not for restock.
        // Supports both vanilla Container and NeoForge Capabilities.Item.BLOCK.
        InvAccess below = getInventoryBelow(level);
        if (below == null) {
            WnirMod.LOGGER.info("[Trader] no inventory below — trade path disabled, restock still runs");
        }

        boolean changed = false;

        for (TrackedTrader td : traders) {
            if (td.missCount >= FORGET_THRESHOLD) continue;

            var entity = level.getEntity(td.uuid);
            if (!(entity instanceof Villager villager) || !villager.isAlive()) {
                WnirMod.LOGGER.info("[Trader] villager {} not loaded or dead", td.uuid);
                continue;
            }

            var offers = villager.getOffers();
            if (offers == null || offers.isEmpty()) {
                WnirMod.LOGGER.info("[Trader] villager {} has no offers", td.uuid);
                continue;
            }

            for (int i = 0; i < offers.size() && i < MAX_TRADES; i++) {
                if (!td.isChecked(i)) continue;
                MerchantOffer offer = offers.get(i);

                if (offer.isOutOfStock()) {
                    if (getFluidMb() >= RESTOCK_COST_MB) {
                        WnirMod.LOGGER.info("[Trader] restocking villager {} (offer {}), fluid={}mB",
                            td.uuid, i, getFluidMb());
                        drainFluid(RESTOCK_COST_MB);
                        for (MerchantOffer o : offers) o.resetUses();
                        changed = true;
                    } else {
                        WnirMod.LOGGER.info("[Trader] offer {} out of stock but fluid only {}mB < {}mB",
                            i, getFluidMb(), RESTOCK_COST_MB);
                    }
                } else {
                    if (below == null) {
                        WnirMod.LOGGER.info("[Trader] offer {} in stock but no inventory below — skip trade", i);
                        continue;
                    }
                    int reps = executeTrades(offer, i, td, below);
                    if (reps > 0) changed = true;
                }
            }
        }

        if (changed) setChanged();
    }

    private InvAccess getInventoryBelow(ServerLevel level) {
        BlockPos below = worldPosition.below();
        var be = level.getBlockEntity(below);
        if (be instanceof Container c) {
            WnirMod.LOGGER.info("[Trader] inventory below: vanilla Container ({})", be.getClass().getSimpleName());
            return new ContainerAccess(c);
        }
        var cap = level.getCapability(Capabilities.Item.BLOCK, below, Direction.UP);
        if (cap != null) {
            WnirMod.LOGGER.info("[Trader] inventory below: NeoForge cap ({})",
                be != null ? be.getClass().getSimpleName() : "no BE");
            return new CapAccess(cap);
        }
        return null;
    }

    private int executeTrades(MerchantOffer offer, int offerIdx, TrackedTrader td, InvAccess inv) {
        int reps = 0;
        while (!offer.isOutOfStock()) {
            ItemStack costA  = offer.getCostA();
            ItemStack costB  = offer.getCostB();
            ItemStack result = offer.getResult().copy();

            if (!inv.hasEnough(costA)) {
                WnirMod.LOGGER.info("[Trader] FAIL costA={} ×{} | inv={}",
                    costA.getItem(), costA.getCount(), inv.summary());
                td.tradesFailed[offerIdx]++; break;
            }
            if (!costB.isEmpty() && !inv.hasEnough(costB)) {
                WnirMod.LOGGER.info("[Trader] FAIL costB={} ×{} | inv={}",
                    costB.getItem(), costB.getCount(), inv.summary());
                td.tradesFailed[offerIdx]++; break;
            }
            if (!inv.canFit(result)) break;

            inv.extract(costA);
            if (!costB.isEmpty()) inv.extract(costB);
            inv.insert(result);

            if (offer.shouldRewardExp()) storedXp += offer.getXp();
            offer.increaseUses();
            td.tradesDone[offerIdx]++;
            reps++;
        }
        return reps;
    }

    private void drainFluid(int mb) {
        var res = FluidResource.of(WnirRegistries.MAGIC_CELLULOSE_STILL.get());
        try (var tx = Transaction.openRoot()) {
            fluidHandler.extract(0, res, mb, tx);
            tx.commit();
        }
    }

    // ── InvAccess — abstracts vanilla Container and NeoForge capability ──────────

    private interface InvAccess {
        boolean hasEnough(ItemStack target);
        boolean canFit(ItemStack stack);
        void extract(ItemStack target);
        void insert(ItemStack stack);
        String summary();
    }

    /** Wraps a vanilla Container (chest, barrel, hopper, …). */
    private static class ContainerAccess implements InvAccess {
        private final Container inv;
        ContainerAccess(Container inv) { this.inv = inv; }

        @Override public boolean hasEnough(ItemStack target) {
            if (target.isEmpty()) return true;
            int needed = target.getCount();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItem(s, target)) needed -= s.getCount();
                if (needed <= 0) return true;
            }
            return false;
        }

        @Override public boolean canFit(ItemStack stack) {
            int space = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) space += stack.getMaxStackSize();
                else if (ItemStack.isSameItem(s, stack)) space += s.getMaxStackSize() - s.getCount();
                if (space >= stack.getCount()) return true;
            }
            return false;
        }

        @Override public void extract(ItemStack target) {
            int needed = target.getCount();
            for (int i = 0; i < inv.getContainerSize() && needed > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItem(s, target)) {
                    int take = Math.min(s.getCount(), needed);
                    inv.removeItem(i, take);
                    needed -= take;
                }
            }
        }

        @Override public void insert(ItemStack stack) {
            int remaining = stack.getCount();
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItem(s, stack)) {
                    int add = Math.min(s.getMaxStackSize() - s.getCount(), remaining);
                    if (add > 0) { inv.setItem(i, s.copyWithCount(s.getCount() + add)); remaining -= add; }
                }
            }
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                if (inv.getItem(i).isEmpty()) {
                    int place = Math.min(remaining, stack.getMaxStackSize());
                    inv.setItem(i, stack.copyWithCount(place)); remaining -= place;
                }
            }
        }

        @Override public String summary() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) sb.append(s.getItem()).append("×").append(s.getCount()).append(", ");
            }
            if (sb.length() > 1) sb.setLength(sb.length() - 2);
            return sb.append("]").toString();
        }
    }

    /** Wraps a NeoForge Capabilities.Item.BLOCK handler (modded containers like Sophisticated Storage). */
    private static class CapAccess implements InvAccess {
        private final ResourceHandler<ItemResource> cap;
        CapAccess(ResourceHandler<ItemResource> cap) { this.cap = cap; }

        @Override public boolean hasEnough(ItemStack target) {
            if (target.isEmpty()) return true;
            // Read slots directly — no transaction needed for reads.
            // Match by item type only: getCostA() may carry price-adjustment components
            // not present on plain barrel items, making component-exact extract return 0.
            long available = 0;
            for (int i = 0; i < cap.size(); i++) {
                ItemResource res = cap.getResource(i);
                if (!res.isEmpty() && res.getItem() == target.getItem()) {
                    available += cap.getAmountAsLong(i);
                    if (available >= target.getCount()) return true;
                }
            }
            return false;
        }

        @Override public boolean canFit(ItemStack stack) {
            try (var tx = Transaction.openRoot()) {
                long inserted = ResourceHandlerUtil.insertStacking(
                    cap, ItemResource.of(stack), stack.getCount(), tx);
                return inserted >= stack.getCount();
            }
        }

        @Override public void extract(ItemStack target) {
            // Extract slot-by-slot using each slot's actual resource so price components
            // on the target don't prevent extraction of plain matching items.
            int needed = target.getCount();
            try (var tx = Transaction.openRoot()) {
                for (int i = 0; i < cap.size() && needed > 0; i++) {
                    ItemResource res = cap.getResource(i);
                    if (!res.isEmpty() && res.getItem() == target.getItem()) {
                        needed -= cap.extract(i, res, needed, tx);
                    }
                }
                if (needed <= 0) tx.commit();
            }
        }

        @Override public void insert(ItemStack stack) {
            try (var tx = Transaction.openRoot()) {
                ResourceHandlerUtil.insertStacking(cap, ItemResource.of(stack), stack.getCount(), tx);
                tx.commit();
            }
        }

        @Override public String summary() { return "[cap-inventory]"; }
    }

    // ── TrackedTrader ─────────────────────────────────────────────────────────

    public static class TrackedTrader {
        public UUID   uuid;
        public int    missCount;
        public int    checkedBits;
        public int[]  tradesDone;
        public int[]  tradesFailed;
        public String cachedName;

        TrackedTrader(UUID uuid, int missCount, int checkedBits,
                      int[] tradesDone, int[] tradesFailed, String cachedName) {
            this.uuid        = uuid;
            this.missCount   = missCount;
            this.checkedBits = checkedBits;
            this.tradesDone  = tradesDone.length == MAX_TRADES ? tradesDone : Arrays.copyOf(tradesDone, MAX_TRADES);
            this.tradesFailed = tradesFailed.length == MAX_TRADES ? tradesFailed : Arrays.copyOf(tradesFailed, MAX_TRADES);
            this.cachedName  = cachedName;
        }

        public boolean isChecked(int idx) {
            return idx >= 0 && idx < MAX_TRADES && (checkedBits & (1 << idx)) != 0;
        }

        public void toggleChecked(int idx) {
            if (idx >= 0 && idx < MAX_TRADES) checkedBits ^= (1 << idx);
        }
    }
}
