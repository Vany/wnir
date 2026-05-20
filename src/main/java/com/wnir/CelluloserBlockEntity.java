package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
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
public class CelluloserBlockEntity extends BlockEntity implements WorldlyContainer, net.minecraft.world.MenuProvider {

    // ── Configurable parameters ──────────────────────────────────────────────
    public static final int XP_PER_TICK     = 200;
    public static final int FE_PER_MB       = 10;   // FE consumed per mB of cellulose produced
    public static final int WATER_PER_MB    = 1;    // mB of water consumed per mB of cellulose produced
    public static final int OUTPUT_DIVISOR  = 10;
    public static final int TANK_CAPACITY   = 16_000;
    public static final int ENERGY_CAPACITY = 1_000_000;

    // ── State ────────────────────────────────────────────────────────────────

    static final int OUTPUT_SLOTS   = 9; // slots 1..OUTPUT_SLOTS are disassembly output
    static final int TOTAL_SLOTS    = 1 + OUTPUT_SLOTS;
    static final int DISASSEMBLY_XP = 80 * XP_PER_TICK; // extra processing time added per disassembly

    // Slot 0 = input; slots 1–9 = disassembly output
    private NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private int remainingXp = 0;
    private int totalXp     = 0;

    // Materials queued from disassembly — deposited into output slots once processing completes.
    private List<ItemStack> pendingMaterials = List.of();

    // Recipe → all possible material lists cache. Populated lazily on first use.
    private final Map<Item, List<List<ItemStack>>> disassemblyCache = new HashMap<>();

    // Sticky recipe selection: once a recipe index is chosen for a run, keep it until the
    // item is consumed (avoids re-rolling every tick when output slots are full).
    private int  chosenRecipeIndex = -1;
    private Item chosenRecipeItem  = null;

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
        NonNullList<ItemStack> pendingList = NonNullList.withSize(OUTPUT_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input.childOrEmpty("Pending"), pendingList);
        pendingMaterials = pendingList.stream().filter(s -> !s.isEmpty()).toList();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items, false);
        energyHandler.serialize(output.child("Energy"));
        fluidHandler.serialize(output.child("Fluids"));
        output.putInt("RemainingXp", remainingXp);
        output.putInt("TotalXp",     totalXp);
        if (!pendingMaterials.isEmpty()) {
            NonNullList<ItemStack> pendingList = NonNullList.withSize(OUTPUT_SLOTS, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(pendingMaterials.size(), OUTPUT_SLOTS); i++) {
                pendingList.set(i, pendingMaterials.get(i));
            }
            ContainerHelper.saveAllItems(output.child("Pending"), pendingList, false);
        }
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
        if (slot != 0) return false;
        return isEnchanted(stack) || isConfigSource(stack) || isDisassemblableItem(stack);
    }

    // ── WorldlyContainer ─────────────────────────────────────────────────────

    private static final int[] ALL_SLOTS;
    private static final int[] OUTPUT_SLOT_IDS;
    static {
        ALL_SLOTS = new int[TOTAL_SLOTS];
        for (int i = 0; i < TOTAL_SLOTS; i++) ALL_SLOTS[i] = i;
        OUTPUT_SLOT_IDS = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) OUTPUT_SLOT_IDS[i] = i + 1;
    }

    @Override
    public int[] getSlotsForFace(Direction side) { return ALL_SLOTS; }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @org.jspecify.annotations.Nullable Direction dir) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return slot != 0; // only output slots 1-9 are extractable
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

        // Deposit pending materials once XP processing completes
        if (be.remainingXp == 0 && !be.pendingMaterials.isEmpty()) {
            if (be.canFitMaterials(be.pendingMaterials)) {
                be.fitMaterials(be.pendingMaterials, true);
                be.pendingMaterials = List.of();
                changed = true;
            }
            // output full — stall until space opens
        }

        // Consume item from slot 0 when fully idle (no XP left, no pending materials)
        if (be.remainingXp == 0 && be.pendingMaterials.isEmpty()) {
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
                // Remaining-health fraction: 1.0 = pristine, 0.0 = fully broken.
                // Scales processing time for disassembly.
                float survivalProb = (disassemble && input.getMaxDamage() > 0)
                    ? 1.0f - (float) input.getDamageValue() / input.getMaxDamage()
                    : 1.0f;

                if (xp > 0 || disassemble) {
                    List<ItemStack> mats = List.of();
                    boolean hasAnyRecipe = false;

                    if (disassemble) {
                        Item inputItem = input.getItem();
                        // Reset sticky index if a different item entered slot 0
                        if (inputItem != be.chosenRecipeItem) {
                            be.chosenRecipeIndex = -1;
                            be.chosenRecipeItem  = inputItem;
                        }
                        List<List<ItemStack>> allRecipes = be.getAllDisassemblyRecipes(inputItem, level);
                        hasAnyRecipe = !allRecipes.isEmpty();
                        if (hasAnyRecipe && level.getRandom().nextFloat() < survivalProb) {
                            if (be.chosenRecipeIndex < 0) {
                                be.chosenRecipeIndex = level.getRandom().nextInt(allRecipes.size());
                            }
                            mats = allRecipes.get(be.chosenRecipeIndex);
                        }
                    }

                    int scaledXp = Math.max(1, (int)(DISASSEMBLY_XP * survivalProb));

                    boolean canProcess = (xp > 0 || !mats.isEmpty())
                        && (mats.isEmpty() || be.canFitMaterials(mats));
                    if (canProcess) {
                        input.shrink(1);
                        be.chosenRecipeIndex = -1;
                        be.chosenRecipeItem  = null;
                        int totalXp = xp + (!mats.isEmpty() ? scaledXp : 0);
                        if (totalXp > 0) {
                            be.remainingXp = totalXp;
                            be.totalXp     = totalXp;
                        }
                        if (!mats.isEmpty()) be.pendingMaterials = List.copyOf(mats);
                        changed = true;
                    } else if (xp == 0 && (!hasAnyRecipe || survivalProb <= 0)) {
                        // No recipe, or fully broken (survival can never pass) — pass through
                        if (be.passThrough(input)) changed = true;
                    }
                } else {
                    // Accepted by canPlaceItem (e.g. config source with xp=0) but nothing to do
                    if (be.passThrough(input)) changed = true;
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

                // Use set() for all internal modifications — extract() is rate-limited
                // (maxExtract=0 for energy; slot-0 extract overridden to 0 for fluid)
                be.energyHandler.set(be.energyHandler.getAmountAsInt() - feNeeded);
                be.fluidHandler.set(0, waterRes, (int)(be.fluidHandler.getAmountAsLong(0)) - wNeeded);
                be.fluidHandler.set(1, cellRes,  (int)(be.fluidHandler.getAmountAsLong(1)) + cellOut);
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
     * Returns all possible material lists for disassembling the given item (cached).
     * Each inner list is a snapshot — callers must not mutate the stacks.
     */
    List<List<ItemStack>> getAllDisassemblyRecipes(Item item, Level level) {
        return disassemblyCache.computeIfAbsent(item, k -> resolveAllRecipes(k, level, new HashSet<>()));
    }

    /**
     * Collects every valid disassembly material list for {@code target}.
     *
     * Smithing recipes (base + template + addition → result):
     *   materials = recursive base materials + addition ingredient.
     *   Template excluded — the table returns it.
     *
     * Crafting recipes:
     *   skipped when any ingredient has EQUIPPABLE (repair/upgrade recipes).
     *   Each matching recipe becomes a separate entry in the result list.
     *
     * Returns an empty list when no recipe is found.
     */
    private static List<List<ItemStack>> resolveAllRecipes(Item target, Level level, Set<Item> visited) {
        if (!visited.add(target)) return List.of();

        RecipeManager rm = ((ServerLevel) level).recipeAccess();
        List<List<ItemStack>> results = new ArrayList<>();

        // ── Smithing recipes ─────────────────────────────────────────────────
        for (RecipeHolder<SmithingRecipe> holder : rm.recipeMap().byType(RecipeType.SMITHING)) {
            if (!(holder.value() instanceof SmithingTransformRecipe recipe)) continue;

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

            List<ItemStack> mats = new ArrayList<>(resolveRecipeFirst(testBase.getItem(), level, new HashSet<>(visited)));
            recipe.additionIngredient().ifPresent(addIng ->
                addIng.items().findFirst().ifPresent(h -> mats.add(new ItemStack(h.value(), 1)))
            );
            if (!mats.isEmpty()) results.add(mats);
        }

        // ── Crafting recipes ─────────────────────────────────────────────────
        for (RecipeHolder<CraftingRecipe> holder : rm.recipeMap().byType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();

            ItemStack result;
            try {
                result = recipe.assemble(CraftingInput.EMPTY);
            } catch (Exception e) {
                continue;
            }
            if (result == null || result.isEmpty() || result.getItem() != target) continue;

            List<Ingredient> ings = recipe.placementInfo().ingredients();
            boolean hasEquippableIng = ings.stream().anyMatch(ing ->
                ing.items().anyMatch(h -> new ItemStack(h.value()).has(DataComponents.EQUIPPABLE))
            );
            if (hasEquippableIng) continue;

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
            if (!mats.isEmpty()) results.add(mats);
        }

        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    /**
     * Returns the first matching material list for {@code target} — used for smithing base recursion.
     */
    private static List<ItemStack> resolveRecipeFirst(Item target, Level level, Set<Item> visited) {
        List<List<ItemStack>> all = resolveAllRecipes(target, level, visited);
        return all.isEmpty() ? List.of() : all.get(0);
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

    // ── Pass-through ─────────────────────────────────────────────────────────

    /** Moves one item from slot 0 into the first output slot that has room. Returns true if moved. */
    private boolean passThrough(ItemStack stack) {
        ItemStack single = stack.copyWithCount(1);
        for (int i = 1; i <= OUTPUT_SLOTS; i++) {
            ItemStack existing = items.get(i);
            if (existing.isEmpty()) {
                items.set(i, single);
                stack.shrink(1);
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, single)
                    && existing.getCount() < existing.getMaxStackSize()) {
                existing.grow(1);
                stack.shrink(1);
                return true;
            }
        }
        return false;
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
