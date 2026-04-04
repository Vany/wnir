# SPECS.md — When Nothing Is Ready (wnir)

- [~] Enchanted weapons in dungeon loot — REMOVED (loot modifier conditions broken in 1.21.11; enchants available at enchanting table only)
- [~] Enchantment books in dungeon loot — REMOVED (same reason)

---

## Overview

**WNIR** is a standalone NeoForge mod (1.21.11 only) that adds gameplay content: custom blocks, mob effects, enchantments, and a potion.

## Platform

| Parameter | Value |
|-----------|-------|
| Minecraft | 1.21.11 |
| Mod loader | NeoForge 21.11.38-beta |
| Java | 21+ |
| External deps | GeckoLib 5.4.5 (`geckolib-neoforge-1.21.11`) |
| Side | Both (server: blocks/effects, client: none) |
| Mod ID | `wnir` |
| Package | `com.wnir` |

---

## 1. Blocks

### Critical pattern for all blocks
Never do world access (`getBlockState`, `getBlockEntity`, `setChunkForced`) in `setRemoved()` — causes infinite loops during chunk unload. All cleanup goes in `playerWillDestroy()` on the Block class (block still in world at that point). For non-player removal (TNT, pistons), override `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` — block at pos is already air when this fires.

---

### Chunk Loader (`wnir:chunk_loader`)

Force-loads its chunk permanently. Positions survive server restarts via `ChunkLoaderData`.

| Event | Action |
|-------|--------|
| `onPlace` | `ChunkLoaderData.add(pos)` + `setChunkForced(true)` |
| `playerWillDestroy` | `ChunkLoaderData.remove(pos)` + `setChunkForced(false)` |
| Server start | `ChunkLoaderData.forceAll(level)` — re-force all saved positions |
| Server stop | `ChunkLoaderData.reset()` — clear singleton (chunks unforce naturally) |

**Acquisition:** no crafting recipe — found only in dungeon loot (Overworld structures).

**ChunkLoaderData:** plain text file `wnir_chunk_loaders_<dim>.txt` in world save directory. One `X Y Z` per line. Atomic save: write `.tmp` then `Files.move(ATOMIC_MOVE)`. Per-dimension instances, loaded lazily. Migration from legacy single file on first load.

---

### Spawner Agitator (`wnir:spawner_agitator`)

Column placed below vanilla mob spawners. Modifies all spawners above:
- Sets `requiredPlayerRange` to `32767` (always active; not -1 to avoid blocking server shutdown save loop)
- Scales `minSpawnDelay` and `maxSpawnDelay` down by agitator count

**Column layout:** `[agitators...][spawners...]` — agitators at bottom, spawners on top.

**Architecture — event-driven, no ticker:**

| Event | Action |
|-------|--------|
| `onPlace` | `notifyColumn()` → topmost agitator calls `bindSpawner()` |
| `playerWillDestroy` | `notifyColumnExcluding(removed)` → topmost calls `unbindSpawner()` |
| `onLoad` (BE) | `bindSpawner()` — resolves spawners on chunk load |
| `randomTick` | `recheckSpawners()` — periodic integrity check |

**`SpawnerAccessor`:** reflection-based access to `BaseSpawner` private fields. Resolves by name first, then probes by default value (16 / 20) as fallback. Cached in `static volatile` fields.

**NBT:** persists `SpawnerCount` + `OriginalRange_N`, `OriginalMinDelay_N`, `OriginalMaxDelay_N` so originals survive chunk reload.

**Acquisition:** no crafting recipe — found only in dungeon loot (Overworld structures).

**Known limitation:** spawner placed after agitator is only detected on chunk reload (`onLoad`). `neighborChanged` in 1.21.11 takes `Orientation` (not `BlockPos`), not currently overridden.

---

### Warding Column System

All post blocks share a single `WardingColumnBlockEntity`. The **bottom** block of the column owns the computed state and runs the tick. Each block type contributes differently:

| Block | Radius | Extra effect |
|-------|--------|--------------|
| `warding_post` | +6 | Pushes aggressive mobs outward |
| `repelling_post` | +4 | Adds radius only |
| `teleporter_inhibitor` | +4 | Cancels teleports in radius |
| `lighting_post` | +4 | Light level 15 (no active effect) |
| `hurt_post` | +4 | Magic damage to enemies: `1♥ × hurtPostCount` every 4 ticks |

All types mix freely in one column. Column events on every block type: `onPlace` → `notifyColumn`, `playerWillDestroy` → `notifyColumnExcluding`, `randomTick` → `notifyColumn`, `onLoad` (BE) → `recalcColumn`.

**Tick:** bottom BE only. Every 4 ticks. Vertical range ±2.5 blocks. Push strength 0.5, upward 0.1.

**Acquisition:** all posts are loot-only — jungle temples, desert pyramids, strongholds, mineshafts, simple dungeons. Weight 3 each.

---

### Warding Post (`wnir:warding_post`)

Pushes `isAggressive()` mobs outward. +6 radius per post.

---

### Teleporter Inhibitor (`wnir:teleporter_inhibitor`)

Cancels teleports in radius. +4 radius per post. `EntityTeleportEvent` at `LOWEST` priority — skips player-issued `/tp` (`TeleportCommand`, `SpreadPlayersCommand`).

---

### Lighting Post (`wnir:lighting_post`)

Light level 15. +4 radius. No active effect. Recipe: glowstone_dust × 4 + warding_post → 4.

---

### Hurt Post (`wnir:hurt_post`)

Deals magic damage (bypasses armor) to all `Enemy` implementors in column radius every 4 ticks. Damage scales with count: `2.0 × hurtPostCount` HP (i.e. 1♥ per post). +4 radius per post. Loot-only.

---

### Repelling Post (`wnir:repelling_post`)

Adds +4 radius to the column. No active effect of its own.

---

### EE Clock (`wnir:ee_clock`)

Column block that accelerates a block-entity machine. N clocks → N extra ticks per game tick (total: N+1).

**Machine discovery:**
1. Try `pos.above()` — machine above the column top
2. Fallback: `pos.below(columnHeight)` — machine below the column bottom

**Mechanics:**
- Only topmost EE Clock ticks
- Calls `BlockEntityTicker.tick()` exactly `columnHeight` extra times per game tick
- `columnHeight` via `ColumnHelper.countBelow` (includes self)

**Column events:** same pattern as Warding Post.

**Ticker helper:** `EEClockBlock.getMachineTicker()` uses `@SuppressWarnings("unchecked")` cast `(BlockEntityType<T>) be.getType()`. Cast is safe — BE instance and its type always correspond.

**Acquisition:** no crafting recipe — found only in End City treasure chests (weight 5, empty weight 10).

**Texture:** `cube_bottom_top` model with `ee_clock_top`, `ee_clock_bottom`, `ee_clock_side`.

---

### Mossy Hopper (`wnir:mossy_hopper`)

Item sorter hopper. Extends `HopperBlock` / `RandomizableContainerBlockEntity`.

**Slots:** 10 (two rows of 5). GUI opens on right-click.

**Transfer logic (every 8 ticks):**
- Pull from above via `HopperBlockEntity.suckInItems()`
- Push 2 items per cycle into the block in its facing direction
- **Never ejects the last item in a stack** — only pushes from slots where `count > 1`
- If only one eligible slot, both transfers come from it
- Slot selection is random among eligible slots each transfer
- Uses `Capabilities.Item.BLOCK` for ejection — works with vanilla and modded inventories

**GUI:**
- `WnirHopperMenu` (variant: `mossy`) — 10 hopper slots (rows at y=20 and y=38) + standard player inventory (y=64) + hotbar (y=122). `IMAGE_HEIGHT = 149`.
- `WnirHopperScreen` (factory: `"mossy_hopper"`) — renders `wnir:textures/gui/container/mossy_hopper.png` (256×256, assembled from vanilla hopper slices; content occupies top-left 176×149 pixels).
- Screen registered in `WnirClientSetup` via `RegisterMenuScreensEvent`.

**Recipe:** shaped — `"M M" / "MHM" / " M "`, M = mossy cobblestone, H = hopper → 1 mossy hopper. Category: redstone.

**Properties:** stone map color, metal sound, strength 3.0, `requiresCorrectToolForDrops()`, `noOcclusion()`. Listed in `minecraft:mineable/pickaxe` tag.

**Model:** parent `minecraft:block/hopper` with all texture variables set to `minecraft:block/mossy_cobblestone`.

---

### EE Clock Budding Crystal (`wnir:ee_clock_budding_crystal`)

Created automatically when a `BuddingAmethystBlock` is placed on top of an EE Clock column, or when an EE Clock is placed below an existing budding amethyst.

**Growth:** `BASE_TICKS = 168000` (1 Minecraft week). Scales by column height below: N EE Clocks → 168 000 / N ticks. Does NOT accelerate via EE Clock's extra-tick mechanism (guarded in `EEClockBlockEntity`).

**Transformation:** when progress reaches `BASE_TICKS`, the block replaces itself with an `EEClockBlock`.

**GUI:** right-click opens `GrowingCrystalScreen` (factory: `"ee_clock_budding_crystal"`, color `0xFF55AA44`) to show growth progress.

**Acquisition:** crafted or obtained via EE Clock + budding amethyst interaction.

---

### Teleporter Crystal (`wnir:teleporter_crystal`)

Created automatically when crying obsidian is placed on top of an EE Clock column, or when an EE Clock is placed below existing crying obsidian.

**Growth:** same `BASE_TICKS = 168000` schedule as EE Clock Budding Crystal, scaled by column height.

**Fuel:** consumes 16 ender pearls total — 14 fed in via GUI during growth, 2 consumed at transformation.

**Transformation:** when fully grown and fuelled, replaces itself with a `PersonalDimensionTeleporterBlock`.

**GUI:** right-click opens `GrowingCrystalScreen` (factory: `"teleporter_crystal"`, color `0xFF9955CC`) to insert ender pearls and monitor progress.

---

### Blue Sticky Tape (`wnir:blue_sticky_tape`)

Item that picks up any block (except bedrock/air) with full NBT, then places it back on right-click, consuming the item.

**Pickup:** clears container contents before world removal to prevent item duplication. Stores `block_state` + optional `block_entity` in `DataComponents.CUSTOM_DATA`. Sets `CUSTOM_MODEL_DATA=1` to trigger `SpecialModelRenderer`.

**Placement:** restores block state + loads block entity NBT via `be.loadWithComponents(TagValueInput)`.

**Item name (filled):** "Wrapped \<block name\>" via `getName()` override.

**Tooltip (filled):** container contents (up to 8 items) from `"Items"` list; spawner entity type from `"SpawnData" → "entity" → "id"`.

**Rendering:** `BlueStickyTapeRenderer` (`SpecialModelRenderer`). Sprite priority: SOUTH face → UP face → first unculled quad → `particleIcon()`. Overlays a generated blue X cross (`DynamicTexture`, 16×16).

**Model:** `items/blue_sticky_tape.json` — `range_dispatch` on `custom_model_data`; threshold 1.0 activates `minecraft:special` renderer `wnir:blue_sticky_tape`.

**Acquisition:** crafted (recipe: `data/wnir/recipe/blue_sticky_tape.json`).

---

### Antiwither (`wnir:antiwither`)

Explosion-immune block. Strength 50 (hardness) / 3,600,000 (explosion resistance) — immune to all explosions including the Wither. Requires diamond+ tool to mine.

**Recipe:** shapeless — 8 obsidian + 1 nether star.

---

### Wither Silencer (`wnir:wither_silencer`)

Suppresses `entity.wither.spawn` and `entity.wither.death` sounds **dimension-wide** when at least one Wither Silencer block exists in the dimension.

**Sound mechanics:**
- Wither spawn/death sounds are global level events with no meaningful world position — chunk-based distance checks are useless.
- Suppression is performed client-side in `WitherSilencerHandler.onPlaySound` (`PlaySoundEvent`).
- Any matching sound is replaced with `DelegateSoundInstance(original, 0f)` (silent delegate).
- Only those two sounds are silenced; all other Wither sounds remain unaffected.

**Registry:** `WitherSilencerBlockEntity` maintains a static `Map<ResourceKey<Level>, Set<BlockPos>>`. `onLoad` adds, `setRemoved` removes. `WnirMod.onServerStopping` calls `clearRegistry()`.

**Properties:** strength 50 / blast resistance 3,600,000. Tags: `minecraft:wither_immune`, `minecraft:needs_diamond_tool`. Listed in `minecraft:mineable/pickaxe`.

**Recipe:** shapeless — silencer_post + nether_star → 1 wither_silencer.

---

### Spawner (`wnir:spawner`)

Consumes Magic Cellulose fluid to spawn hostile mobs. Mob pool is determined at first tick by scanning the biome spawn list AND active structure spawn overrides at the block's position (e.g. Wither Skeletons / Blazes in a Nether Fortress).

**Parameters (static constants):**

| Field | Value | Meaning |
|-------|-------|---------|
| `TANK_CAPACITY` | 16 000 mB | Fluid tank size |
| `FLUID_PER_TICK` | 10 mB | Drained each active tick |
| `XP_PER_TICK` | 5 | XP accumulated per tick |
| `SPAWN_COST_MULTIPLIER` | 20 | `mob base XP × 20` = ticks to spawn |

**Tick logic:**
1. On first tick: `scanBiome()` — fills `candidates` list from biome MONSTER spawns + structure overrides.
2. Pause if redstone signal present.
3. Pause if fluid < `FLUID_PER_TICK`.
4. Drain 10 mB, accumulate 5 XP.
5. When `accumulatedXp >= targetXp`: spawn mob above, pick new target.

**Mob selection:** weighted random by spawn weight. Cost = `max(1, baseXp) × 20` ticks.

**Structure scan:** iterates `Registry<Structure>` from `level.registryAccess()`. For each structure with a MONSTER `StructureSpawnOverride`, checks `level.structureManager().getStructureWithPieceAt(pos, structure).isValid()`.

**Kill credit:** spawned mob receives `damageSources().playerAttack(installer)` for 1.0f HP — grants kill credit to the installer player (stored UUID set in `setPlacedBy`).

**Redstone:** `level.hasNeighborSignal(pos)` pauses operation; fluid is not drained.

**Fluid capability:** `Capabilities.Fluid.BLOCK` registered in `WnirMod`; accepts only `wnir:magic_cellulose` (still variant).

---

## 2. Mob Effects

All effects are beneficial marker `MobEffect` subclasses — no logic in the effect class itself. Behavior is entirely in event handlers registered in `WnirMod`.

---

### Martial Lightning (`wnir:martial_lightning`) — `#00BFFF`

Melee combat enhancement based on held weapon tier.

| Weapon | Damage mult | AoE | Secondary |
|--------|-------------|-----|-----------|
| Bare hand | 10x | Yes | — |
| Wooden | 5x | Yes | Poison (amp 31, 10s) |
| Stone | 3x | Yes | Wither (amp 3, 10s) |
| Iron | 1.5x | No | — |
| Other | 1x | No | — |

**AoE:** hits entities in front hemisphere within `entityInteractionRange`. Recursive AoE guarded by `ConcurrentHashMap<Player>` to prevent cascade.

**Categorization:** prefix match on registry path (`wooden_`, `stone_`, `iron_`).

**Handler:** `MartialLightningHandler::onLivingIncomingDamage`

---

### Homing Archery (`wnir:homing_archery`) — `#9B30FF`

Replaces bow arrows with homing `ShulkerBullet`.

**Target acquisition:**
1. Raycast 100 blocks along look direction
2. Fallback: nearest entity within 50 blocks with look-dot > 0.5

**Damage:** `base(2) × velocity_scale(3) × power × multiplier(3)` — up to 18 at full draw.

**Tracking:** `ConcurrentHashMap<UUID, TrackedBullet>` maps bullet UUID → damage. Stale entries cleaned after 60s.

**Handler:** `HomingArcheryHandler::onArrowLoose` (cancels arrow, spawns bullet), `HomingArcheryHandler::onLivingDamage` (applies damage mapping).

---

### Insane Light (`wnir:insane_light`) — `#FFFF44`

- Player glows (GLOWING) and has night vision (NIGHT_VISION) — refreshed when < 10 ticks remaining
- All mobs within 48 blocks get 2x FOLLOW_RANGE (`ADD_MULTIPLIED_BASE + 1.0`)
- Range boost refreshed every 40 ticks; removed when effect expires
- When a mob acquires the player as its target: 25% chance Blindness (5s), 25% chance Weakness (5s) — independent rolls

**Handler:** `InsaneLightHandler::onPlayerTick`, `InsaneLightHandler::onLivingChangeTarget`

---

### Mega Chanter (`wnir:mega_chanter`) — `#00FF99`

Bypasses the vanilla "Too Expensive" (cost ≥ 40) anvil cap. Recomputes the anvil operation from scratch whenever the event fires, identical to vanilla logic but caps display at 39 XP levels instead of blocking the result.

**Handler:** `MegaChanterHandler::onAnvilUpdate`

---

### Dead Blow (`wnir:dead_blow`) — `#FF2200`

Multiplies next incoming hit by 8×, then removes itself.

**Handler:** `DeadBlowHandler::onLivingIncomingDamage`

---

### Streamer Protect (`wnir:streamer_protect`) — `#FFD700`

Indicator-only. No handler — reserved for external use.

---

## 3. Potions

### Mega Chanter (`wnir:mega_chanter`)

Potion of Mega Chanting. Applies `mega_chanter` effect for 3600 ticks (3 min), amplifier 0.

**Brewing:** Awkward Potion + Book → Mega Chanter Potion.
Registered via `RegisterBrewingRecipesEvent` on `NeoForge.EVENT_BUS` (NOT modEventBus — it is not an `IModBusEvent` in 1.21.11).

Splash and lingering variants available via vanilla brewing chain — not blocked.

---

### Martial Lightning (`wnir:martial_lightning`)

Applies `martial_lightning` effect for 3600 ticks (3 min), amplifier 0.

**Brewing:** Awkward Potion + Golden Sword → Martial Lightning Potion.

---

## 4. Enchantments

All three are data-driven JSON under `data/wnir/enchantment/`. Handlers registered on `NeoForge.EVENT_BUS`.

### Swift Strike (`wnir:swift_strike`)

Attack speed boost via `ATTACK_SPEED` multiplier (`ADD_MULTIPLIED_TOTAL`).

| Level | Multiplier | Delay reduction |
|-------|-----------|-----------------|
| I | +1/3 | −25% |
| II | +1.0 | −50% |
| III | +3.0 | −75% |

Applied via transient modifier on `PlayerTickEvent.Post`. Modifier removed and re-applied every tick to reflect equipment changes.

**Applicable to:** `#minecraft:enchantable/weapon`

---

### Accelerate (`wnir:accelerate`)

Arrow velocity scaling on `EntityJoinLevelEvent` (when the arrow enters the world).

| Level | Velocity multiplier |
|-------|---------------------|
| I | ×1.5 |
| II | ×2.0 |
| III | ×3.0 |

Checks both main hand and off hand (crossbow can be in off hand). Works with `AbstractArrow` subclasses (`net.minecraft.world.entity.projectile.arrow.AbstractArrow`).

**Applicable to:** `#wnir:enchantable/ranged` (custom tag covering bows, crossbows)

---

### OverCrooking (`wnir:over_crooking`)

Hoe enchantment. When breaking leaves with an OverCrooking-enchanted hoe, multiplies counts of all drops **except** saplings and sticks.

| Level | Multiplier |
|-------|-----------|
| I | ×2 |
| II | ×3 |
| III | ×4 |

Uses `BlockDropsEvent`. Available at enchanting table.

---

### Toughness (`wnir:toughness`)

Armor toughness bonus via `ARMOR_TOUGHNESS` attribute (`ADD_VALUE`).

Sums enchantment level across all 4 armor slots. E.g. four pieces of Toughness III = +12 armor toughness.

Applied via transient modifier on `PlayerTickEvent.Post`.

**Applicable to:** `#minecraft:enchantable/armor`

---

### Loot & Tags

**In enchanting table:** `data/minecraft/tags/enchantment/in_enchanting_table.json` — all three enchantments.

**On mob spawn equipment:** `data/minecraft/tags/enchantment/on_mob_spawn_equipment.json` — all three.

**Dungeon loot modifiers (via `IGlobalLootModifier`):**
- `add_enchantment_books_loot` — enchantment books with wnir enchants in dungeon chests
- `add_enchanted_weapons_loot` — pre-enchanted iron sword/bow/chestplate in dungeon chests
- `add_end_city_loot` — EE Clock in End City treasure chests

---

## 5. Creative Tab

Tab ID: `wnir:wnir`. Title: "When Nothing Is Ready". Icon: chunk_loader.

Contains: chunk_loader, spawner_agitator, warding_post, teleporter_inhibitor, repelling_post, antiwither, wither_silencer, ee_clock, ee_clock_budding_crystal, teleporter_crystal, mossy_hopper, steel_hopper, nether_hopper, personal_dimension_teleporter, blue_sticky_tape, skull_beehive, magic_cellulose_bucket, celluloser, spawner.

---

## 6. Decisions

1. **No Compat class** — 1.21.11 only; use direct APIs throughout. No reflection for cross-version compat.
2. **`new BlockEntityType<>(factory, Set.of(block))`** — direct constructor (no Builder), 1.21.11 API.
3. **`Identifier.fromNamespaceAndPath`** — for ResourceKey creation; `Identifier` is the 1.21.11 rename of `ResourceLocation`.
3b. **`ResourceKey.identifier()`** — replaces `location()` (renamed in 1.21.11); used to extract the `Identifier` from a `ResourceKey`.
4. **`tag.getInt(key).orElse(0)`** — `CompoundTag.getInt` returns `Optional<Integer>` in 1.21.11.
5. **`attr.removeModifier(Identifier)`** — direct call; Compat reflection not needed in 1.21.11.
6. **`new AttributeModifier(Identifier, amount, op)`** — direct constructor; no reflection.
7. **`AbstractArrow` direct import** — at `net.minecraft.world.entity.projectile.arrow.AbstractArrow` in 1.21.11 (no sub-package lookup needed).
8. **`event.setXpCost(int)`** on AnvilUpdateEvent — direct (not `setCost(long)` from 1.21.1).
9. **`RegisterBrewingRecipesEvent` on `NeoForge.EVENT_BUS`** — NOT modEventBus; it is not an `IModBusEvent` in 1.21.11.
10. **`affectNeighborsAfterRemoval` instead of `onRemove`** — `onRemove` does not exist in 1.21.11. Fires after block is removed (pos is already air). Must notify `pos` AND `pos.above()` for split-column cases.
11. **`requiredPlayerRange = 32767` not -1** — -1 makes spawner always active but blocks server shutdown save loop (keeps generating work). 32767 is effectively infinite during gameplay but stops naturally when all players disconnect.
12. **Event-driven spawner binding** — no per-tick polling; bind/unbind on place/destroy/load events.
13. **Plain text file for ChunkLoaderData** — `X Y Z` per line, atomic save via tmp+rename. Avoids `SavedData` complexity and cross-version NBT API differences.
14. **EE Clock extra-ticks approach** — calls the machine's own `BlockEntityTicker.tick()` N extra times per game tick. Works with any vanilla or modded machine without cooperation (same approach as Draconic Evolution).
15. **Splash/lingering Mega Chanter potions allowed** — vanilla brewing chain produces them naturally; no lock-out intentional.
16. **`on_mob_spawn_equipment` tag** — pure JSON, no Java; simpler than a custom loot modifier.
17. **GUI blit API (1.21.11):** `g.blit(RenderPipelines.GUI_TEXTURED, Identifier, x, y, uPixel, vPixel, width, height, texW, texH)`. Old 7-param shorthand removed. Texture must be 256×256; u/v are pixel offsets.
18. **Block mineable tag required:** `requiresCorrectToolForDrops()` only gates drops; actual tool-speed and breakability require the block to be in `data/minecraft/tags/block/mineable/pickaxe.json` (or axe/shovel etc.).
19. **NBT API (1.21.11):** `loadAdditional(ValueInput)` / `saveAdditional(ValueOutput)` — no `HolderLookup.Provider`. Use `input.getIntOr(key, default)` and `output.putInt(key, val)`. `ContainerHelper.loadAllItems(ValueInput, NonNullList)`.
20. **`@EventBusSubscriber.bus()` ignored in NeoForge FML 4** — event bus routing is automatic via `IModBusEvent` interface; omit the `bus` parameter entirely.
21. **MapCodec covariant override:** subclass of `HopperBlock` cannot return `MapCodec<SubType>` — cast via `(MapCodec<HopperBlock>)(MapCodec<?>) CODEC` with `@SuppressWarnings("unchecked")`.
22. **Recipe key format:** use plain string `"M": "minecraft:item"` not object `"M": {"item": "..."}` — both valid in spec but plain string matches vanilla/working examples in this codebase.
24. **`SpecialModelRenderer` for item rendering:** register via `RegisterSpecialModelRendererEvent` in `@EventBusSubscriber` client class. JSON item model uses `"type": "minecraft:special"` with `"model": {"type": "wnir:renderer_id"}`. `MapCodec<Unbaked>` registered as codec.
25. **`BlockStateModel` (1.21.11):** `mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state)` returns `BlockStateModel`. Call `model.collectParts(RandomSource)` → `List<BlockModelPart>` (deprecated but functional). `BlockModelPart.getQuads(Direction)` → `List<BakedQuad>`. `BakedQuad` is now a Java record — use `.sprite()` accessor (not `.getSprite()`).
26. **`DynamicTexture` for generated textures:** `new DynamicTexture(() -> "debug_name", nativeImage)`. Register via `Minecraft.getInstance().getTextureManager().register(Identifier, texture)`. Use as `RenderTypes.itemEntityTranslucentCull(identifier)`.
27. **Container dupe bug on Blue Sticky Tape pickup:** call `container.clearContent()` before `level.removeBlock()` to prevent the block entity dropping its inventory contents on removal.
28. **`Item.getName(ItemStack)` override:** returns the display name; use to customise name for data-carrying items like Blue Sticky Tape (shows "Wrapped X" when filled).
29. **`appendHoverText` signature (1.21.11):** `(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` — consumer replaces `List<Component>`.
30. **Item NBT format (1.21.x codec):** `"Slot"` (uppercase), `"id"` (lowercase), `"count"` (lowercase). `CompoundTag.getList(key)` returns `Optional<ListTag>` — no type param.
23. **Loot modifier conditions broken:** `neoforge:loot_table_id` conditions in global loot modifiers may not filter correctly in 1.21.11 — modifier fires on all loot tables. Removed all enchantment-via-loot machinery; enchants available at enchanting table only.

---

---

### Accumulator (`wnir:accumulator`)

Passive FE buffer. Base capacity 1,000,000 FE. Retains charge when mined — dropped item carries full BE state (energy + capacity). Combine two or more in a crafting grid to merge their energy and capacity (custom recipe `AccumulatorCombineRecipe`).

**Tooltip:** dynamic header line showing `n / m FE` — color-coded by fill level (green > 66%, yellow 33–66%, red < 33%). Uses K / M / G suffixes. Rendered via `WnirBlockItem` `headerLines` hook.

---

### Celluloser (`wnir:celluloser`)

Converts enchanted books + water + FE into magic cellulose fluid.

**Inputs:**
- Enchanted book (1 slot) — consumed on start; XP calculated from enchantment min-cost formula
- Water (tank, 16 000 mB)
- FE energy (buffer 1 000 000 FE)

**Output:** Magic Cellulose fluid (tank, 16 000 mB)

**Processing parameters (public static, editable at runtime):**

| Field | Default | Meaning |
|-------|---------|---------|
| `XP_PER_TICK` | 200 | XP processed per server tick |
| `FE_PER_XP` | 100 | FE consumed per XP point |
| `WATER_PER_XP` | 1 | mB water consumed per XP |
| `OUTPUT_DIVISOR` | 10 | XP / divisor = mB cellulose produced |
| `TANK_CAPACITY` | 16 000 | mB per tank |
| `ENERGY_CAPACITY` | 1 000 000 | max FE buffer |

**Behaviour:**
- Pauses (preserves progress) when energy, water, or output space is exhausted
- Processing resumes automatically when resources are available
- XP calculated as sum of `levelToXp((minCost + maxCost) / 2)` per enchantment

**NeoForge capabilities (registered in `WnirMod`):**
- `Capabilities.Energy.BLOCK` → `be.energyHandler` (insert only, all faces)
- `Capabilities.Fluid.BLOCK` → `be.fluidHandler` (insert water slot 0; extract cellulose slot 1)

**GUI:** energy bar (fills from bottom) + water tank + cellulose tank + progress arrow. Texture `textures/gui/container/celluloser.png` (256×256). Fill sprites packed at y=168.

**Recipe:** shaped — `"EBE" / "SLS" / "GEG"` (E=emerald, B=brush, S=shears, L=lectern, G=gold_ingot).

**Properties:** green map color, metal sound, strength 3.5, `requiresCorrectToolForDrops()`. State preserved on mine via `copy_components` on `block_entity_data`.

---

### Steel Hopper (`wnir:steel_hopper`)

High-throughput version of the Mossy Hopper. Renders as iron.

**Slots:** 10 (two rows of 5). GUI opens on right-click.

**Transfer logic (every 8 ticks):**
- Pull from above via `HopperBlockEntity.suckInItems()`
- Push up to 8 items from a single slot per cycle (fills existing stacks first via `ResourceHandlerUtil.insertStacking`)
- No slot-lock restriction (unlike Mossy Hopper — all slots eligible)
- Uses `Capabilities.Item.BLOCK` for ejection — works with vanilla and modded inventories

**GUI:** `WnirHopperMenu` (variant: `steel`) + `WnirHopperScreen` (factory: `"steel_hopper"`) — identical layout to Mossy Hopper.

**Recipe:** shaped — `"I I" / "IHI" / " I "`, I = iron_ingot, H = hopper.

---

### Nether Hopper (`wnir:nether_hopper`)

Regulator hopper. Fills a target inventory with exactly one of each item type. Renders as netherrack.

**Slots:** 10 (two rows of 5). GUI opens on right-click.

**Transfer logic (every 8 ticks):**
- Pull from above via `HopperBlockEntity.suckInItems()`
- Eject: count occupied slots in target = N; pull from hopper slot N; regulator check; push 1 item to first accepting empty target slot
- **Regulator rule:** if target already contains the item type from hopper slot N, skip — nothing is inserted
- This ensures at most one of each item type ends up in the target

**Eject path decision:**
1. If target block entity implements `Container` → slot-count-mapped path (`countOccupied` via `Container.getItem`)
2. Else → capability fallback via `Capabilities.Item.BLOCK`: iterates hopper slots in order, skips any item already in target (check via aborted-transaction extract), inserts the first eligible item

**GUI:** `WnirHopperMenu` (variant: `nether`) + `WnirHopperScreen` (factory: `"nether_hopper"`) — identical layout to Mossy/Steel Hopper. Texture: `nether_hopper.png` (placeholder copy of mossy_hopper.png; needs netherrack-themed art).

**Recipe:** shaped — `"M M" / "MHM" / " M "`, M = netherrack, H = hopper → 1 nether hopper. Category: redstone.

**Properties:** nether map color, nether bricks sound, strength 3.0, `requiresCorrectToolForDrops()`, `noOcclusion()`. Listed in `minecraft:mineable/pickaxe` tag.

**Model:** parent `minecraft:block/hopper` + `hopper_side` with all textures = `minecraft:block/netherrack`.

**Capability registration:** `Capabilities.Item.BLOCK` → `VanillaContainerWrapper.of(be)` (same as other hoppers).

---

### Seed Bundle (`wnir:seed_bundle`)

Green bundle that auto-plants seeds across adjacent farmland.

**Capacity:** 9 distinct item stacks (max one stack per item type; up to maxStackSize items per slot). No weight limit.

**Insertion:** Click inventory item onto bundle to insert (inherited from `BundleItem`). Left-click slot item to transfer in; right-click bundle to remove one.

**Planting:** Right-click any surface → BFS flood-fill up to 64 blocks across same-level adjacent blocks. For each empty planting spot, tries each seed type in bundle using the seed's own `useOn()` — capability-based, not hardcoded to farmland. Plants one seed per position and removes it from the bundle.

**Recipe:** shaped — `" S " / "SBS" / " S "`, S = wheat_seeds, B = green_bundle.

**Model:** reuses vanilla `minecraft:item/green_bundle` models (with open-front/back in GUI when item selected).

---

### Magic Cellulose Fluid (`wnir:magic_cellulose`)

Custom fluid produced by the Celluloser. Still + flowing variants. Bucket item: `wnir:magic_cellulose_bucket`.

Textures: `textures/block/magic_cellulose_still.png`, `textures/block/magic_cellulose_flow.png`.

---

### Skull Beehive (`wnir:skull_beehive`)

Turret block that automatically shoots arrows at hostile mobs.

**Recipe:** `" S " / "SHS" / " S "` — S = skeleton skull, H = beehive. Category: misc.

**Inventory (136 slots total):**

| Slots | Purpose | Rules |
|-------|---------|-------|
| 0-5 | Bow / crossbow weapons (mixed ok) | Insert: any bow/crossbow. Extract: only if weapon is excluded (damaged) |
| 6 | Arrow receiver | Insert: arrows. Immediately drains to arrow storage |
| 7 | Gunpowder receiver | Insert: gunpowder. Immediately drains to gunpowder storage |
| 8-71 | Arrow storage (64 slots) | Max 1024 total. Arrows chosen at random per shot |
| 72-135 | Gunpowder storage (64 slots) | Max 1024 total |

**Shooting:**
- Range: 24 blocks (spherical). Target: nearest hostile mob in range — any entity implementing `net.minecraft.world.entity.monster.Enemy` (Monster subclasses, EnderDragon, Slime, Ghast, etc.). Endermen excluded.
- LoS: raycast to predicted mob position; aborts if blocked by blocks.
- Prediction: iterative gravity-compensated prediction using mob horizontal velocity.
- Cost per shot: 1 arrow + 1 gunpowder.
- Arrow velocity: base (bow=3.0, crossbow=3.15) × 2.0.
- Arrow damage: (2.0 + Power enchant bonus) × 2.0. Always critical.
- Flame enchant: 5s fire on target.
- Shot cooldown: 2 ticks between shots.

**Weapon management:**
- Weapon reload: bow = 20 ticks, crossbow = `CrossbowItem.getChargeDuration(stack, null)`.
- Best weapon = least damaged, reload finished, not excluded.
- If durability ≤ 1 before shot: play `DISPENSER_FAIL` sound, mark excluded, skip.
- Exclusion cleared when slot receives a fresh/repaired weapon (durability > 1).
- Durability reduced by 1 per shot via `hurtAndBreak`.

**GUI (slots 0-7 only; storage slots hidden from GUI):**
- Row 1 (y=20): 6 bow slots.
- Row 2 (y=42): arrow receiver + gunpowder receiver.
- Progress bars (y=64, y=76): arrow count / gunpowder count (client-synced via ContainerData).
- Texture: stub uses mossy_hopper.png placeholder.

**GeckoLib:** `SkullBeehiveBlockEntity` implements `GeoBlockEntity`. `SkullBeehiveGeoModel` registered. Idle animation loops (`animation.skull_beehive.idle`). `RenderShape.INVISIBLE` — rendered entirely by GeckoLib. Model at `assets/wnir/geo/skull_beehive.geo.json`; animation at `assets/wnir/animations/skull_beehive.animation.json`. Shooting animation not yet defined.

**Drop on break:** loot table uses `copy_components` → `block_entity_data`; all 136 slots preserved in the dropped item and restored on placement.

---

## 7. Out of Scope

- Automated tests
- Client-side rendering (custom block entity renderers)
- GUI / screen for blocks other than Mossy Hopper, EE Clock Budding Crystal, and Teleporter Crystal (all three have screens)
- Cross-mod API / capability integration
- Config file (no user-configurable parameters currently)
