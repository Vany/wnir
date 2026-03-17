package com.wnir;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Blue Sticky Tape — picks up any block (except bedrock/air) with full NBT,
 * then places it back on right-click, consuming the item.
 *
 * Stored data (DataComponents.CUSTOM_DATA):
 *   "block_state" → NbtUtils.writeBlockState result
 *   "block_entity" → BlockEntity.saveWithoutMetadata result (optional)
 *
 * Sets CUSTOM_MODEL_DATA=1 when filled so the items JSON switches to the
 * SpecialModelRenderer that draws the stored block + blue cross.
 * When filled, the item name becomes "Wrapped <block name>" and the tooltip
 * shows container contents or spawner entity type.
 */
public class BlueStickyTapeItem extends Item {

    public BlueStickyTapeItem(Properties props) {
        super(props);
    }

    // -------------------------------------------------------------------------
    // Name and tooltip
    // -------------------------------------------------------------------------

    @Override
    public Component getName(ItemStack stack) {
        if (!hasStoredBlock(stack)) return super.getName(stack);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return super.getName(stack);
        String blockId = data.copyTag()
            .getCompound("block_state").flatMap(t -> t.getString("Name")).orElse("");
        if (blockId.isEmpty()) return super.getName(stack);
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) return super.getName(stack);
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == Blocks.AIR && !blockId.contains("air")) return super.getName(stack);
        return Component.translatable("item.wnir.blue_sticky_tape.wrapped", block.getName());
    }

    @Override
    public void appendHoverText(
        ItemStack stack,
        Item.TooltipContext context,
        TooltipDisplay tooltipDisplay,
        Consumer<Component> consumer,
        TooltipFlag flag
    ) {
        CompoundTag beTag = getStoredBeTag(stack);
        if (beTag == null) return;

        // Container contents (chest, furnace, hopper, etc.)
        // Items are saved as: {"Slot": N, "id": "...", "count": N}
        beTag.getList("Items").ifPresent(items -> {
            int shown = 0;
            for (Tag t : items) {
                if (!(t instanceof CompoundTag itemTag)) continue;
                String itemId = itemTag.getString("id").orElse("");
                int count = itemTag.getInt("count").orElse(1);
                if (itemId.isEmpty()) continue;
                Identifier itemIdentifier = Identifier.tryParse(itemId);
                if (itemIdentifier == null) continue;
                net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getValue(itemIdentifier);
                Component name = new ItemStack(item).getHoverName();
                Component line = count > 1
                    ? Component.literal(count + "× ").append(name)
                    : name;
                consumer.accept(line.copy().withStyle(ChatFormatting.GRAY));
                if (++shown >= 8) break;
            }
        });

        // Spawner entity type
        beTag.getCompound("SpawnData").ifPresent(spawnData ->
            spawnData.getCompound("entity").ifPresent(entity -> {
                String entityId = entity.getString("id").orElse("");
                if (entityId.isEmpty()) return;
                Identifier eid = Identifier.tryParse(entityId);
                if (eid == null) return;
                consumer.accept(
                    Component.translatable("entity." + eid.getNamespace() + "." + eid.getPath())
                        .withStyle(ChatFormatting.GRAY)
                );
            })
        );
    }

    // -------------------------------------------------------------------------
    // Pickup / place logic
    // -------------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        BlockPos pos   = ctx.getClickedPos();
        ItemStack stack = ctx.getItemInHand();

        if (hasStoredBlock(stack)) {
            BlockPos placePos  = pos.relative(ctx.getClickedFace());
            BlockState stored  = getStoredState(stack, level);
            if (!level.getBlockState(placePos).canBeReplaced()) return InteractionResult.FAIL;

            level.setBlockAndUpdate(placePos, stored);

            CompoundTag beTag = getStoredBeTag(stack);
            if (beTag != null) {
                BlockEntity be = level.getBlockEntity(placePos);
                if (be != null) {
                    be.loadWithComponents(
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), beTag));
                }
            }

            stack.remove(DataComponents.CUSTOM_DATA);
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            stack.shrink(1);
            return InteractionResult.CONSUME;
        }

        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK) || state.isAir()) return InteractionResult.FAIL;

        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(state));

        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) tag.put("block_entity", be.saveWithoutMetadata(level.registryAccess()));

        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(1.0f), List.of(), List.of(), List.of()));

        // Clear container contents BEFORE removal to suppress inventory drops.
        if (be instanceof net.minecraft.world.Container container) container.clearContent();
        level.removeBlock(pos, false);
        return InteractionResult.CONSUME;
    }

    // -------------------------------------------------------------------------
    // Stored data helpers
    // -------------------------------------------------------------------------

    public static boolean hasStoredBlock(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains("block_state");
    }

    public static BlockState getStoredState(ItemStack stack, Level level) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Blocks.AIR.defaultBlockState();
        CompoundTag stateTag = data.copyTag().getCompound("block_state").orElse(new CompoundTag());
        return NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK), stateTag);
    }

    public static BlockState getStoredStateClient(ItemStack stack) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return Blocks.AIR.defaultBlockState();
        return getStoredState(stack, mc.level);
    }

    public static CompoundTag getStoredBeTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        return data.copyTag().getCompound("block_entity").orElse(null);
    }
}
