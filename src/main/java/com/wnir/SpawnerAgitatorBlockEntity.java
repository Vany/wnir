package com.wnir;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SpawnerAgitatorBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Fixed profile for the fake player used to keep spawners active.
     * Fixed UUID so multiple rebind cycles reuse the same fake player slot.
     */
    private static final GameProfile AGITATOR_PROFILE =
        new GameProfile(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"), "[Agitator]");

    private static final Set<SpawnerAgitatorBlockEntity> BOUND_AGITATORS =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** BEs that need column recheck on the next server tick (deferred from onLoad). */
    private static final Set<SpawnerAgitatorBlockEntity> PENDING_REBIND =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final List<SpawnerBlockEntity> cachedSpawnerBEs = new ArrayList<>();
    private final List<BlockPos> cachedSpawnerPositions = new ArrayList<>();
    private int cachedStackSize = 1;
    /** Intangible server-side fake player added to ServerLevel.players() so spawners see a nearby player. */
    private AgitatorFakePlayer fakePlayer;

    /**
     * Fake player that mirrors real players' sleeping state so it never blocks
     * night-skip and never triggers premature night-skip.
     */
    private static class AgitatorFakePlayer extends FakePlayer {
        AgitatorFakePlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        /**
         * Returns true only when ALL real (non-fake, non-spectator) players are sleeping,
         * so this fake player never blocks night-skip and never causes premature night-skip.
         */
        @Override
        public boolean isSleeping() {
            if (level() instanceof ServerLevel sl) {
                boolean anyRealSleeping = false;
                boolean anyRealAwake = false;
                for (ServerPlayer p : sl.players()) {
                    if (p == this || p instanceof AgitatorFakePlayer || p.isSpectator()) continue;
                    if (p.isSleeping()) anyRealSleeping = true;
                    else anyRealAwake = true;
                }
                return anyRealSleeping && !anyRealAwake;
            }
            return false;
        }

        /**
         * SleepStatus.update() counts players via isSleepingLongEnough() (= isSleeping() &&
         * sleepCounter >= 100), not isSleeping(). sleepCounter is never incremented for a
         * fake player that never entered a real bed, so we must override this too.
         */
        @Override
        public boolean isSleepingLongEnough() { return isSleeping(); }

        @Override public int getSleepTimer() { return 100; }
    }

    public SpawnerAgitatorBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.SPAWNER_AGITATOR_BE.get(), pos, state);
    }

    void bindSpawner() {
        bindSpawner(null);
    }

    void bindSpawner(BlockPos exclude) {
        if (level == null || level.isClientSide()) return;
        BlockPos above = worldPosition.above();
        if (exclude != null && above.equals(exclude)) above = above.above();

        // Not the top of the agitator column — another agitator is directly above
        if (level.getBlockState(above).getBlock() instanceof SpawnerAgitatorBlock) {
            LOGGER.info("[Agitator] {} — not top of column, skip bind", worldPosition);
            cachedSpawnerBEs.clear();
            cachedSpawnerPositions.clear();
            return;
        }

        List<SpawnerBlockEntity> foundBEs = new ArrayList<>();
        List<BlockPos> foundPositions = new ArrayList<>();
        BlockPos check = above;
        while (true) {
            if (exclude != null && check.equals(exclude)) {
                check = check.above();
                continue;
            }
            if (!level.getBlockState(check).is(Blocks.SPAWNER)) {
                LOGGER.info("[Agitator] {} — scan stopped at {} (block={})",
                    worldPosition, check, level.getBlockState(check).getBlock());
                break;
            }
            if (!(level.getBlockEntity(check) instanceof SpawnerBlockEntity spawnerBE)) {
                LOGGER.warn("[Agitator] {} — SPAWNER at {} has no SpawnerBlockEntity!", worldPosition, check);
                break;
            }
            foundBEs.add(spawnerBE);
            foundPositions.add(check.immutable());
            check = check.above();
        }

        if (foundBEs.isEmpty()) {
            LOGGER.info("[Agitator] {} — no spawners found above, not binding", worldPosition);
            return;
        }

        cachedSpawnerBEs.clear();
        cachedSpawnerBEs.addAll(foundBEs);
        cachedSpawnerPositions.clear();
        cachedSpawnerPositions.addAll(foundPositions);

        ServerLevel serverLevel = (ServerLevel) level;

        // Create or reuse the fake player; position it at the first spawner so
        // BaseSpawner.isNearPlayer() finds it within the default requiredPlayerRange (16 blocks).
        if (fakePlayer == null) {
            fakePlayer = new AgitatorFakePlayer(serverLevel, AGITATOR_PROFILE);
        }
        BlockPos firstSpawner = foundPositions.get(0);
        fakePlayer.setPos(firstSpawner.getX() + 0.5, firstSpawner.getY() + 0.5, firstSpawner.getZ() + 0.5);

        List<ServerPlayer> playerList = serverLevel.players();
        if (!playerList.contains(fakePlayer)) {
            playerList.add(fakePlayer);
            LOGGER.info("[Agitator] {} — fake player added to level players", worldPosition);
        }

        ChunkLoaderBlock.forceChunk(serverLevel, worldPosition, true);
        BOUND_AGITATORS.add(this);
        LOGGER.info("[Agitator] bind complete at {} — stack={}, spawners={}",
            worldPosition, cachedStackSize, cachedSpawnerBEs.size());
    }

    void unbindSpawner() {
        if (level instanceof ServerLevel serverLevel) {
            if (fakePlayer != null) {
                serverLevel.players().remove(fakePlayer);
                LOGGER.info("[Agitator] {} — fake player removed from level players", worldPosition);
            }
            ChunkLoaderBlock.forceChunk(serverLevel, worldPosition, false);
        }
        fakePlayer = null;
        BOUND_AGITATORS.remove(this);
        cachedSpawnerBEs.clear();
        cachedSpawnerPositions.clear();
        LOGGER.info("[Agitator] unbind at {}", worldPosition);
    }

    /**
     * Called each server tick from onServerTick(). Only the topmost agitator acts.
     * Calls spawner.serverTick() {@code cachedStackSize} extra times — on top of the
     * one natural tick the game already fires — yielding stackSize+1 total ticks/game-tick.
     */
    void tickSpawners() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!ColumnHelper.isTopOfColumn(serverLevel, worldPosition, null, SpawnerAgitatorBlock.class)) return;
        for (int i = 0; i < cachedSpawnerBEs.size(); i++) {
            SpawnerBlockEntity spawnerBE = cachedSpawnerBEs.get(i);
            if (spawnerBE.isRemoved()) continue;
            BaseSpawner spawner = spawnerBE.getSpawner();
            BlockPos spawnerPos = cachedSpawnerPositions.get(i);
            for (int t = 0; t < cachedStackSize; t++) {
                spawner.serverTick(serverLevel, spawnerPos);
            }
        }
    }

    /**
     * Called on each random tick. Only the topmost agitator in the column acts.
     * Accelerates trial spawner restart: N agitators → x(N+1) cooldown speed on average.
     * Subtracts {@code stackSize × 1365} ticks (≈ average random tick interval at default
     * randomTickSpeed=3: 4096/3 ≈ 1365), preserving the same net speedup as a per-game-tick approach.
     */
    void tickTrialSpawners(ServerLevel level) {
        if (!ColumnHelper.isTopOfColumn(level, worldPosition, null, SpawnerAgitatorBlock.class)) return;
        if (!TrialSpawnerAccessor.isAvailable()) return;

        long advance = (long) cachedStackSize * 1365L;
        BlockPos check = worldPosition.above();
        while (level.getBlockEntity(check) instanceof TrialSpawnerBlockEntity tsbe) {
            if (tsbe.getTrialSpawner().getState() == TrialSpawnerState.COOLDOWN) {
                TrialSpawnerAccessor.advanceCooldown(tsbe.getTrialSpawner().getStateData(), advance);
            }
            check = check.above();
        }
    }

    boolean isBound() {
        return !cachedSpawnerBEs.isEmpty();
    }

    void recalcStackSize() {
        recalcStackSize(null);
    }

    void recalcStackSize(BlockPos exclude) {
        if (level == null) return;
        cachedStackSize = ColumnHelper.countBelow(
            level, worldPosition, exclude, SpawnerAgitatorBlock.class, false
        );
    }

    static void unbindAll() {
        PENDING_REBIND.clear();
        for (var be : Set.copyOf(BOUND_AGITATORS)) {
            be.unbindSpawner();
        }
    }

    /**
     * Returns true if {@code pos} is a spawner currently controlled by any bound agitator.
     * Used by the spawn event handler to mark mobs persistent.
     */
    static boolean isAgitatedSpawnerAt(BlockPos pos) {
        for (SpawnerAgitatorBlockEntity be : BOUND_AGITATORS) {
            if (be.cachedSpawnerPositions.contains(pos)) return true;
        }
        return false;
    }

    /**
     * Registered on NeoForge.EVENT_BUS in WnirMod.
     * Processes deferred column rechecks from onLoad(), then fires extra spawner ticks.
     */
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // Deferred rebinds: multiple agitators in the same column each queue a rebind,
        // but one notifyColumn walk covers the entire column — deduplicate by (x, z).
        if (!PENDING_REBIND.isEmpty()) {
            Set<SpawnerAgitatorBlockEntity> snapshot = Set.copyOf(PENDING_REBIND);
            PENDING_REBIND.removeAll(snapshot);
            LOGGER.info("[Agitator] tick: processing {} deferred rebinds", snapshot.size());
            Set<Long> processed = new java.util.HashSet<>();
            for (SpawnerAgitatorBlockEntity be : snapshot) {
                if (be.isRemoved() || be.level == null || be.level.isClientSide()) continue;
                long xz = (long) be.worldPosition.getX() << 32 | (be.worldPosition.getZ() & 0xFFFFFFFFL);
                if (!processed.add(xz)) continue;
                SpawnerAgitatorBlock.notifyColumn(be.level, be.worldPosition);
            }
        }

        // Extra ticks: stackSize additional serverTick() calls per game-tick per column
        for (SpawnerAgitatorBlockEntity be : BOUND_AGITATORS) {
            be.tickSpawners();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Defer column rebind to the next server tick so neighboring BEs are fully loaded.
        if (level != null && !level.isClientSide()) {
            LOGGER.info("[Agitator] {} — onLoad: queued for deferred rebind", worldPosition);
            PENDING_REBIND.add(this);
        }
    }
}
