package com.wnir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Target Post — warding column block that damages mobs filtered by custom name.
 *
 * If unnamed: damages all hostile mobs (same as HurtPostBlock).
 * If renamed in an anvil (e.g. "Creeper"): damages only mobs whose entity type path matches
 * the given name (case-insensitive). Multiple named Target Posts in one column → each adds
 * its name to the filter; all matching types are attacked.
 *
 * Damage: same HURT_DAMAGE × targetPostCount as HurtPost.
 * Contributes NO radius.
 *
 * Recipe: warding_post + target block (shapeless).
 */
public class TargetPostBlock extends WardingColumnBaseBlock {

    private static final MapCodec<TargetPostBlock> CODEC = simpleCodec(TargetPostBlock::new);

    @Override
    protected MapCodec<TargetPostBlock> codec() { return CODEC; }

    public TargetPostBlock(BlockBehaviour.Properties props) { super(props); }

    @Override
    protected Supplier<BlockEntityType<? extends WardingColumnBlockEntity>> beTypeSupplier() {
        return WnirRegistries.WARDING_COLUMN_BLOCK_ENTITY::get;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WardingColumnBlockEntity.create(pos, state);
    }

    /** Right-click any mob with a Target Post item → stamps the mob's entity type name onto the item. */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TargetPostBlock)) return;
        if (!(event.getTarget() instanceof Mob mob)) return;
        String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
        String display = Character.toUpperCase(typePath.charAt(0)) + typePath.substring(1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(display));
        event.setCanceled(true);
    }

    /**
     * Called from WnirMod.onBlockPlaced via BlockEvent.EntityPlaceEvent.
     * Fires while the player still holds the original (unconsumed) item, so CUSTOM_NAME is intact.
     * Also calls notifyColumn so targetFilters is rebuilt immediately (not deferred to randomTick).
     */
    static void onEntityPlace(Level level, BlockPos pos, net.minecraft.world.entity.player.Player player) {
        ItemStack heldStack = findHeldTargetPost(player);
        if (heldStack == null) return;
        Component customName = heldStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;
        if (!(level.getBlockEntity(pos) instanceof WardingColumnBlockEntity be)) return;
        String raw = customName.getString().trim();
        String filter = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        be.setTargetName(filter.toLowerCase(Locale.ROOT));
        WardingColumnBaseBlock.notifyColumn(level, pos);
    }

    private static ItemStack findHeldTargetPost(net.minecraft.world.entity.player.Player player) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack s = player.getItemInHand(hand);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TargetPostBlock) return s;
        }
        return null;
    }

    /** Restores the filter name on the dropped item when a named Target Post is broken. */
    public static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getState().getBlock() instanceof TargetPostBlock)) return;
        if (!(event.getBlockEntity() instanceof WardingColumnBlockEntity be)) return;
        if (be.targetName.isEmpty()) return;
        String display = Character.toUpperCase(be.targetName.charAt(0)) + be.targetName.substring(1);
        for (ItemEntity ie : event.getDrops()) {
            ItemStack stack = ie.getItem();
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                    && bi.getBlock() instanceof TargetPostBlock) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(display));
            }
        }
    }
}
