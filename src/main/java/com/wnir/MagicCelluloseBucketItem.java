package com.wnir;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.material.Fluid;
import java.util.function.Consumer;

/**
 * Magic Cellulose Bucket — extends BucketItem to add vault renewal on use.
 *
 * Right-clicking on any used (or partially-used) trial chamber vault block
 * clears the list of players who have already claimed their reward and
 * reactivates the vault, consuming the bucket in the process.
 *
 * Plays the bucket-empty (water-splash) sound on success.
 */
public class MagicCelluloseBucketItem extends BucketItem {

    public MagicCelluloseBucketItem(Fluid fluid, Item.Properties props) {
        super(fluid, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockState blockState = level.getBlockState(ctx.getClickedPos());

        // ── Trial Spawner: reduce cooldown by 1 hour (72 000 ticks) ──────────────
        if (blockState.getBlock() instanceof TrialSpawnerBlock) {
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            var serverLevel = (net.minecraft.server.level.ServerLevel) level;
            var pos         = ctx.getClickedPos();
            var player      = ctx.getPlayer();
            if (!(serverLevel.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawnerBe))
                return super.useOn(ctx);

            var ts   = spawnerBe.getTrialSpawner();
            var data = ts.getStateData();
            if (ts.getState() != TrialSpawnerState.COOLDOWN) return InteractionResult.PASS;

            long newEnd = Math.max(0L, TrialSpawnerAccessor.getCooldownEndsAt(data) - 72_000L);
            TrialSpawnerAccessor.setCooldownEndsAt(data, newEnd);
            spawnerBe.setChanged();
            serverLevel.sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_ALL);
            serverLevel.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
            if (player != null) {
                ItemStack hand = ctx.getItemInHand();
                player.setItemInHand(ctx.getHand(), BucketItem.getEmptySuccessItem(hand, player));
            }
            return InteractionResult.CONSUME;
        }

        // Only intercept vault blocks; fall through to normal bucket behavior otherwise
        if (!(blockState.getBlock() instanceof VaultBlock)) {
            return super.useOn(ctx);
        }

        if (level.isClientSide()) return InteractionResult.SUCCESS;

        var serverLevel = (net.minecraft.server.level.ServerLevel) level;
        var pos         = ctx.getClickedPos();
        var player      = ctx.getPlayer();

        if (!(serverLevel.getBlockEntity(pos) instanceof VaultBlockEntity vaultBe)) {
            return super.useOn(ctx);
        }

        // ── Clear rewarded-players list via NBT round-trip ─────────────────────
        CompoundTag beTag = vaultBe.saveCustomOnly(serverLevel.registryAccess());
        beTag.getCompound("server_data").ifPresent(sd -> {
            sd.remove("rewarded_players");
            sd.remove("state_updating_resumes_at"); // clear pause timer so vault can tick immediately
        });
        vaultBe.loadWithComponents(
            TagValueInput.create(ProblemReporter.DISCARDING, serverLevel.registryAccess(), beTag));
        vaultBe.setChanged();

        // ── Reactivate block state if it was INACTIVE ──────────────────────────
        if (blockState.getValue(VaultBlock.STATE) == VaultState.INACTIVE) {
            serverLevel.setBlock(pos,
                blockState.setValue(VaultBlock.STATE, VaultState.ACTIVE),
                Block.UPDATE_ALL);
        } else {
            serverLevel.sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_ALL);
        }

        // ── Sound ──────────────────────────────────────────────────────────────
        serverLevel.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);

        // ── Consume bucket → empty bucket ──────────────────────────────────────
        if (player != null) {
            ItemStack hand = ctx.getItemInHand();
            ItemStack result = BucketItem.getEmptySuccessItem(hand, player);
            player.setItemInHand(ctx.getHand(), result);
        }

        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext ctx,
            TooltipDisplay display,
            Consumer<Component> out,
            TooltipFlag flag) {
        super.appendHoverText(stack, ctx, display, out, flag);
        WnirTooltips.add(out, flag,
            Component.translatable("tooltip.wnir.magic_cellulose_bucket"),
            Component.translatable("tooltip.wnir.magic_cellulose_bucket.detail"));
    }
}
