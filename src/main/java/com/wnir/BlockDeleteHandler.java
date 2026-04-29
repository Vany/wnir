package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.event.level.ChunkEvent;

public final class BlockDeleteHandler {

    private BlockDeleteHandler() {}

    public static void onChunkLoad(ChunkEvent.Load event) {
        boolean hasIds = !BlockDeleteConfig.BLOCKS_TO_DELETE.isEmpty();
        boolean hasPos = !BlockDeleteConfig.POSITIONS_TO_DELETE.isEmpty();
        if (!hasIds && !hasPos) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos chunkPos = chunk.getPos();

        // Position-based deletions: only act on positions whose chunk is this one
        if (hasPos) {
            for (BlockPos pos : BlockDeleteConfig.POSITIONS_TO_DELETE) {
                if (!new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4).equals(chunkPos)) continue;
                var state = level.getBlockState(pos);
                if (!state.isAir()) {
                    Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    WnirMod.LOGGER.warn("[BlockDelete] Deleted {} at {} (position entry)", id, pos);
                    BlockDeleteConfig.DELETED_POS_THIS_SESSION.add(pos);
                } else {
                    // Air already — position is stale, remove from config anyway
                    WnirMod.LOGGER.info("[BlockDelete] Position {} is already air, removing from config", pos);
                    BlockDeleteConfig.DELETED_POS_THIS_SESSION.add(pos);
                }
            }
        }

        // Block-type-based deletions: scan all sections
        if (hasIds) {
            LevelChunkSection[] sections = chunk.getSections();
            int minY = level.getMinY();
            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();

            for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                LevelChunkSection section = sections[sectionIdx];
                if (section == null || section.hasOnlyAir()) continue;

                int baseY = minY + sectionIdx * 16;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            var state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;
                            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                            if (BlockDeleteConfig.BLOCKS_TO_DELETE.contains(id)) {
                                BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                                WnirMod.LOGGER.warn("[BlockDelete] Deleted {} at {}", id, pos);
                                BlockDeleteConfig.DELETED_IDS_THIS_SESSION.add(id);
                            }
                        }
                    }
                }
            }
        }
    }
}
