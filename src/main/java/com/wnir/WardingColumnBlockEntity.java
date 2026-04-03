package com.wnir;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Base class for all warding column block entities.
 *
 * The BOTTOM block of the column is the actor:
 *   - Runs the server tick (repel mobs if any RepellingPostBlock in column)
 *   - Owns totalRadius, hasRepel, hasInhibit for the column
 *
 * Each block type contributes to the column radius:
 *   - WardingPostBlock:         +6 (radius only)
 *   - RepellingPostBlock:       +4 (radius + mob push)
 *   - TeleporterInhibitorBlock: +4 (radius + teleport block)
 *   - HurtPostBlock:            +4 (radius + 1-heart armor-bypassing damage every 4 ticks)
 *   - LightingPostBlock:        +4 (radius + light level 15)
 */
public class WardingColumnBlockEntity extends BlockEntity {

    static final double VERTICAL_RANGE  = 2.5;
    static final double PUSH_STRENGTH   = 0.5;
    static final double PUSH_UPWARD     = 0.1;
    static final double CENTER_EPSILON  = 0.01;
    private static final int TICK_INTERVAL = 4;

    /** Contribution per WardingPostBlock in column. */
    static final double WARDING_RADIUS   = 6.0;
    /** Contribution per RepellingPostBlock in column. */
    static final double REPELLING_RADIUS = 4.0;
    /** Contribution per TeleporterInhibitorBlock in column. */
    static final double INHIBITOR_RADIUS = 4.0;
    /** Contribution per HurtPostBlock in column. */
    static final double HURT_RADIUS = 4.0;
    /** Damage per tick cycle: 2.0 = 1 heart, bypasses armor via magic damage source. */
    private static final float HURT_DAMAGE = 2.0f;
    /** Contribution per SilencerPostBlock in column. */
    static final double SILENCER_RADIUS = 4.0;

    // ── Column state (valid only when isBottomOfColumn) ──────────────────
    double totalRadius      = 6.0;  // default: single warding post = 6
    boolean hasRepel        = false;
    boolean hasInhibit      = false;
    int     hurtPostCount   = 0;
    int     silencerCount   = 0;
    boolean isBottomOfColumn = true;

    /**
     * UUID of the player who placed any block in this column.
     * Used as the damage attribution for hurt post kills (loot drops).
     * Null until a player places a block in the column.
     */
    UUID installerUUID = null;

    private int tickCounter;

    public WardingColumnBlockEntity(
        BlockEntityType<?> type, BlockPos pos, BlockState state
    ) {
        super(type, pos, state);
    }

    /** Factory for BlockEntityType registration (defers type lookup). */
    static WardingColumnBlockEntity create(BlockPos pos, BlockState state) {
        return new WardingColumnBlockEntity(WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY.get(), pos, state);
    }

    // ── Inhibitor registry (for O(n) teleport checks) ────────────────────

    /**
     * Bottom-of-column positions with hasInhibit=true, keyed by dimension.
     * Updated in recalcColumn() and setRemoved(). Read by WardingPostTeleportHandler.
     */
    static final Map<ResourceKey<Level>, Set<BlockPos>> inhibitorRegistry =
        new ConcurrentHashMap<>();

    private void updateRegistry() {
        if (level == null) return;
        ResourceKey<Level> key = level.dimension();

        // inhibitorRegistry: server-side only (used by server-side teleport handler)
        if (!level.isClientSide()) {
            Set<BlockPos> inhibSet = inhibitorRegistry.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            if (isBottomOfColumn && hasInhibit) {
                inhibSet.add(worldPosition.immutable());
            } else {
                inhibSet.remove(worldPosition);
            }
        }

        // silencerRegistry: both sides (used by client-side sound handler)
        Set<BlockPos> silSet = silencerRegistry.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (isBottomOfColumn && silencerCount > 0) {
            silSet.add(worldPosition.immutable());
        } else {
            silSet.remove(worldPosition);
        }
    }

    /**
     * Bottom-of-column positions with silencerCount>0, keyed by dimension.
     * Populated on BOTH client and server so SilencerHandler can check it client-side.
     */
    static final Map<ResourceKey<Level>, Set<BlockPos>> silencerRegistry =
        new ConcurrentHashMap<>();

    /** Called from WnirMod.onServerStopping() to clear all registry entries. */
    public static void clearRegistry() {
        inhibitorRegistry.clear();
        silencerRegistry.clear();
    }

    // ── Tick ─────────────────────────────────────────────────────────────

    public static void serverTick(
        Level level,
        BlockPos pos,
        BlockState state,
        WardingColumnBlockEntity be
    ) {
        if (!be.isBottomOfColumn) return;
        if (!be.hasRepel && be.hurtPostCount == 0) return;
        if (++be.tickCounter < TICK_INTERVAL) return;
        be.tickCounter = 0;

        double radius = be.totalRadius + 0.5;
        Vec3 center = Vec3.atCenterOf(pos);
        AABB area = new AABB(
            center.x - radius, center.y - VERTICAL_RANGE, center.z - radius,
            center.x + radius, center.y + VERTICAL_RANGE, center.z + radius
        );

        if (be.hasRepel) {
            for (Mob mob : level.getEntitiesOfClass(Mob.class, area, Mob::isAggressive)) {
                Vec3 dir = mob.position().subtract(center);
                double dist = dir.horizontalDistance();
                if (dist < CENTER_EPSILON) { dir = new Vec3(1, 0, 0); dist = 1; }
                double scale = PUSH_STRENGTH / dist;
                mob.push(dir.x * scale, PUSH_UPWARD, dir.z * scale);
                mob.hurtMarked = true;
            }
        }

        if (be.hurtPostCount > 0) {
            float damage = HURT_DAMAGE * be.hurtPostCount;
            // indirectMagic bypasses armor and attributes kills to the installer so mobs drop full loot.
            Player installer = be.installerUUID != null
                ? ((ServerLevel) level).getServer().getPlayerList().getPlayer(be.installerUUID)
                : null;
            DamageSource src = installer != null
                ? level.damageSources().indirectMagic(installer, installer)
                : level.damageSources().magic();
            for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m instanceof Enemy)) {
                mob.hurt(src, damage);
            }
        }
    }

    // ── Column recalc ─────────────────────────────────────────────────────

    void recalcColumn() { recalcColumn(null); }

    void recalcColumn(BlockPos exclude) {
        if (level == null) return;
        isBottomOfColumn = ColumnHelper.isBottomOfColumn(
            level, worldPosition, exclude, WardingColumnBlock.class
        );
        if (!isBottomOfColumn) {
            updateRegistry();
            return;
        }

        int wardingCount = ColumnHelper.countInColumn(
            level, worldPosition, exclude, WardingColumnBlock.class, WardingPostBlock.class
        );
        int repelCount = ColumnHelper.countInColumn(
            level, worldPosition, exclude, WardingColumnBlock.class, RepellingPostBlock.class
        );
        int inhibitCount = ColumnHelper.countInColumn(
            level, worldPosition, exclude, WardingColumnBlock.class, TeleporterInhibitorBlock.class
        );
        int hurtCount = ColumnHelper.countInColumn(
            level, worldPosition, exclude, WardingColumnBlock.class, HurtPostBlock.class
        );
        int silencerCount = ColumnHelper.countInColumn(
            level, worldPosition, exclude, WardingColumnBlock.class, SilencerPostBlock.class
        );

        totalRadius = WARDING_RADIUS * wardingCount
                    + REPELLING_RADIUS * repelCount
                    + INHIBITOR_RADIUS * inhibitCount
                    + HURT_RADIUS * hurtCount
                    + SILENCER_RADIUS * silencerCount;
        hasRepel           = repelCount > 0;
        hasInhibit         = inhibitCount > 0;
        hurtPostCount      = hurtCount;
        this.silencerCount = silencerCount;

        // Collect installer UUID from any BE in the column (bottom BE owns the result).
        UUID[] found = {installerUUID};
        ColumnHelper.forEachInColumn(level, worldPosition, WardingColumnBlock.class,
            WardingColumnBlockEntity.class, columnBe -> {
                if (found[0] == null && columnBe.installerUUID != null) {
                    found[0] = columnBe.installerUUID;
                }
            });
        installerUUID = found[0];

        updateRegistry();
    }

    /** Called from WardingColumnBaseBlock.setPlacedBy when a player places any column block. */
    void setInstaller(UUID uuid) {
        this.installerUUID = uuid;
        setChanged();
    }

    // ── Persistence ───────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String raw = input.getStringOr("installer", "");
        installerUUID = raw.isEmpty() ? null : UUID.fromString(raw);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (installerUUID != null) output.putString("installer", installerUUID.toString());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        recalcColumn();
    }

    @Override
    public void setRemoved() {
        // Safe: only removes from static Sets, no world access.
        if (level != null) {
            ResourceKey<Level> key = level.dimension();
            if (!level.isClientSide()) {
                Set<BlockPos> inhibSet = inhibitorRegistry.get(key);
                if (inhibSet != null) inhibSet.remove(worldPosition);
            }
            Set<BlockPos> silSet = silencerRegistry.get(key);
            if (silSet != null) silSet.remove(worldPosition);
        }
        super.setRemoved();
    }
}
