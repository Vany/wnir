package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Professional Seed Bundle — upgraded version of Seed Bundle.
 *
 * In addition to all Seed Bundle behaviour (hold seeds, right-click farmland to plant),
 * right-clicking on any 100% mature crop flood-fills all connected farmland, harvests every
 * fully grown crop it finds, resets each harvested crop to age 0, and drops all collected
 * items at the player's feet.
 */
public class ProfessionalSeedBundleItem extends SeedBundleItem {

    /** Max farmland blocks searched during harvest flood-fill. */
    private static final int MAX_HARVEST_FLOOD = 64;

    public ProfessionalSeedBundleItem(Item.Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos clicked = ctx.getClickedPos();
        BlockState clickedState = level.getBlockState(clicked);

        // Resolve farmland origin for harvest.
        // Two cases: ray hit the crop block directly, OR ray hit the farmland below a crop
        // (common because crop cross-models are narrower than a full block).
        BlockPos farmlandOrigin = null;
        if (isMatureCrop(clickedState)) {
            BlockPos below = clicked.below();
            if (isPlantableSoil(level, below)) farmlandOrigin = below;
        } else if (isPlantableSoil(level, clicked) && isMatureCrop(level.getBlockState(clicked.above()))) {
            farmlandOrigin = clicked;
        }

        if (farmlandOrigin != null) {
            List<ItemStack> harvested = floodFillAndHarvest((ServerLevel) level, player, farmlandOrigin);
            if (!harvested.isEmpty()) {
                BlockPos dropPos = player.blockPosition();
                for (ItemStack drop : harvested) {
                    Block.popResource(level, dropPos, drop);
                }
                level.playSound(null, clicked, SoundEvents.ITEM_PICKUP,
                    SoundSource.PLAYERS, 0.2f, 0.7f + level.getRandom().nextFloat() * 0.4f);
                return InteractionResult.CONSUME;
            }
            // Mature crops found but getDrops returned nothing (edge case) — still consume
            // to avoid accidentally triggering planting on occupied slots.
            return InteractionResult.CONSUME;
        }

        // No crop detected — fall through to Seed Bundle planting behaviour.
        return super.useOn(ctx);
    }

    /** True if the state is a fully mature crop (CropBlock at max age, or NetherWartBlock age 3). */
    static boolean isMatureCrop(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock crop) return crop.isMaxAge(state);
        if (b instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) == 3;
        return false;
    }

    /**
     * BFS from {@code origin} across connected farmland / soul sand.
     * At each position, if the block above is a 100% mature crop: collect its drops and reset it to age 0.
     * Returns all collected drops (not yet spawned in world).
     */
    private static List<ItemStack> floodFillAndHarvest(ServerLevel level, Player player, BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);
        List<ItemStack> allDrops = new ArrayList<>();
        int processed = 0;

        while (!queue.isEmpty() && processed < MAX_HARVEST_FLOOD) {
            BlockPos pos = queue.poll();
            processed++;

            BlockPos cropPos = pos.above();
            BlockState cropState = level.getBlockState(cropPos);
            if (isMatureCrop(cropState)) {
                // Collect drops using the loot table (respects Fortune etc. on held item)
                List<ItemStack> drops = Block.getDrops(
                    cropState, level, cropPos,
                    level.getBlockEntity(cropPos),
                    player, player.getMainHandItem()
                );
                allDrops.addAll(drops);
                // Reset to age 0 so the farmland stays tilled and the crop regrows
                Block b = cropState.getBlock();
                if (b instanceof CropBlock crop) {
                    level.setBlock(cropPos, crop.getStateForAge(0), 3);
                } else {
                    // NetherWartBlock: defaultBlockState() has age 0
                    level.setBlock(cropPos, b.defaultBlockState(), 3);
                }
            }

            // Spread to adjacent farmland/soul sand, seeded or not
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos neighbor = pos.relative(dir).above(dy);
                    if (!visited.contains(neighbor) && isPlantableSoil(level, neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return allDrops;
    }

    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext ctx, TooltipDisplay display,
        Consumer<Component> out, TooltipFlag flag
    ) {
        // Bundle contents are shown via BundleContents component — no need for super call here.
        WnirTooltips.add(out, flag,
            Component.translatable("tooltip.wnir.professional_seed_bundle"),
            Component.translatable("tooltip.wnir.professional_seed_bundle.detail"));
    }
}
