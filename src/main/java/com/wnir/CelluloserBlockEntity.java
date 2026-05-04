package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.core.Holder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Celluloser block entity.
 *
 * ── Configurable parameters ──────────────────────────────────────────────────
 *   XP_PER_TICK    = 200   XP processed per server tick
 *   OUTPUT_DIVISOR = 10    XP / divisor = mB of magic cellulose produced
 *   WATER_PER_MB   = 1     mB of water consumed per mB of cellulose produced
 *   FE_PER_MB      = 10    FE consumed per mB of cellulose produced
 *   TANK_CAPACITY  = 16000 max mB per tank (water in / cellulose out)
 *   ENERGY_CAPACITY= 1_000_000  max FE in buffer
 *
 * ── Processing ───────────────────────────────────────────────────────────────
 *   1. Consume item from slot 0 → calculate total XP
 *   2. Each tick: process min(remainingXp, XP_PER_TICK) XP if resources available
 *   3. Pause (preserve progress) when energy or water runs out
 *
 * ── Disassembly (slots 1–9) ───────────────────────────────────────────────────
 *   When slot 0 holds armor/weapon:
 *     - Roll survival probability = 1 - (damage / maxDamage)
 *     - Resolve materials via recipe lookup (smithing → recurse into base; crafting → no-armor filter)
 *     - If output slots full: pause until space appears
 *     - On success: place materials in slots 1–9, consume item, start XP processing if enchanted
 *
 * ── Capabilities ─────────────────────────────────────────────────────────────
 *   Energy: all faces insert; no extraction
 *   Fluid:  all faces → insert water (tank 0); all faces → extract cellulose (tank 1)
 */
public class CelluloserBlockEntity extends BlockEntity implements Container, net.minecraft.world.MenuProvider {

    // ── Configurable parameters ──────────────────────────────────────────────
    public static final int XP_PER_TICK     = 200;
    public static final int FE_PER_MB       = 10;   // FE consumed per mB of cellulose produced
    public static final int WATER_PER_MB    = 1;    // mB of water consumed per mB of cellulose produced
    public static final int OUTPUT_DIVISOR  = 10;
    public static final int TANK_CAPACITY   = 16_000;
    public static final int ENERGY_CAPACITY = 1_000_000;

    // ── State ────────────────────────────────────────────────────────────────

    static final int OUTPUT_SLOTS = 9; // slots 1..OUTPUT_SLOTS are disassembly output
    static final int TOTAL_SLOTS  = 1 + OUTPUT_SLOTS;

    // Slot 0 = input; slots 1–9 = disassembly output
    private NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private int remainingXp = 0;
    private int totalXp     = 0;

    // Recipe → material list cache. Populated lazily on first use.
    private final Map<Item, List<ItemStack>> disassemblyCache = new HashMap<>();

    /**
     * Two-tank fluid handler.
     *   Slot 0 = water input   — external insert allowed (water only), external extract disallowed
     *   Slot 1 = cellulose out — external extract allowed, external insert disallowed
     */
    final FluidStacksResourceHandler fluidHandler = new FluidStacksResourceHandler(2, TANK_CAPACITY) {
        @Override
        public boolean isValid(int slot, FluidResource resource) {
            if (resource.isEmpty()) return false;
            return switch (slot) {
                case 0 -> resource.getFluid() == Fluids.WATER;
                default -> false;
            };
        }
        @Override
        public int extract(int slot, FluidResource resource, int amount,
                net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (slot == 0) return 0;
            return super.extract(slot, resource, amount, transaction);
        }
        @Override
        protected void onContentsChanged(int slot, FluidStack previous) {
            CelluloserBlockEntity.this.setChanged();
        }
    };

    /** Energy buffer — external insert only, no extraction. */
    final SimpleEnergyHandler energyHandler = new SimpleEnergyHandler(
        ENERGY_CAPACITY, ENERGY_CAPACITY, 0
    ) {
        @Override
        protected void onEnergyChanged(int delta) {
            CelluloserBlockEntity.this.setChanged();
        }
    };

    // ── ContainerData (synced to client via menu) ────────────────────────────

    final ContainerData syncData = new ContainerData() {
        @Override
        public int get(int i) {
            int fe = energyHandler.getAmountAsInt();
            return switch (i) {
                case 0 -> fe & 0xFFFF;
                case 1 -> (fe >> 16) & 0xFFFF;
                case 2 -> (int) fluidHandler.getAmountAsLong(0);
                case 3 -> (int) fluidHandler.getAmountAsLong(1);
                case 4 -> Math.min(remainingXp, 32767);
                case 5 -> Math.min(totalXp, 32767);
                default -> 0;
            };
        }
        @Override public void set(int i, int value) {}
        @Override public int getCount() { return 6; }
    };

    // ── Construction ─────────────────────────────────────────────────────────

    public CelluloserBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.CELLULOSER_BE.get(), pos, state);
    }

    // ── NBT ─────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        energyHandler.deserialize(input.childOrEmpty("Energy"));
        fluidHandler.deserialize(input.childOrEmpty("Fluids"));
        remainingXp = input.getIntOr("RemainingXp", 0);
        totalXp     = input.getIntOr("TotalXp",     0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items, false);
        energyHandler.serialize(output.child("Energy"));
        fluidHandler.serialize(output.child("Fluids"));
        output.putInt("RemainingXp", remainingXp);
        output.putInt("TotalXp",     totalXp);
    }

    // ── Container ────────────────────────────────────────────────────────────

    @Override public int getContainerSize() { return TOTAL_SLOTS; }
    @Override public boolean isEmpty()       { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = ContainerHelper.removeItem(items, slot, amount);
        if (!stack.isEmpty()) setChanged();
        return stack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() { items.clear(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot != 0) return false; // output slots are read-only
        return isEnchanted(stack) || isConfigSource(stack) || isDisassemblableItem(stack);
    }

    static boolean isConfigSource(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && CelluloserConfig.EXTRA_SOURCES.containsKey(id);
    }

    static boolean isEnchanted(ItemStack stack) {
        var enc = stack.get(DataComponents.ENCHANTMENTS);
        if (enc != null && !enc.isEmpty()) return true;
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    /**
     * Items that trigger disassembly: armor (EQUIPPABLE), weapons (WEAPON), tools (TOOL),
     * plus bow/crossbow/trident which may not carry those components but are craftable equipment.
     */
    static boolean isDisassemblableItem(ItemStack stack) {
        return stack.has(DataComponents.EQUIPPABLE)
            || stack.has(DataComponents.WEAPON)
            || stack.has(DataComponents.TOOL)
            || stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            || stack.getItem() instanceof TridentItem;
    }

    // ── MenuProvider ─────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.wnir.celluloser");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new CelluloserMenu(id, playerInv, this, syncData);
    }

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, CelluloserBlockEntity be) {
        boolean changed = false;

        // Consume item from slot 0 when idle
        if (be.remainingXp == 0) {
            ItemStack input = be.items.get(0);
            if (!input.isEmpty()) {
                int xp = 0;
                if (isEnchanted(input)) {
                    xp = calcItemXp(input);
                } else {
                    Identifier id = BuiltInRegistries.ITEM.getKey(input.getItem());
                    if (id != null) {
                        xp = CelluloserConfig.EXTRA_SOURCES.getOrDefault(id, 0);
                    }
                }

                boolean disassemble = isDisassemblableItem(input);

                if (xp > 0 || disassemble) {
                    List<ItemStack> mats = List.of();
                    if (disassemble) {
                        float survivalProb = input.getMaxDamage() > 0
                            ? 1.0f - (float) input.getDamageValue() / input.getMaxDamage()
                            : 1.0f;
                        if (level.getRandom().nextFloat() < survivalProb) {
                            mats = be.getDisassemblyMaterials(input.getItem(), level);
                        }
                    }

                    if (xp == 0 && mats.isEmpty()) {
                        // Nothing to produce — leave item alone
                    } else if (!mats.isEmpty() && !be.canFitMaterials(mats)) {
                        // Output slots full — pause until space opens
                    } else {
                        input.shrink(1);
                        if (xp > 0) {
                            be.remainingXp = xp;
                            be.totalXp     = xp;
                        }
                        if (!mats.isEmpty()) be.placeMaterials(mats);
                        changed = true;
                    }
                }
            }
        }

        // Process XP — pauses when resources are absent
        if (be.remainingXp > 0) {
            int tickXp   = Math.min(be.remainingXp, XP_PER_TICK);
            int cellOut  = tickXp / OUTPUT_DIVISOR;
            int wNeeded  = cellOut * WATER_PER_MB;
            int feNeeded = cellOut * FE_PER_MB;

            boolean hasEnergy = be.energyHandler.getAmountAsInt() >= feNeeded;
            boolean hasWater  = be.fluidHandler.getAmountAsLong(0) >= wNeeded;
            long cellSpace    = TANK_CAPACITY - be.fluidHandler.getAmountAsLong(1);
            boolean hasSpace  = cellOut == 0 || cellSpace >= cellOut;

            if (hasEnergy && hasWater && hasSpace) {
                var waterRes = FluidResource.of(Fluids.WATER);
                var cellRes  = FluidResource.of(WnirRegistries.MAGIC_CELLULOSE_STILL.get());

                try (var tx = Transaction.openRoot()) {
                    be.energyHandler.extract(feNeeded, tx);
                    be.fluidHandler.extract(0, waterRes, wNeeded, tx);
                    be.fluidHandler.set(1, cellRes, (int)(be.fluidHandler.getAmountAsLong(1) + cellOut));
                    tx.commit();
                }
                be.remainingXp -= tickXp;
                changed = true;
            }
        }

        if (changed) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ── Disassembly ──────────────────────────────────────────────────────────

    /**
     * Returns cached material list for disassembling the given item.
     * Each call returns a snapshot copy — callers must not mutate the stacks.
     */
    List<ItemStack> getDisassemblyMaterials(Item item, Level level) {
        return disassemblyCache.computeIfAbsent(item, k ->
            resolveRecipe(k, level, new HashSet<>())
                .stream().map(ItemStack::copy).toList()
        );
    }

    /**
     * Recursively resolves disassembly materials via recipe lookup.
     *
     * Smithing recipe (base + template + addition → result):
     *   returns materials of base item (recursive) + the addition ingredient (netherite ingot).
     *   Template is excluded — it is consumed in smithing but returned by the table.
     *
     * Crafting recipe:
     *   skipped if any ingredient slot contains an item with EQUIPPABLE component (repair/upgrade recipes).
     *   Otherwise returns merged ingredient list (first option per slot, counts summed by item type).
     */
    private static List<ItemStack> resolveRecipe(Item target, Level level, Set<Item> visited) {
        if (!visited.add(target)) return List.of(); // cycle guard

        RecipeManager rm = ((ServerLevel) level).recipeAccess();

        // ── Smithing recipes ─────────────────────────────────────────────────
        // assemble() takes only the input (no HolderLookup.Provider in 1.21.11)
        Collection<RecipeHolder<SmithingRecipe>> smithingRecipes = rm.recipeMap().byType(RecipeType.SMITHING);
        for (RecipeHolder<SmithingRecipe> holder : smithingRecipes) {
            if (!(holder.value() instanceof SmithingTransformRecipe recipe)) continue;

            // Get first base item option; skip recipe if none
            var baseFirst = recipe.baseIngredient().items().findFirst();
            if (baseFirst.isEmpty()) continue;
            ItemStack testBase = new ItemStack(baseFirst.get().value());

            ItemStack testTemplate = recipe.templateIngredient()
                .flatMap(ing -> ing.items().findFirst().map(h -> new ItemStack(h.value())))
                .orElse(ItemStack.EMPTY);
            ItemStack testAddition = recipe.additionIngredient()
                .flatMap(ing -> ing.items().findFirst().map(h -> new ItemStack(h.value())))
                .orElse(ItemStack.EMPTY);

            ItemStack result = recipe.assemble(new SmithingRecipeInput(testTemplate, testBase, testAddition));
            if (result.isEmpty() || result.getItem() != target) continue;

            // Found: recurse into base armor, then add the addition (netherite ingot)
            List<ItemStack> mats = new ArrayList<>(resolveRecipe(testBase.getItem(), level, visited));
            recipe.additionIngredient().ifPresent(addIng ->
                addIng.items().findFirst().ifPresent(h -> mats.add(new ItemStack(h.value(), 1)))
            );
            return mats;
        }

        // ── Crafting recipes ─────────────────────────────────────────────────
        // ShapedRecipe/ShapelessRecipe.assemble ignores input and returns stored result copy
        Collection<RecipeHolder<CraftingRecipe>> craftingRecipes = rm.recipeMap().byType(RecipeType.CRAFTING);
        for (RecipeHolder<CraftingRecipe> holder : craftingRecipes) {
            CraftingRecipe recipe = holder.value();

            ItemStack result = recipe.assemble(CraftingInput.EMPTY);
            if (result.isEmpty() || result.getItem() != target) continue;

            // Skip repair/upgrade recipes that contain equipped-slot items as ingredients
            List<Ingredient> ings = recipe.placementInfo().ingredients();
            boolean hasEquippableIng = ings.stream().anyMatch(ing ->
                ing.items().anyMatch(h -> new ItemStack(h.value()).has(DataComponents.EQUIPPABLE))
            );
            if (hasEquippableIng) continue;

            // Merge ingredient counts by item type (take first option per slot)
            Map<Item, Integer> counts = new LinkedHashMap<>();
            for (Ingredient ing : ings) {
                ing.items().findFirst().map(Holder::value).ifPresent(item ->
                    counts.merge(item, 1, Integer::sum)
                );
            }

            List<ItemStack> mats = new ArrayList<>();
            for (var e : counts.entrySet()) {
                mats.add(new ItemStack(e.getKey(), e.getValue()));
            }
            return mats;
        }

        return List.of();
    }

    /** Returns true if all materials can fit into output slots 1–OUTPUT_SLOTS. */
    private boolean canFitMaterials(List<ItemStack> materials) {
        return fitMaterials(materials, false);
    }

    /** Places materials into output slots 1–OUTPUT_SLOTS, filling existing stacks first. */
    private void placeMaterials(List<ItemStack> materials) {
        fitMaterials(materials, true);
        // setChanged() is called by the serverTick caller via changed=true
    }

    /**
     * Two-pass slot fill: merge into existing stacks first, then fill empty slots.
     * When commit=false, operates on temporary counts (dry run — returns false if any material
     * doesn't fit). When commit=true, writes directly to the items list and always returns true.
     */
    private boolean fitMaterials(List<ItemStack> materials, boolean commit) {
        Item[] slotItems  = new Item[OUTPUT_SLOTS];
        int[]  slotCounts = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemStack s = items.get(i + 1);
            if (!s.isEmpty()) { slotItems[i] = s.getItem(); slotCounts[i] = s.getCount(); }
        }
        for (ItemStack mat : materials) {
            int max = mat.getMaxStackSize();
            int remaining = mat.getCount();
            for (int i = 0; i < OUTPUT_SLOTS && remaining > 0; i++) {
                if (slotItems[i] == mat.getItem()) {
                    int add = Math.min(max - slotCounts[i], remaining);
                    slotCounts[i] += add;
                    remaining -= add;
                }
            }
            for (int i = 0; i < OUTPUT_SLOTS && remaining > 0; i++) {
                if (slotItems[i] == null) {
                    slotItems[i]  = mat.getItem();
                    slotCounts[i] = Math.min(max, remaining);
                    remaining    -= slotCounts[i];
                }
            }
            if (remaining > 0) return false;
        }
        if (commit) {
            for (int i = 0; i < OUTPUT_SLOTS; i++) {
                if (slotItems[i] == null) {
                    items.set(i + 1, ItemStack.EMPTY);
                } else {
                    items.set(i + 1, new ItemStack(slotItems[i], slotCounts[i]));
                }
            }
        }
        return true;
    }

    // ── XP calculation ───────────────────────────────────────────────────────

    static int calcItemXp(ItemStack stack) {
        int total = 0;
        for (var enc : new ItemEnchantments[]{
            stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY),
            stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY),
        }) {
            for (var entry : enc.entrySet()) {
                var ench = entry.getKey().value();
                int lvl = entry.getValue();
                int meanLevel = (ench.getMinCost(lvl) + ench.getMaxCost(lvl)) / 2;
                total += levelToXp(meanLevel);
            }
        }
        return total;
    }

    static int levelToXp(int n) {
        if (n <= 0)  return 0;
        if (n <= 16) return n * n + 6 * n;
        if (n <= 31) return Math.round(2.5f * n * n - 40.5f * n + 360);
        return Math.round(4.5f * n * n - 162.5f * n + 2220);
    }
}
