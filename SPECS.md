# SPECS.md — When Nothing Is Ready (wnir)

## TODO


- [x] Chunk loader block
- [x] Spawner agitator block
- [x] Warding post block
- [x] Teleporter inhibitor block
- [x] Repelling post block
- [x] EE Clock machine accelerator block
- [x] Antiwither block
- [x] Martial Lightning effect
- [x] Homing Archery effect
- [x] Insane Light effect
- [x] Mega Chanter effect + anvil bypass
- [x] Dead Blow effect
- [x] Streamer Protect effect
- [x] Swift Strike enchantment
- [x] Accelerate enchantment
- [x] Toughness enchantment
- [x] Mega Chanter potion (Awkward + Book)
- [x] Enchanted weapons in dungeon loot
- [x] Enchantment books in dungeon loot
- [x] EE Clock in End City loot
- [x] Warding Post in dungeon loot

---

## Overview

**WNIR** is a NeoForge mod (1.21.11 only) that adds gameplay content: custom blocks, mob effects, enchantments, and a potion. It is the companion mod to **Minaret** (WebSocket + chord keys).

## Platform

| Parameter | Value |
|-----------|-------|
| Minecraft | 1.21.11 |
| Mod loader | NeoForge 21.11.38-beta |
| Java | 21+ |
| External deps | None (std lib only) |
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

**Known limitation:** spawner placed after agitator is only detected on chunk reload (`onLoad`). `neighborChanged` in 1.21.11 takes `Orientation` (not `BlockPos`), not currently overridden.

---

### Warding Post (`wnir:warding_post`)

Column block that repels hostile mobs outward. Radius scales with column height.

**Radius formula:** `radius = 4 * columnHeight` (1 post → 4 blocks, 2 → 8, etc.). Vertical range: ±2 blocks.

**Behavior:**
- Only topmost post in a column ticks (others skip via `isTopOfColumn`)
- Ticks every 4 server ticks
- Scans for `Monster` entities in an AABB; pushes each mob ~0.5 blocks outward + 0.1 upward
- Mobs at exact center pushed in +X direction

**Column events:**

| Event | Action |
|-------|--------|
| `onPlace` | `notifyColumn()` — recalc all posts in column |
| `playerWillDestroy` | `notifyColumnExcluding(removed)` |
| `affectNeighborsAfterRemoval` | `notifyColumn(pos)` + `notifyColumn(pos.above())` |
| `randomTick` | `notifyColumn()` — integrity recheck |
| `onLoad` (BE) | `recalcColumn()` |

**Loot:** found in jungle temples, desert pyramids, strongholds, mineshafts, simple dungeons.

---

### Teleporter Inhibitor (`wnir:teleporter_inhibitor`)

Prevents entity teleportation within radius. Participates in mixed warding columns.

**Radius formula:** `inhibitRadius = 4 * (wardingPostCount + inhibitorCount)`.

**Two modes:**
- **Standalone:** inhibits teleports only. Top `TeleporterInhibitorBlockEntity` owns the radius.
- **Mixed column with Warding Post:** warding posts handle mob repulsion at their own radius; inhibitor extends coverage. Top `WardingPostBlockEntity` owns both.

**Event:** `EntityTeleportEvent` at `LOWEST` priority. Skips `TeleportCommand` and `SpreadPlayersCommand` (player-issued `/tp`).

**Warding column system:**
- `WardingColumnBlock` — marker interface for both types
- `ColumnHelper` — `forEachInMixedColumn`, `countInMixedColumn`, `isTopOfMixedColumn`
- `WardingPostTeleportHandler` — scans range, reads radius from top-of-column BE

---

### Repelling Post (`wnir:repelling_post`)

Column block. Currently uses the same base behavior as Warding Post. Distinct block for future differentiation.

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

**Loot:** End City treasure chests (weight 5, empty weight 10).

**Texture:** `cube_bottom_top` model with `ee_clock_top`, `ee_clock_bottom`, `ee_clock_side`.

---

### Antiwither (`wnir:antiwither`)

Explosion-immune block. Strength 50 (hardness) / 3,600,000 (explosion resistance) — immune to all explosions including the Wither. Requires diamond+ tool to mine.

**Recipe:** shapeless — 8 obsidian + 1 nether star.

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

Indicator-only. No handler — logic handled externally (e.g. via Minaret WebSocket API).

---

## 3. Potion

### Mega Chanter (`wnir:mega_chanter`)

Potion of Mega Chanting. Applies `mega_chanter` effect for 3600 ticks (3 min), amplifier 0.

**Brewing:** Awkward Potion + Book → Mega Chanter Potion.
Registered via `RegisterBrewingRecipesEvent` on `NeoForge.EVENT_BUS` (NOT modEventBus — it is not an `IModBusEvent` in 1.21.11).

Splash and lingering variants available via vanilla brewing chain — not blocked.

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

Contains: chunk_loader, spawner_agitator, warding_post, teleporter_inhibitor, repelling_post, antiwither, ee_clock.

---

## 6. Decisions

1. **No Compat class** — 1.21.11 only; use direct APIs throughout. No reflection for cross-version compat.
2. **`new BlockEntityType<>(factory, Set.of(block))`** — direct constructor (no Builder), 1.21.11 API.
3. **`Identifier.fromNamespaceAndPath`** — for ResourceKey creation; `Identifier` is the 1.21.11 rename of `ResourceLocation`.
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

---

## 7. Out of Scope

- Automated tests
- Client-side rendering (custom block entity renderers)
- GUI / screen for any block
- Cross-mod API / capability integration
- Config file (no user-configurable parameters currently)
