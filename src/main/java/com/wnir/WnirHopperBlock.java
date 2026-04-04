package com.wnir;

import com.mojang.serialization.MapCodec;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Shared block class for all 10-slot WNIR hoppers (Mossy, Steel, Nether).
 * Each variant is created via a static factory that injects the BE type and ticker.
 */
public class WnirHopperBlock extends HopperBlock {

    // Codec-only instance — blocks are always loaded from the registry by ID, never via codec.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final MapCodec<WnirHopperBlock> CODEC =
        simpleCodec(props -> new WnirHopperBlock(props, null, null, (BlockEntityTicker) null));

    private final BiFunction<BlockPos, BlockState, ? extends RandomizableContainerBlockEntity> beFactory;
    private final Supplier<? extends BlockEntityType<?>> beTypeSupplier;
    // Stored as supertypes; runtime casts are safe because each factory sets matching types.
    private final BlockEntityTicker<RandomizableContainerBlockEntity> ticker;

    // ── Per-variant static factories ─────────────────────────────────────────
    // Static methods (not lambdas in WnirRegistries) so there is no self-reference
    // in the static field initializer — same pattern as WnirHopperMenu.mossy() etc.

    static WnirHopperBlock mossy(BlockBehaviour.Properties props) {
        return new WnirHopperBlock(props,
            MossyHopperBlockEntity::new,
            () -> WnirRegistries.MOSSY_HOPPER_BE.get(),
            (l, p, s, be) -> MossyHopperBlockEntity.serverTick(l, p, s, (MossyHopperBlockEntity) be));
    }

    static WnirHopperBlock steel(BlockBehaviour.Properties props) {
        return new WnirHopperBlock(props,
            SteelHopperBlockEntity::new,
            () -> WnirRegistries.STEEL_HOPPER_BE.get(),
            (l, p, s, be) -> SteelHopperBlockEntity.serverTick(l, p, s, (SteelHopperBlockEntity) be));
    }

    static WnirHopperBlock nether(BlockBehaviour.Properties props) {
        return new WnirHopperBlock(props,
            NetherHopperBlockEntity::new,
            () -> WnirRegistries.NETHER_HOPPER_BE.get(),
            (l, p, s, be) -> NetherHopperBlockEntity.serverTick(l, p, s, (NetherHopperBlockEntity) be));
    }

    private WnirHopperBlock(
        BlockBehaviour.Properties props,
        BiFunction<BlockPos, BlockState, ? extends RandomizableContainerBlockEntity> beFactory,
        Supplier<? extends BlockEntityType<?>> beTypeSupplier,
        BlockEntityTicker<RandomizableContainerBlockEntity> ticker
    ) {
        super(props);
        this.beFactory      = beFactory;
        this.beTypeSupplier = beTypeSupplier;
        this.ticker         = ticker;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public MapCodec<HopperBlock> codec() {
        return (MapCodec<HopperBlock>) (MapCodec<?>) CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return beFactory.apply(pos, state);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        return level.isClientSide() ? null
            : createTickerHelper(type, (BlockEntityType) beTypeSupplier.get(),
                (BlockEntityTicker) ticker);
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MenuProvider be) {
            player.openMenu(be);
            player.awardStat(Stats.INSPECT_HOPPER);
        }
        return InteractionResult.CONSUME;
    }
}
