package com.wnir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches LLM tool calls to server-thread game actions.
 * All game-state mutations run on the server thread via server.execute(),
 * with the LLM thread blocking via CompletableFuture.get().
 */
public final class WeddingRingLlmTools {

    private static final int TOOL_RANGE       = 8;
    private static final int CRAFT_TABLE_RANGE = 4;

    private WeddingRingLlmTools() {}

    /**
     * Execute a tool call on the server thread and return the result string.
     * Called from the LLM executor thread; blocks until the server thread completes.
     */
    public static String execute(MinecraftServer server, UUID petUUID, String toolName, String argsJson) {
        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(dispatch(server, petUUID, toolName, argsJson));
            } catch (Exception e) {
                future.complete("error: " + e.getMessage());
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "error: server thread timeout";
        }
    }

    private static String dispatch(MinecraftServer server, UUID petUUID, String toolName, String argsJson) {
        Mob pet = findPet(server, petUUID);
        if (pet == null) return "error: pet not found";

        WeddingRingData data = WeddingRingData.get(pet);
        if (data == null) return "error: pet not bound";

        JsonObject args;
        try { args = JsonParser.parseString(argsJson).getAsJsonObject(); }
        catch (Exception e) { args = new JsonObject(); }

        return switch (toolName) {
            case "say"       -> toolSay(server, pet, data, args);
            case "remember"  -> toolRemember(data, args);
            case "plan"      -> toolPlan(data, args);
            case "todo"      -> toolTodo(data);
            case "item_info" -> toolItemInfo(args);
            case "craft"     -> toolCraft(pet, data, args);
            case "nearest"   -> toolNearest(pet, args);
            case "inspect"   -> toolInspect(pet, args);
            case "inventory" -> toolInventory(pet, data);
            case "put"       -> toolPut(pet, data, args);
            case "get"       -> toolGet(pet, data, args);
            case "goto"      -> toolGoto(pet, args);
            case "stats"     -> toolStats(pet, data);
            case "equip"     -> toolEquip(pet, data, args);
            case "place"     -> toolPlace(pet, data, args);
            case "done"      -> toolDone(data, args);
            case "think"     -> "ok";
            default          -> "error: unknown tool " + toolName;
        };
    }

    // ── Tool implementations ─────────────────────────────────────────────────

    private static String toolSay(MinecraftServer server, Mob pet, WeddingRingData data, JsonObject args) {
        String text = stringArg(args, "text", "");
        if (text.isEmpty()) return "error: text required";
        String msg = pet.getDisplayName().getString() + ": " + text;
        server.getPlayerList().broadcastSystemMessage(
            net.minecraft.network.chat.Component.literal(msg).withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        return "ok";
    }

    private static String toolRemember(WeddingRingData data, JsonObject args) {
        String text = stringArg(args, "text", "");
        if (text.isEmpty()) return "error: text required";
        List<String> mem = new ArrayList<>(data.getLlmMemory());
        mem.add(text);
        data.setLlmMemory(mem);
        return "ok";
    }

    private static String toolDone(WeddingRingData data, JsonObject args) {
        int index = intArg(args, "index", -1);
        if (index < 1) return "error: index required (1-based)";
        List<String> todo = new ArrayList<>(data.getLlmTodo());
        if (index > todo.size()) return "error: index " + index + " out of range (have " + todo.size() + ")";
        String removed = todo.remove(index - 1);
        data.setLlmTodo(todo);
        return "completed: " + removed;
    }

    private static String toolPlan(WeddingRingData data, JsonObject args) {
        String text = stringArg(args, "text", "");
        if (text.isEmpty()) return "error: text required";
        List<String> todo = new ArrayList<>(data.getLlmTodo());
        todo.add(text);
        data.setLlmTodo(todo);
        return "ok";
    }

    private static String toolTodo(WeddingRingData data) {
        List<String> todo = data.getLlmTodo();
        if (todo.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < todo.size(); i++) {
            sb.append(i + 1).append(". ").append(todo.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private static String toolItemInfo(JsonObject args) {
        String name = stringArg(args, "item_name", "");
        if (name.isEmpty()) return "error: item_name required";
        Identifier id = Identifier.tryParse(name);
        if (id == null) return "error: invalid identifier " + name;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return "unknown item: " + name;
        ItemStack stack = new ItemStack(item);
        return "Name: " + stack.getHoverName().getString()
             + " | max stack: " + item.getDefaultMaxStackSize();
    }

    private static String toolCraft(Mob pet, WeddingRingData data, JsonObject args) {
        String name = stringArg(args, "item_name", "");
        if (name.isEmpty()) return "error: item_name required";
        Identifier id = Identifier.tryParse(name);
        if (id == null) return "error: invalid identifier";
        Item targetItem = BuiltInRegistries.ITEM.getValue(id);
        if (targetItem == null || targetItem == net.minecraft.world.item.Items.AIR)
            return "unknown item: " + name;

        ServerLevel level = (ServerLevel) pet.level();

        // Find a crafting recipe that outputs this item
        CraftingRecipe found = null;
        for (RecipeHolder<CraftingRecipe> holder : level.recipeAccess().recipeMap().byType(RecipeType.CRAFTING)) {
            ItemStack result;
            try { result = holder.value().assemble(CraftingInput.EMPTY); }
            catch (Exception e) { continue; }
            if (result == null || result.isEmpty()) continue;
            if (result.getItem() == targetItem) { found = holder.value(); break; }
        }
        if (found == null) return "no crafting recipe for " + name;

        // Count required ingredients
        List<Ingredient> ings = found.placementInfo().ingredients();
        Map<Item, Integer> required = new LinkedHashMap<>();
        for (Ingredient ing : ings) {
            if (ing.isEmpty()) continue;
            ing.items().findFirst().map(h -> h.value())
                .ifPresent(item -> required.merge(item, 1, Integer::sum));
        }

        // Check if pet has all ingredients
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : required.entrySet()) {
            int have = countInStorage(data, e.getKey());
            if (have < e.getValue()) {
                missing.add(BuiltInRegistries.ITEM.getKey(e.getKey())
                    + " x" + (e.getValue() - have) + " (have " + have + ")");
            }
        }
        if (!missing.isEmpty()) return "missing: " + String.join(", ", missing);

        // Check crafting table if recipe needs more than 4 ingredients
        long nonEmpty = ings.stream().filter(i -> !i.isEmpty()).count();
        if (nonEmpty > 4) {
            boolean tableNearby = hasCraftingTableNearby(pet, level);
            if (!tableNearby) return "error: crafting table required within " + CRAFT_TABLE_RANGE + " blocks";
        }

        // Consume ingredients and craft
        for (Map.Entry<Item, Integer> e : required.entrySet()) {
            consumeFromStorage(data, e.getKey(), e.getValue());
        }
        ItemStack result;
        try { result = found.assemble(CraftingInput.EMPTY); }
        catch (Exception e) { return "error: craft failed: " + e.getMessage(); }
        if (result.isEmpty()) return "error: empty craft result";

        // Place result in first available storage slot
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            if (data.getStdSlot(i).isEmpty()) {
                data.setStdSlot(i, result);
                return "ok: crafted " + result.getHoverName().getString();
            }
        }
        return "error: no storage space for result";
    }

    private static String toolNearest(Mob pet, JsonObject args) {
        String blockName = stringArg(args, "block_name", "");
        int count = intArg(args, "count", 5);
        if (blockName.isEmpty()) return "error: block_name required";

        ServerLevel level = (ServerLevel) pet.level();
        BlockPos petPos = pet.blockPosition();
        int range = TOOL_RANGE;

        List<String> results = new ArrayList<>();
        for (int dx = -range; dx <= range && results.size() < count; dx++) {
            for (int dy = -range; dy <= range && results.size() < count; dy++) {
                for (int dz = -range; dz <= range && results.size() < count; dz++) {
                    BlockPos pos = petPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    String regPath = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (!regPath.contains(blockName)) continue;
                    // Must be reachable (adjacent non-solid face)
                    boolean reachable = false;
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                        if (!level.getBlockState(pos.relative(dir)).isSolid()) { reachable = true; break; }
                    }
                    if (!reachable) continue;
                    double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                    String cardinal = WeddingRingLlmHandler.cardinal(dx, dz);
                    String regFull = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    results.add("%s at (%d, %d, %d) [%.1fm %s]".formatted(
                        regFull, pos.getX(), pos.getY(), pos.getZ(), dist, cardinal));
                }
            }
        }
        return results.isEmpty() ? "none found" : String.join("\n", results);
    }

    private static String toolInspect(Mob pet, JsonObject args) {
        BlockPos pos = blockPosArg(args);
        if (pos == null) return "error: x, y, z required";
        if (pet.blockPosition().distSqr(pos) > TOOL_RANGE * TOOL_RANGE)
            return "error: too far (max " + TOOL_RANGE + " blocks)";

        ServerLevel level = (ServerLevel) pet.level();
        var be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return "error: no container at " + posStr(pos);

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                sb.append("  ").append(s.getHoverName().getString())
                    .append(" x").append(s.getCount()).append("\n");
                shown++;
            }
        }
        return shown == 0 ? "(empty)" : sb.toString().trim();
    }

    private static String toolInventory(Mob pet, WeddingRingData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Weapon: ").append(stackStr(data.getWeapon())).append("\n");
        sb.append("Shield: ").append(stackStr(data.getShield())).append("\n");
        if (data.hasArmor()) {
            sb.append("Head: ").append(stackStr(data.getArmorHead())).append("\n");
            sb.append("Chest: ").append(stackStr(data.getArmorChest())).append("\n");
            sb.append("Legs: ").append(stackStr(data.getArmorLegs())).append("\n");
            sb.append("Feet: ").append(stackStr(data.getArmorFeet())).append("\n");
        }
        sb.append("Healing I: ").append(data.getHealingCount1()).append(", II: ").append(data.getHealingCount2()).append("\n");
        sb.append("Storage:\n");
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            ItemStack s = data.getStdSlot(i);
            if (!s.isEmpty()) {
                sb.append("  [").append(i).append("] ")
                    .append(s.getHoverName().getString())
                    .append(" x").append(s.getCount()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String toolPut(Mob pet, WeddingRingData data, JsonObject args) {
        BlockPos pos = blockPosArg(args);
        if (pos == null) return "error: x, y, z required";
        if (pet.blockPosition().distSqr(pos) > TOOL_RANGE * TOOL_RANGE)
            return "error: too far";
        String itemName = stringArg(args, "item_name", "");
        int count = intArg(args, "count", 1);
        if (itemName.isEmpty()) return "error: item_name required";

        Identifier id = Identifier.tryParse(itemName);
        if (id == null) return "error: invalid identifier";
        Item item = BuiltInRegistries.ITEM.getValue(id);

        ServerLevel level = (ServerLevel) pet.level();
        var be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return "error: no container at " + posStr(pos);

        int moved = 0;
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT && moved < count; i++) {
            ItemStack s = data.getStdSlot(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int toMove = Math.min(s.getCount(), count - moved);
            // Try inserting into container
            int inserted = insertIntoContainer(container, s.copyWithCount(toMove));
            if (inserted > 0) {
                moved += inserted;
                if (inserted >= s.getCount()) data.setStdSlot(i, ItemStack.EMPTY);
                else data.setStdSlot(i, s.copyWithCount(s.getCount() - inserted));
            }
        }
        return "moved " + moved;
    }

    private static String toolGet(Mob pet, WeddingRingData data, JsonObject args) {
        BlockPos pos = blockPosArg(args);
        if (pos == null) return "error: x, y, z required";
        if (pet.blockPosition().distSqr(pos) > TOOL_RANGE * TOOL_RANGE)
            return "error: too far";
        String itemName = stringArg(args, "item_name", "");
        int count = intArg(args, "count", 1);
        if (itemName.isEmpty()) return "error: item_name required";

        Identifier id = Identifier.tryParse(itemName);
        if (id == null) return "error: invalid identifier";
        Item item = BuiltInRegistries.ITEM.getValue(id);

        ServerLevel level = (ServerLevel) pet.level();
        var be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return "error: no container at " + posStr(pos);

        int moved = 0;
        for (int i = 0; i < container.getContainerSize() && moved < count; i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int toTake = Math.min(s.getCount(), count - moved);
            // Find free storage slot
            int slot = findFreeStorageSlot(data, item);
            if (slot < 0) break;
            ItemStack existing = data.getStdSlot(slot);
            int canFit = item.getDefaultMaxStackSize() - existing.getCount();
            int actual = Math.min(toTake, canFit);
            if (actual <= 0) continue;
            if (existing.isEmpty()) data.setStdSlot(slot, s.copyWithCount(actual));
            else existing.grow(actual);
            container.removeItem(i, actual);
            moved += actual;
        }
        return "moved " + moved;
    }

    private static String toolStats(Mob pet, WeddingRingData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Health: ").append(String.format("%.1f/%.1f", pet.getHealth(), pet.getMaxHealth())).append("\n");
        sb.append("Hunger: ").append(data.getFoodLevel()).append("/20\n");
        appendAttr(sb, "Max health",          pet, Attributes.MAX_HEALTH);
        appendAttr(sb, "Attack damage",       pet, Attributes.ATTACK_DAMAGE);
        appendAttr(sb, "Attack speed",        pet, Attributes.ATTACK_SPEED);
        appendAttr(sb, "Movement speed",      pet, Attributes.MOVEMENT_SPEED);
        appendAttr(sb, "Armor",               pet, Attributes.ARMOR);
        appendAttr(sb, "Armor toughness",     pet, Attributes.ARMOR_TOUGHNESS);
        appendAttr(sb, "Knockback resistance",pet, Attributes.KNOCKBACK_RESISTANCE);
        appendAttr(sb, "Follow range",        pet, Attributes.FOLLOW_RANGE);
        return sb.toString().trim();
    }

    private static void appendAttr(StringBuilder sb, String label, Mob pet,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        var instance = pet.getAttribute(attr);
        if (instance != null) sb.append(label).append(": ")
            .append(String.format("%.2f", instance.getValue())).append("\n");
    }

    private static String toolEquip(Mob pet, WeddingRingData data, JsonObject args) {
        String itemName = stringArg(args, "item_name", "");
        if (itemName.isEmpty()) return "error: item_name required";

        Identifier id = Identifier.tryParse(itemName);
        if (id == null) return "error: invalid identifier";
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return "unknown item: " + itemName;

        // Find in storage
        int storageSlot = -1;
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            ItemStack s = data.getStdSlot(i);
            if (!s.isEmpty() && s.getItem() == item) { storageSlot = i; found = s; break; }
        }
        if (storageSlot < 0) return "error: " + itemName + " not in storage";

        // Broken item check
        if (found.getMaxDamage() > 0 && found.getDamageValue() >= found.getMaxDamage()) {
            return "broken: " + found.getHoverName().getString() + " is broken (0 durability) — cannot equip";
        }

        // Detect which ring slot this item belongs to
        String slotName = detectSlot(found, data);
        if (slotName == null) return "error: cannot determine equipment slot for " + itemName;

        // Swap: move current occupant back to the storage slot the new item came from
        ItemStack current = getSlot(data, slotName);
        data.setStdSlot(storageSlot, current); // empty or previous item
        setSlot(data, slotName, found);

        WeddingRingAttributeManager.recalculate(pet);
        String result = "equipped " + found.getHoverName().getString() + " → " + slotName;
        if (!current.isEmpty()) result += " (returned " + current.getHoverName().getString() + " to storage)";
        return result;
    }

    private static String detectSlot(ItemStack s, WeddingRingData data) {
        if (s.has(DataComponents.WEAPON) || s.has(DataComponents.KINETIC_WEAPON)
                || s.getItem() instanceof MaceItem || s.getItem() instanceof AxeItem) return "weapon";
        if (s.has(DataComponents.BLOCKS_ATTACKS)) return "shield";
        var eq = s.get(DataComponents.EQUIPPABLE);
        if (eq == null) return null;
        return switch (eq.slot()) {
            case HEAD  -> data.hasArmor() ? "helmet"     : null;
            case CHEST -> data.hasArmor() ? "chestplate" : null;
            case LEGS  -> data.hasArmor() ? "leggings"   : null;
            case FEET  -> data.hasArmor() ? "boots"      : null;
            default    -> null;
        };
    }

    private static ItemStack getSlot(WeddingRingData data, String slot) {
        return switch (slot) {
            case "weapon"     -> data.getWeapon();
            case "shield"     -> data.getShield();
            case "helmet"     -> data.getArmorHead();
            case "chestplate" -> data.getArmorChest();
            case "leggings"   -> data.getArmorLegs();
            case "boots"      -> data.getArmorFeet();
            default           -> ItemStack.EMPTY;
        };
    }

    private static void setSlot(WeddingRingData data, String slot, ItemStack s) {
        switch (slot) {
            case "weapon"     -> data.setWeapon(s);
            case "shield"     -> data.setShield(s);
            case "helmet"     -> data.setArmorHead(s);
            case "chestplate" -> data.setArmorChest(s);
            case "leggings"   -> data.setArmorLegs(s);
            case "boots"      -> data.setArmorFeet(s);
        }
    }

    private static String toolPlace(Mob pet, WeddingRingData data, JsonObject args) {
        BlockPos pos = blockPosArg(args);
        if (pos == null) return "error: x, y, z required";
        if (pet.blockPosition().distSqr(pos) > TOOL_RANGE * TOOL_RANGE)
            return "error: too far";

        String itemName = stringArg(args, "item_name", "");
        if (itemName.isEmpty()) return "error: item_name required";

        Identifier id = Identifier.tryParse(itemName);
        if (id == null) return "error: invalid identifier";
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return "unknown item: " + itemName;
        if (!(item instanceof net.minecraft.world.item.BlockItem blockItem))
            return "error: " + itemName + " is not a placeable block";

        if (countInStorage(data, item) == 0) return "error: " + itemName + " not in storage";

        ServerLevel level = (ServerLevel) pet.level();
        if (!level.getBlockState(pos).canBeReplaced()) return "error: position is occupied";

        level.setBlockAndUpdate(pos, blockItem.getBlock().defaultBlockState());
        consumeFromStorage(data, item, 1);
        return "placed " + blockItem.getBlock().getName().getString() + " at " + posStr(pos);
    }

    private static String toolGoto(Mob pet, JsonObject args) {
        int x = intArg(args, "x", Integer.MIN_VALUE);
        int y = intArg(args, "y", Integer.MIN_VALUE);
        int z = intArg(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE)
            return "error: x, y, z required";
        boolean ok = pet.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0);
        return ok ? "moving" : "unreachable";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Mob findPet(MinecraftServer server, UUID petUUID) {
        for (ServerLevel level : server.getAllLevels()) {
            var e = level.getEntity(petUUID);
            if (e instanceof Mob mob) return mob;
        }
        return null;
    }

    private static int countInStorage(WeddingRingData data, Item item) {
        int total = 0;
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            ItemStack s = data.getStdSlot(i);
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    private static void consumeFromStorage(WeddingRingData data, Item item, int count) {
        int remaining = count;
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT && remaining > 0; i++) {
            ItemStack s = data.getStdSlot(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(s.getCount(), remaining);
            remaining -= take;
            data.setStdSlot(i, s.getCount() > take ? s.copyWithCount(s.getCount() - take) : ItemStack.EMPTY);
        }
    }

    private static int findFreeStorageSlot(WeddingRingData data, Item item) {
        // Prefer partial stack of same item first
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            ItemStack s = data.getStdSlot(i);
            if (!s.isEmpty() && s.getItem() == item && s.getCount() < item.getDefaultMaxStackSize()) return i;
        }
        for (int i = 0; i < WeddingRingMenu.STD_SLOT_COUNT; i++) {
            if (data.getStdSlot(i).isEmpty()) return i;
        }
        return -1;
    }

    private static int insertIntoContainer(Container container, ItemStack stack) {
        int inserted = 0;
        for (int i = 0; i < container.getContainerSize() && inserted < stack.getCount(); i++) {
            ItemStack slot = container.getItem(i);
            if (!container.canPlaceItem(i, stack)) continue;
            if (slot.isEmpty()) {
                int take = Math.min(stack.getCount() - inserted, stack.getMaxStackSize());
                container.setItem(i, stack.copyWithCount(take));
                inserted += take;
            } else if (ItemStack.isSameItemSameComponents(slot, stack)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int take = Math.min(space, stack.getCount() - inserted);
                slot.grow(take);
                inserted += take;
            }
        }
        return inserted;
    }

    private static boolean hasCraftingTableNearby(Mob pet, ServerLevel level) {
        BlockPos petPos = pet.blockPosition();
        for (int dx = -CRAFT_TABLE_RANGE; dx <= CRAFT_TABLE_RANGE; dx++) {
            for (int dy = -CRAFT_TABLE_RANGE; dy <= CRAFT_TABLE_RANGE; dy++) {
                for (int dz = -CRAFT_TABLE_RANGE; dz <= CRAFT_TABLE_RANGE; dz++) {
                    BlockPos p = petPos.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock)
                        return true;
                }
            }
        }
        return false;
    }

    private static String stackStr(ItemStack s) {
        return s.isEmpty() ? "(empty)" : s.getHoverName().getString() + " x" + s.getCount();
    }

    private static String posStr(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String stringArg(JsonObject args, String key, String def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : def;
    }

    private static int intArg(JsonObject args, String key, int def) {
        try { return args.has(key) ? args.get(key).getAsInt() : def; }
        catch (Exception e) { return def; }
    }

    private static BlockPos blockPosArg(JsonObject args) {
        try {
            int x = intArg(args, "x", Integer.MIN_VALUE);
            int y = intArg(args, "y", Integer.MIN_VALUE);
            int z = intArg(args, "z", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) return null;
            return new BlockPos(x, y, z);
        } catch (Exception e) { return null; }
    }
}
