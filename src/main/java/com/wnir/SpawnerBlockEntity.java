package com.wnir;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Spawner block entity.
 *
 * Consumes Magic Cellulose fluid to spawn hostile mobs of the types that
 * naturally spawn in the biome at the placement location. Each mob "costs"
 * XP equal to what it would drop on a normal kill. Spawned mobs receive a
 * half-heart hit attributed to the installer player so loot tables credit them.
 *
 * Rates:
 *   TANK_CAPACITY  = 16 000 mB
 *   FLUID_PER_TICK = 10 mB  drained each active tick
 *   XP_PER_TICK   = 5       accumulated toward spawn cost per tick
 */
public class SpawnerBlockEntity extends BlockEntity {

    static final int TANK_CAPACITY        = 16_000;
    static final int FLUID_PER_TICK       = 10;
    static final int XP_PER_TICK          = 5;
    /** Multiplier on mob XP cost so a 5-XP mob takes 20 ticks ≈ 1 second. */
    static final int SPAWN_COST_MULTIPLIER = 20;

    // ── Fluid ────────────────────────────────────────────────────────────────

    final FluidStacksResourceHandler fluidHandler = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        public boolean isValid(int slot, FluidResource resource) {
            return !resource.isEmpty()
                && resource.getFluid() == WnirRegistries.MAGIC_CELLULOSE_STILL.get();
        }
        @Override
        protected void onContentsChanged(int slot, FluidStack previous) {
            SpawnerBlockEntity.this.setChanged();
        }
    };

    // ── Installer ────────────────────────────────────────────────────────────

    UUID installerUUID = null;

    void setInstaller(UUID uuid) {
        this.installerUUID = uuid;
        setChanged();
    }

    // ── Spawn state ──────────────────────────────────────────────────────────

    private record SpawnCandidate(EntityType<? extends Mob> type, int weight, int xp) {}

    private final List<SpawnCandidate> candidates = new ArrayList<>();
    private int totalWeight = 0;

    /** Mob type currently being accumulated toward a spawn. Null = pick on next tick. */
    private EntityType<? extends Mob> currentTarget = null;
    private int targetXp      = 1;
    private int accumulatedXp = 0;

    // ── Construction ─────────────────────────────────────────────────────────

    public SpawnerBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.SPAWNER_BE.get(), pos, state);
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fluidHandler.deserialize(input.childOrEmpty("Fluid"));
        String rawInstaller = input.getStringOr("installer", "");
        installerUUID = rawInstaller.isEmpty() ? null : UUID.fromString(rawInstaller);
        String rawTarget = input.getStringOr("target", "");
        if (!rawTarget.isEmpty()) {
            Identifier id = Identifier.tryParse(rawTarget);
            if (id != null) {
                var opt = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
                if (opt.isPresent()) {
                    @SuppressWarnings("unchecked")
                    EntityType<? extends Mob> t = (EntityType<? extends Mob>) opt.get();
                    currentTarget = t;
                }
            }
        }
        targetXp      = input.getIntOr("targetXp",    1);
        accumulatedXp = input.getIntOr("accumulated", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        fluidHandler.serialize(output.child("Fluid"));
        if (installerUUID != null) output.putString("installer", installerUUID.toString());
        if (currentTarget != null) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(currentTarget);
            if (key != null) output.putString("target", key.toString());
        }
        output.putInt("targetXp",    targetXp);
        output.putInt("accumulated", accumulatedXp);
    }

    // ── Biome scan ───────────────────────────────────────────────────────────

    private void scanBiome(ServerLevel level, BlockPos pos) {
        candidates.clear();
        totalWeight = 0;
        List<Weighted<MobSpawnSettings.SpawnerData>> spawns = level.getBiome(pos).value()
            .getMobSettings().getMobs(MobCategory.MONSTER).unwrap();
        for (Weighted<MobSpawnSettings.SpawnerData> weighted : spawns) {
            MobSpawnSettings.SpawnerData data = weighted.value();
            int weight = weighted.weight();
            if (weight <= 0) continue;
            @SuppressWarnings("unchecked")
            EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) data.type();
            int xp = getBaseXp(mobType, level);
            candidates.add(new SpawnCandidate(mobType, weight, xp));
            totalWeight += weight;
        }
    }

    /** Get base XP by creating a temporary instance (not added to world). */
    private static int getBaseXp(EntityType<? extends Mob> type, ServerLevel level) {
        try {
            Mob mob = (Mob) type.create(level, EntitySpawnReason.NATURAL);
            if (mob != null) return Math.max(1, mob.getExperienceReward(level, null));
        } catch (Exception ignored) {}
        return 5;
    }

    private void pickNewTarget() {
        if (candidates.isEmpty() || totalWeight == 0) return;
        int roll = (int) (Math.random() * totalWeight);
        int cum = 0;
        for (SpawnCandidate c : candidates) {
            cum += c.weight();
            if (roll < cum) {
                setTarget(c);
                return;
            }
        }
        setTarget(candidates.get(candidates.size() - 1));
    }

    private void setTarget(SpawnCandidate c) {
        currentTarget = c.type();
        targetXp      = Math.max(1, c.xp()) * SPAWN_COST_MULTIPLIER;
        accumulatedXp = 0;
        setChanged();
    }

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void serverTick(
        Level level, BlockPos pos, BlockState state, SpawnerBlockEntity be
    ) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Populate candidates on first tick (avoids issues when level isn't fully ready at onLoad)
        if (be.candidates.isEmpty()) {
            be.scanBiome(serverLevel, pos);
            if (be.candidates.isEmpty()) return;
        }

        if (be.currentTarget == null) {
            be.pickNewTarget();
            if (be.currentTarget == null) return;
        }

        // Pause on redstone signal or insufficient fluid
        if (level.hasNeighborSignal(pos)) return;
        if (be.fluidHandler.getAmountAsLong(0) < FLUID_PER_TICK) return;

        // Drain fluid
        FluidResource resource = FluidResource.of(WnirRegistries.MAGIC_CELLULOSE_STILL.get());
        try (var tx = Transaction.openRoot()) {
            be.fluidHandler.extract(0, resource, FLUID_PER_TICK, tx);
            tx.commit();
        }
        be.accumulatedXp += XP_PER_TICK;
        be.setChanged();

        if (be.accumulatedXp >= be.targetXp) {
            spawnMob(serverLevel, pos, be);
            be.currentTarget = null; // pick new target on next active tick
        }
    }

    private static void spawnMob(ServerLevel level, BlockPos pos, SpawnerBlockEntity be) {
        BlockPos spawnPos = pos.above();
        Mob mob = (Mob) be.currentTarget.create(level, EntitySpawnReason.SPAWNER);
        if (mob == null) return;
        mob.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
            EntitySpawnReason.SPAWNER, null);
        level.addFreshEntity(mob);
        // Half-heart hit attributed to installer so mob loot credits the player
        ServerPlayer installer = be.installerUUID != null
            ? level.getServer().getPlayerList().getPlayer(be.installerUUID)
            : null;
        if (installer != null) {
            mob.hurt(level.damageSources().playerAttack(installer), 1.0f);
        }
    }
}
