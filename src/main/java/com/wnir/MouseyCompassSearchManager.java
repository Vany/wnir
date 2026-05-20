package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.*;

/**
 * Server-side BFS search manager for MouseyCompassItem.
 *
 * One SearchTask per player (keyed by UUID). Each tick, the nearest-to-player
 * chunk from the frontier is popped and scanned. Neighbors are enqueued after
 * each scan (floodfill expansion). Search stops when found or radius exceeded.
 *
 * Per-section optimisation: PalettedContainer.maybeHas() checks the palette
 * before scanning 4096 blocks, rejecting most sections in O(palette_size).
 */
public final class MouseyCompassSearchManager {

    /** Max chunk radius from the player's chunk at search start. */
    public static int MAX_CHUNK_RADIUS = 16;

    /**
     * Result returned from tick(). All fields valid regardless of outcome.
     * @param found        non-null if the block was found this tick
     * @param scannedChunk the chunk scanned this tick (for needle pointing); null if scan was skipped
     * @param newRadius    Chebyshev distance (rings) of the scanned chunk from origin;
     *                     -1 if the radius did not increase this tick
     */
    public record TickResult(BlockPos found, ChunkPos scannedChunk, int newRadius) {}

    private static final Map<UUID, SearchTask> tasks = new HashMap<>();

    private MouseyCompassSearchManager() {}

    public static void startSearch(UUID playerId, Identifier targetBlock, ChunkPos startChunk, int yMin, int yMax) {
        tasks.put(playerId, new SearchTask(targetBlock, startChunk, yMin, yMax));
    }

    public static void cancel(UUID playerId) {
        tasks.remove(playerId);
    }

    public static boolean isSearching(UUID playerId) {
        return tasks.containsKey(playerId);
    }

    /** Call on server stop to avoid stale state across world reloads. */
    public static void reset() {
        tasks.clear();
    }

    /**
     * Process one chunk for the given player.
     * Returns a TickResult; check isSearching() after to know if exhausted.
     */
    public static TickResult tick(UUID playerId, ServerLevel level, ChunkPos playerChunk) {
        SearchTask task = tasks.get(playerId);
        if (task == null) return new TickResult(null, null, -1);

        ChunkPos next = task.pollNearest(playerChunk);
        if (next == null) {
            tasks.remove(playerId);
            return new TickResult(null, null, -1);
        }

        // Stop if beyond max radius
        int dx = next.x() - task.originChunk.x();
        int dz = next.z() - task.originChunk.z();
        if (dx * dx + dz * dz > MAX_CHUNK_RADIUS * MAX_CHUNK_RADIUS) {
            tasks.remove(playerId);
            return new TickResult(null, null, -1);
        }

        // Chebyshev ring distance from origin
        int ring = Math.max(Math.abs(next.x() - task.originChunk.x()), Math.abs(next.z() - task.originChunk.z()));
        int newRadius = (ring > task.lastReportedRadius) ? ring : -1;
        if (newRadius != -1) task.lastReportedRadius = ring;

        // Skip unloaded chunks — still add neighbors so search can continue past them
        if (!level.hasChunk(next.x(), next.z())) {
            task.addNeighbors(next);
            return new TickResult(null, next, newRadius);
        }

        Block target = BuiltInRegistries.BLOCK.getValue(task.targetBlock);
        if (target == null || target == Blocks.AIR) {
            tasks.remove(playerId);
            return new TickResult(null, null, -1);
        }

        BlockPos found = scanChunk(level, next, target, task.yMin, task.yMax);
        if (found != null) {
            tasks.remove(playerId);
            return new TickResult(found, next, newRadius);
        }

        task.addNeighbors(next);
        return new TickResult(null, next, newRadius);
    }

    private static BlockPos scanChunk(ServerLevel level, ChunkPos cp, Block target, int yMin, int yMax) {
        LevelChunk chunk = level.getChunk(cp.x(), cp.z());
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = level.getMinY() / 16;
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;

            int baseY = (minSection + i) * 16;
            if (baseY + 15 < yMin || baseY > yMax) continue; // section entirely outside Y range

            if (!section.getStates().maybeHas(state -> state.is(target))) continue;

            int startY = Math.max(0, yMin - baseY);
            int endY   = Math.min(15, yMax - baseY);
            for (int x = 0; x < 16; x++) {
                for (int y = startY; y <= endY; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (level.getBlockState(new BlockPos(minX + x, baseY + y, minZ + z)).is(target)) {
                            return new BlockPos(minX + x, baseY + y, minZ + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    // ── Search task ───────────────────────────────────────────────────────────

    private static final class SearchTask {
        final Identifier targetBlock;
        final ChunkPos originChunk;
        final int yMin, yMax;
        final Set<ChunkPos> visited  = new HashSet<>();
        final Set<ChunkPos> frontier = new HashSet<>();
        int lastReportedRadius = 0;

        SearchTask(Identifier targetBlock, ChunkPos start, int yMin, int yMax) {
            this.targetBlock = targetBlock;
            this.originChunk = start;
            this.yMin = yMin;
            this.yMax = yMax;
            frontier.add(start);
        }

        /** Pop the frontier chunk nearest to the player's current chunk. */
        ChunkPos pollNearest(ChunkPos playerChunk) {
            ChunkPos best = null;
            int bestDist = Integer.MAX_VALUE;
            for (ChunkPos cp : frontier) {
                int dx = cp.x() - playerChunk.x();
                int dz = cp.z() - playerChunk.z();
                int dist = dx * dx + dz * dz;
                if (dist < bestDist) { bestDist = dist; best = cp; }
            }
            if (best == null) return null;
            frontier.remove(best);
            visited.add(best);
            return best;
        }

        void addNeighbors(ChunkPos cp) {
            for (ChunkPos n : new ChunkPos[]{
                new ChunkPos(cp.x() + 1, cp.z()),
                new ChunkPos(cp.x() - 1, cp.z()),
                new ChunkPos(cp.x(), cp.z() + 1),
                new ChunkPos(cp.x(), cp.z() - 1),
            }) {
                if (!visited.contains(n)) frontier.add(n);
            }
        }
    }
}
