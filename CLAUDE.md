# CLAUDE.md

Vany is your best friend. You can relay on me and always ask for help.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
make build      # Build the mod
make install    # Build and install to PrismLauncher test instance
make run        # Run dev client
make clean      # Clean build artifacts
make jar        # Build and show jar location
make setup      # Set up Gradle wrapper (8.14)
```

Direct Gradle: `./gradlew build`

No tests exist. `make test` runs `./gradlew test` but there are no test sources.

## Architecture

**WNIR** (When Nothing Is Ready) is a standalone NeoForge Minecraft mod for 1.21.11 that provides custom blocks, mob effects, enchantments, and a brewing potion.

### Build structure
- Flat single-module Gradle project — no `versions/` subdirectory
- Source in `src/main/java/com/wnir/` and `src/main/resources/`
- `build.gradle` at root applies `net.neoforged.gradle.userdev` directly
- Java 21 toolchain required
- 1.21.11 only — no cross-version compat code, no `Compat` class

### 1.21.11 direct APIs (no reflection hacks needed)
- `BlockEntityType`: `new BlockEntityType<>(factory, Set.of(blocks))`
- Registry IDs: `props.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name)))`
- `CompoundTag.getInt(key)` returns `Optional<Integer>` — always use `.orElse(0)`
- `AttributeModifier`: `new AttributeModifier(Identifier.fromNamespaceAndPath(ns, path), amount, op)`
- `attr.removeModifier(Identifier)` — direct call, no reflection
- `AbstractArrow`: `net.minecraft.world.entity.projectile.arrow.AbstractArrow`
- `AnvilUpdateEvent.setXpCost(int)` — direct (not `setCost`)
- `RegisterBrewingRecipesEvent` on `NeoForge.EVENT_BUS` (not modEventBus)
- `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` replaces `onRemove`
- `ResourceKey.identifier()` — replaces `location()` (renamed in 1.21.11)

### Core classes (all in `com.wnir`)
- **WnirMod** — Mod entry point (`@Mod`). Registers all event handlers and lifecycle listeners.
- **WnirRegistries** — All `DeferredRegister` declarations: blocks, items, block entities, mob effects, potions, creative tab.
- **WnirEffects** — Factory for marker `MobEffect` instances (beneficial, no logic in the effect itself — all behavior in handlers).

### Block subsystem
- **ChunkLoaderBlock / ChunkLoaderBlockEntity** — Force-loads chunk on place, unforces on destroy.
- **ChunkLoaderData** — Persists chunk loader positions in `wnir_chunk_loaders_<dim>.txt` (plain text, atomic save via tmp+rename). Singleton per `ServerLevel`.
- **SpawnerAgitatorBlock / SpawnerAgitatorBlockEntity** — Column-based spawner enhancement. Topmost agitator binds all contiguous spawners above, scales delays by agitator count.
- **WardingPostBlock / WardingPostBlockEntity** — Column-based hostile mob repulsion. `radius = 4 * columnHeight`.
- **TeleporterInhibitorBlock / TeleporterInhibitorBlockEntity** — Prevents teleportation in radius. Participates in mixed warding column.
- **RepellingPostBlock / RepellingPostBlockEntity** — (currently no unique behavior beyond warding post base)
- **EEClockBlock / EEClockBlockEntity** — Column-based machine accelerator. N extra ticks/game-tick for the machine above or below the column.
- **AntiWitherBlock** — Explosion-immune block (resistance 3,600,000). No block entity.
- **MossyHopperBlock / MossyHopperBlockEntity** — 10-slot sorter hopper. Never ejects last item (count > 1 required). 2 items/8 ticks from random eligible slots.
- **SteelHopperBlock / SteelHopperBlockEntity** — 10-slot high-throughput hopper. 8 items/8 ticks, no slot restriction.
- **NetherHopperBlock / NetherHopperBlockEntity** — 10-slot regulator hopper. Counts occupied target slots (= N), pulls from hopper slot N, inserts only if target lacks that item type. Dual path: `Container` (slot-count-mapped) + `Capabilities.Item.BLOCK` fallback. Never open nested `Transaction.openRoot()` — close check tx before opening insert tx.

**Critical pattern**: Never do world access (`getBlockState`, `getBlockEntity`, `setChunkForced`) in `setRemoved()` — it causes infinite loops during chunk unload. Use `playerWillDestroy()` on the Block class instead.

### Warding column system
- **WardingColumnBlock** — marker interface implemented by `WardingPostBlock` and `TeleporterInhibitorBlock`
- **WardingColumnBaseBlock** — shared base class for column blocks
- **WardingColumnBlockEntity** — shared base block entity with column height caching
- **ColumnHelper** — mixed-column traversal utilities (`countBelow`, `forEachInMixedColumn`, etc.)
- **WardingPostTeleportHandler** — scans for warding column blocks, cancels `EntityTeleportEvent`

### Mob effects & handlers (all beneficial marker effects)
- **MartialLightningHandler** — Melee combat: damage multiplier + AoE by weapon tier
- **HomingArcheryHandler** — Replaces arrows with homing ShulkerBullets
- **InsaneLightHandler** — Glowing + night vision + 2x mob FOLLOW_RANGE + random debuffs on target
- **MegaChanterHandler** — Bypasses anvil "Too Expensive" cap
- **DeadBlowHandler** — 8x damage on next hit, then consumes effect
- **StreamerProtectEffect** — Indicator only, no handler

### Enchantments & handlers (data-driven JSON + event handlers)
- **SwiftStrikeHandler** — Attack speed (`ATTACK_SPEED` multiplier) per weapon level
- **AccelerateHandler** — Arrow velocity scaling on `EntityJoinLevelEvent`
- **ToughnessHandler** — Armor toughness sum across all armor pieces

### SpawnerAccessor
Reflection-based access to `BaseSpawner` private fields (`requiredPlayerRange`, `minSpawnDelay`, `maxSpawnDelay`). Resolves fields by name first, then probes by default value as fallback. Cached in `static volatile` fields.

## Development Guidelines

Execute planned tasks in sequence; use insights from previous tasks to improve the current task and modify the plan itself. Combine tasks when possible.

- Create functional, production-ready code — concise, optimized, idiomatic Java
- Code and comments are written for AI consumption: explicit, unambiguous, predictable patterns
- Always finish functionality; log unimplemented features with errors
- Ask before creating unasked-for functionality
- Challenge decisions you disagree with — argue your position
- If no good solution exists, say so directly
- For large well-known functionality, search for ready libraries before building from scratch

### Module files
Each module directory may contain:
- **CLAUDE.md** — build commands, architecture overview, development rules
- **SPECS.md** — specifications, requirements, decisions
- **MEMO.md** — development memory
- **TODO.md** — task list (complete tasks one by one, mark finished)

Read these files if present. Maintain them. Use git commits to document project history. Store researched information in a `research/` folder.
