package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PlayerHeadBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Set;
import java.util.UUID;

/**
 * Personal Dimension Teleporter — transports a player to their unique personal dimension.
 *
 * Head mechanic (block directly above):
 *   - No skull: no one can use.
 *   - Player skull: only the skull's owner may teleport.
 *   - Mob skull (skeleton, creeper, etc.): any player may teleport.
 *
 * Hunger cost: player must have a full hunger bar (20/20).
 *   On attempt with &lt;20 hunger: deal 1 HP damage and deny.
 *   On successful teleport: drain hunger to 0 (and saturation to 0).
 *
 * Dimension: wnir:personal — single shared dimension with per-player X-axis regions.
 *   Each region is PersonalBiomeSource.REGION_WIDTH blocks wide.
 *   Spawn point is placed on the surface at the center of the player's region.
 */
public class PersonalDimensionTeleporterBlock extends Block {

    static final ResourceKey<Level> PERSONAL_DIM = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "personal")
    );

    private static final MapCodec<PersonalDimensionTeleporterBlock> CODEC =
        simpleCodec(PersonalDimensionTeleporterBlock::new);

    @Override
    protected MapCodec<PersonalDimensionTeleporterBlock> codec() { return CODEC; }

    public PersonalDimensionTeleporterBlock(BlockBehaviour.Properties props) {
        super(props);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel serverLevel = (ServerLevel) level;
        MinecraftServer server = serverLevel.getServer();
        ServerPlayer serverPlayer = (ServerPlayer) player;

        // ── Head check ────────────────────────────────────────────────────
        BlockPos headPos = pos.above();
        BlockState headState = level.getBlockState(headPos);
        if (!canPlayerUse(headState, level, headPos, serverPlayer)) {
            serverPlayer.sendSystemMessage(
                Component.translatable("block.wnir.personal_dimension_teleporter.no_access")
            );
            return InteractionResult.CONSUME;
        }

        // ── Hunger check ──────────────────────────────────────────────────
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() < 20) {
            player.hurt(level.damageSources().magic(), 1.0f);
            serverPlayer.sendSystemMessage(
                Component.translatable("block.wnir.personal_dimension_teleporter.hungry")
            );
            return InteractionResult.CONSUME;
        }

        // ── Get personal dimension ────────────────────────────────────────
        ServerLevel personalLevel = server.getLevel(PERSONAL_DIM);
        if (personalLevel == null) {
            WnirMod.LOGGER.error(
                "Personal dimension 'wnir:personal' not found. " +
                "Ensure data/wnir/dimension/personal.json is present."
            );
            serverPlayer.sendSystemMessage(
                Component.literal("Error: personal dimension not loaded. Check server logs.")
            );
            return InteractionResult.CONSUME;
        }

        // ── Consume hunger AFTER all validation passes ────────────────────
        food.setFoodLevel(0);
        food.setSaturation(0.0f);

        // ── Assign region & compute spawn ─────────────────────────────────
        // Use the biome at the teleporter's location as the player's personal dimension biome.
        Holder<Biome> biomeHolder = serverLevel.getBiome(pos);
        Identifier biomeId = biomeHolder.unwrapKey()
            .map(k -> k.identifier())
            .orElse(Identifier.parse("minecraft:plains"));

        PersonalDimensionManager manager = PersonalDimensionManager.get(server);
        manager.getOrCreateRegion(serverPlayer.getUUID(), biomeId);

        BlockPos spawnPos = computeSpawnPos(manager, serverPlayer.getUUID(), personalLevel);

        // ── Teleport (cross-dimension) ────────────────────────────────────
        // boolean parameter: false = do not reset camera entity to self
        serverPlayer.teleportTo(
            personalLevel,
            spawnPos.getX() + 0.5,
            spawnPos.getY(),
            spawnPos.getZ() + 0.5,
            Set.of(),       // no relative movement flags
            player.getYRot(),
            player.getXRot(),
            false
        );

        return InteractionResult.CONSUME;
    }

    /**
     * Returns the surface spawn position for this player's region.
     * Forces the spawn chunk to generate if not yet generated, then reads the heightmap.
     */
    private BlockPos computeSpawnPos(PersonalDimensionManager manager, UUID playerId, ServerLevel level) {
        int centerX = manager.getRegionCenterX(playerId);
        int centerZ = 0;

        // Force-generate the chunk so heightmap is accurate (synchronous on server thread)
        int chunkX = centerX >> 4;
        int chunkZ = centerZ >> 4;
        level.getChunk(chunkX, chunkZ);

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);
        // In overworld-like dimensions min Y is -64; a value ≤ -64 means void/ungenerated
        if (surfaceY <= -64) {
            surfaceY = 64; // failsafe
        }
        return new BlockPos(centerX, surfaceY, centerZ);
    }

    /**
     * Returns true if this player is permitted to use the teleporter given the skull above it.
     *
     * @param headState  block state of the block directly above the teleporter
     * @param level      world
     * @param headPos    position of that block
     * @param player     player attempting to use
     */
    private boolean canPlayerUse(
        BlockState headState, Level level, BlockPos headPos, ServerPlayer player
    ) {
        Block block = headState.getBlock();
        if (!(block instanceof SkullBlock)) return false; // no skull → no access

        if (block instanceof PlayerHeadBlock) {
            // Player skull: only the skull's owner may use
            if (level.getBlockEntity(headPos) instanceof SkullBlockEntity skullBE) {
                var profile = skullBE.getOwnerProfile();
                if (profile == null) return true; // skull with no owner → public
                // GameProfile is a record in authlib 7.x: id(), name() (not getId/getName)
                com.mojang.authlib.GameProfile gp = profile.partialProfile();
                UUID ownerId = gp.id();
                // UUID is never null in record GameProfile; but may be all-zeros if unresolved
                if (!ownerId.equals(new UUID(0, 0))) {
                    return ownerId.equals(player.getUUID());
                }
                // No UUID: fall back to name match (empty string = unresolved)
                String ownerName = gp.name();
                if (!ownerName.isEmpty()) {
                    return ownerName.equalsIgnoreCase(player.getName().getString());
                }
                return true; // fully unresolved skull → allow
            }
            return true; // no block entity → allow
        }

        // Any mob/dragon/wither skull → everyone can use
        return true;
    }
}
