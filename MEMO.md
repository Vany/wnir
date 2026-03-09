# MEMO.md — When Nothing Is Ready (wnir)

## Project
Standalone NeoForge mod, 1.21.11 only, no Compat.
Repo: `/Users/vany/l/wnir`
Test instance: `/Users/vany/Library/Application Support/PrismLauncher/instances/VanyLLa3d/minecraft/mods/`
Deploy: `make build`, then copy jar from `build/libs/` to test instance.

## Critical Patterns

- **NEVER world access in `setRemoved()`** → infinite loop during chunk unload. Use `playerWillDestroy()` on the Block class instead.
- **`onRemove` does not exist in 1.21.11.** Use `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` — fires after block is already air at pos. Must notify `pos` AND `pos.above()` for split-column teardown.
- **EE Clock machine search:** try `pos.above()` first; fallback `pos.below(columnHeight)` (below column bottom).
- **Spawner requiredPlayerRange = 32767** (not -1): -1 makes spawner always-active but blocks server shutdown save loop.
- **ChunkLoaderData:** plain text `wnir_chunk_loaders_<dim>.txt`, one `X Y Z` per line, atomic save via tmp+`Files.move(ATOMIC_MOVE)`. Not `SavedData`.

## 1.21.11 Direct APIs (no Compat needed)

| What | API |
|------|-----|
| BlockEntityType | `new BlockEntityType<>(factory, Set.of(block))` |
| Block registry ID | `props.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name)))` |
| ResourceKey → Identifier | `key.identifier()` (NOT `key.location()` — renamed in 1.21.11) |
| CompoundTag.getInt | Returns `Optional<Integer>` — always `.orElse(0)` |
| AttributeModifier | `new AttributeModifier(Identifier, amount, operation)` |
| Remove attribute modifier | `attr.removeModifier(Identifier)` — direct, no reflection |
| AbstractArrow import | `net.minecraft.world.entity.projectile.arrow.AbstractArrow` |
| Anvil event cost | `event.setXpCost(int)` (not `setCost`) |
| Brewing recipe event | `NeoForge.EVENT_BUS` (not modEventBus — not IModBusEvent in 1.21.11) |
| neighborChanged | Takes `Orientation` (not `BlockPos`) — not currently overridden |
| Food detection | `DataComponents.FOOD` component (not `getFoodProperties()`) |
| Player from event | `event.getEntity()` (not `getPlayer()`) |
| GUI background blit | `g.blit(RenderPipelines.GUI_TEXTURED, Identifier, x, y, uPixel, vPixel, width, height, texW, texH)` — old 7-param overload removed |
| NBT load/save | `loadAdditional(ValueInput)` / `saveAdditional(ValueOutput)` — no HolderLookup param |
| ValueInput int | `input.getIntOr("key", defaultValue)` (not `getInt().orElse()`) |
| ContainerHelper | `ContainerHelper.loadAllItems(ValueInput, list)` / `saveAllItems(ValueOutput, list)` |
| Block breaking tool | `requiresCorrectToolForDrops()` only gates drops; add block to `data/minecraft/tags/block/mineable/pickaxe.json` for actual pickaxe speed/breakability |
| `@EventBusSubscriber` | `.bus()` parameter ignored in NeoForge FML 4 — omit it; routing is automatic via `IModBusEvent` |
| `Level.isClientSide` | Private field — use `level.isClientSide()` method |
| MapCodec covariant | Subclass of HopperBlock: `(MapCodec<HopperBlock>)(MapCodec<?>) SUBTYPE_CODEC` + `@SuppressWarnings("unchecked")` |
| MenuType | `new MenuType<>(MenuClass::new, FeatureFlags.VANILLA_SET)` |

## Architecture Notes

- **WnirMod.java** — `@Mod("wnir")`. Brewing recipe on `NeoForge.EVENT_BUS`. Server start: `ChunkLoaderData.forceAll()`. Server stop: `SpawnerAgitatorBlockEntity.unbindAll()`, `WardingColumnBlockEntity.clearRegistry()`, `ChunkLoaderData.reset()`.
- **WnirRegistries.java** — All `DeferredRegister` for blocks, items, block entities, mob effects, potions, creative tab.
- **WnirEffects.java** — `public static MobEffect marker(int color)` factory. Effects are pure markers; all behavior is in handlers.
- **ColumnHelper** — `countBelow`, `forEachInMixedColumn`, `isTopOfMixedColumn`, mixed-column traversal.
- **WardingColumnBlock** interface — implemented by `WardingPostBlock` and `TeleporterInhibitorBlock`.
- **SpawnerAccessor** — reflection-based `BaseSpawner` field access (requiredPlayerRange, minSpawnDelay, maxSpawnDelay). Cached in `static volatile` fields.
- **ChunkLoaderData** — per-dimension singleton, file `wnir_chunk_loaders_<dim>.txt` in world save dir.
- **EEClockBlock.getMachineTicker()** — uses `@SuppressWarnings("unchecked")` cast `(BlockEntityType<T>) be.getType()` — always safe.
- **WardingPostTeleportHandler** — handles `EntityTeleportEvent` at `LOWEST` priority; skips `TeleportCommand` and `SpreadPlayersCommand`.
- **MegaChanterHandler** — recomputes anvil cost from scratch; caps display at 39 XP instead of blocking.
- **SwiftStrikeHandler / ToughnessHandler** — apply transient attribute modifier on `PlayerTickEvent.Post`, removed and re-applied every tick.
- **AccelerateHandler** — scales arrow velocity on `EntityJoinLevelEvent`.
- **HomingArcheryHandler** — cancels arrow, spawns `ShulkerBullet`; tracks damage via `ConcurrentHashMap<UUID, TrackedBullet>` (stale entries cleaned after 60s).
- **InsaneLightHandler** — 2× mob `FOLLOW_RANGE` via `ADD_MULTIPLIED_BASE + 1.0`; refreshed every 40 ticks.

## New Blocks Added

- **MossyHopperBlock** — extends `HopperBlock`. 10-slot sorter hopper. `MossyHopperBlockEntity` extends `RandomizableContainerBlockEntity`, implements `Hopper`. Never ejects last item. 2 items/8 ticks from random eligible slots. GUI via `MossyHopperMenu` + `MossyHopperScreen`. Client setup in `WnirClientSetup` (`@EventBusSubscriber`, `RegisterMenuScreensEvent`).

## Known Limitations / Issues

- Spawner placed *after* agitator is only detected on chunk reload (`onLoad`). `neighborChanged` takes `Orientation` in 1.21.11 — not currently overridden to watch for new spawners.
- Repelling Post currently has no unique behavior beyond Warding Post base.
