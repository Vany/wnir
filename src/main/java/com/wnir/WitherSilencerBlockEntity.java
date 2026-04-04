package com.wnir;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wither Silencer block entity.
 *
 * Maintains a static registry of block positions per dimension, used by the
 * client-side WitherSilencerHandler to cancel Wither spawn and death sounds
 * when the Wither is in the same chunk as any Wither Silencer block.
 */
public class WitherSilencerBlockEntity extends BlockEntity {

    /** All active Wither Silencer positions, keyed by dimension. Thread-safe. */
    static final Map<ResourceKey<Level>, Set<BlockPos>> registry = new ConcurrentHashMap<>();

    public WitherSilencerBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.WITHER_SILENCER_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null) {
            registry.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet())
                    .add(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Safe to touch the static registry here — no world access, just a Set.remove.
        if (level != null) {
            Set<BlockPos> set = registry.get(level.dimension());
            if (set != null) set.remove(worldPosition);
        }
    }

    /** Called on server stop to prevent stale entries across world reloads. */
    static void clearRegistry() {
        registry.clear();
    }
}
