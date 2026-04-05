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
| `SlotAccess` | `net.minecraft.world.entity.SlotAccess` (NOT `world.inventory`) |
| `@EventBusSubscriber` | `.bus()` parameter ignored in NeoForge FML 4 — omit it; routing is automatic via `IModBusEvent` |
| `Level.isClientSide` | Private field — use `level.isClientSide()` method |
| MapCodec covariant | Subclass of HopperBlock: `(MapCodec<HopperBlock>)(MapCodec<?>) SUBTYPE_CODEC` + `@SuppressWarnings("unchecked")` |
| MenuType | `new MenuType<>(MenuClass::new, FeatureFlags.VANILLA_SET)` |

## Architecture Notes

- **WnirMod.java** — `@Mod("wnir")`. Brewing recipe on `NeoForge.EVENT_BUS`. Server start: `ChunkLoaderData.forceAll()`. Server stop: `SpawnerAgitatorBlockEntity.unbindAll()`, `WardingColumnBlockEntity.clearRegistry()`, `ChunkLoaderData.reset()`, `WitherSilencerBlockEntity.clearRegistry()`.
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
- **WitherSilencerHandler** — `PlaySoundEvent` listener. If the sound is `entity.wither.spawn` or `entity.wither.death` AND at least one `WitherSilencerBlockEntity` exists in the current dimension → replace sound with `DelegateSoundInstance(original, 0f)`. No position check — Wither sounds are global.

## Hopper / Crystal Refactoring (unified classes)

All 3 hopper variants (mossy, steel, nether) and both crystal variants (ee_clock_budding, teleporter) now share unified base classes. Pattern: static factory methods in the shared class reference WnirRegistries fields to avoid self-reference in static initializer lambdas.

| New class | Replaces | Notes |
|-----------|---------|-------|
| `WnirHopperBlock` | `MossyHopperBlock`, `SteelHopperBlock`, `NetherHopperBlock` | Variant injected via constructor + `Supplier<BlockEntityType>` + ticker lambda |
| `WnirHopperMenu` | `MossyHopperMenu`, `SteelHopperMenu`, `NetherHopperMenu` | Static factories: `mossy()`, `steel()`, `nether()` — reference `WnirRegistries.*_MENU` at call time |
| `WnirHopperScreen` | `MossyHopperScreen`, `SteelHopperScreen`, `NetherHopperScreen` | `factory(textureName)` → `MenuScreens.ScreenConstructor<WnirHopperMenu, WnirHopperScreen>` |
| `AbstractWnirHopperBlockEntity` | duplicated fields/NBT/Container across 3 classes | Abstract `tryEject(level, pos, targetPos)` template method; `tick()` static helper |
| `GrowingCrystalBlockEntity` | `EEClockBuddingCrystalBlockEntity` (190 lines), `TeleporterCrystalBlockEntity` (190 lines) | Abstract `transformState()`, `revertState()`, `menuType()`; static `tick()` + `countEEClocksBelow()` |
| `GrowingCrystalMenu` | `EEClockBuddingCrystalMenu`, `TeleporterCrystalMenu` | Static factories: `eeClock()`, `teleporter()` |
| `GrowingCrystalScreen` | `EEClockBuddingCrystalScreen`, `TeleporterCrystalScreen` | `factory(textureName, colorProgress)` → `MenuScreens.ScreenConstructor` |
| `DelegateSoundInstance` | `QuietSoundInstance` + `SilentSoundInstance` inner classes | Single `factor` param: `0f` = silent, `0.1f` = attenuated |

**Self-reference pattern fix:** `new MenuType<>((id, inv) -> new WnirHopperMenu(MOSSY_HOPPER_MENU.get(), id, inv))` fails because `MOSSY_HOPPER_MENU` isn't initialized yet. Solution: `new MenuType<>(WnirHopperMenu::mossy)` where `mossy()` is defined in `WnirHopperMenu` and dereferences `WnirRegistries.MOSSY_HOPPER_MENU.get()` at call time (runtime, not init time).

## NeoForge Item Transfer API (1.21.11)

`Capabilities.ItemHandler` does NOT exist in the runtime jar — it is the old deprecated API. Use:

| Goal | API |
|------|-----|
| Get item handler from block | `level.getCapability(Capabilities.Item.BLOCK, pos, direction)` → `ResourceHandler<ItemResource>` |
| Convert ItemStack to resource | `ItemResource.of(stack)` |
| Insert (stacking, fills existing first) | `ResourceHandlerUtil.insertStacking(handler, resource, amount, tx)` → inserted count |
| Transaction | `try (var tx = Transaction.openRoot()) { ...; tx.commit(); }` — auto-aborts on close if not committed |
| Expose own inventory | Register in `RegisterCapabilitiesEvent`: `event.registerBlockEntity(Capabilities.Item.BLOCK, beType, (be, side) -> VanillaContainerWrapper.of(be))` |

**Critical:** `RandomizableContainerBlockEntity` does NOT auto-expose `Capabilities.Item.BLOCK`. Must register explicitly in `WnirMod` or modded inventories (and other wnir hoppers) can't see it.

## WnirBlockItem Tooltip System

`WnirBlockItem(block, props, name, headerLines, dataLines)`

| Field | Shown | Purpose |
|-------|-------|---------|
| `headerLines` | Always, before description | Dynamic state (e.g. energy level) |
| description (`tooltip.wnir.<name>`) | Always | One-liner via `WnirTooltips.add()` |
| detail (`tooltip.wnir.<name>.detail`) | Shift only | Usage notes |
| `dataLines` | Always, after description | Extra persistent data |

`formatFe(long)` — shared helper, K/M/G suffixes at 1k/1M/1G. Color pattern for fill level: `GREEN > 66%`, `YELLOW 33–66%`, `RED < 33%`.

## New Blocks/Items Added

- **WnirHopperBlock** + **AbstractWnirHopperBlockEntity** — unified hopper block/BE. Three variants: mossy (sorter, 2 items/8t, no last-item eject), steel (8 items/8t, unrestricted), nether (regulator: slot-N mapped, skips if target has that type). All use `Capabilities.Item.BLOCK` for ejection; expose own cap via `VanillaContainerWrapper.of(be)`.
- **NetherHopperBlockEntity** — dual eject path: `Container` (slot-count-mapped) + `Capabilities.Item.BLOCK` fallback. Nested `Transaction.openRoot()` forbidden — check tx must close before insert tx.
- **GrowingCrystalBlockEntity/Menu/Screen** — abstract base for EEClockBuddingCrystal and TeleporterCrystal. Grows over 168000 ticks ÷ EE Clock column height. Crystal BEs now ~20 lines each (just overrides). EEClock variant transforms to EEClockBlock; Teleporter variant requires 16 ender pearls, transforms to PersonalDimensionTeleporter.
- **WitherSilencerBlock/BE/Handler** — suppresses `entity.wither.spawn` + `entity.wither.death` dimension-wide. Static registry `Map<ResourceKey<Level>, Set<BlockPos>>` in BE. `DelegateSoundInstance(sound, 0f)` replaces the sound. Indestructible (strength 50, BR 3.6M). Tags: `wither_immune`, `needs_diamond_tool`. Recipe: silencer_post + nether_star (shapeless).
- **SpawnerBlock/BE** — consumes Magic Cellulose to spawn biome+structure mobs above the block. Structure scan: `Registry<Structure>` iteration + `getStructureWithPieceAt`. Pauses on redstone. Kill credit via `playerAttack(installer)` 1.0f hit.
- **PersonalDimensionTeleporterBlock** — teleports player to `wnir:personal` dimension. Head mechanic: player skull → owner only; mob skull → any player; no skull → locked. Requires full hunger (20/20); drains hunger to 0 on use. Per-player X-axis regions (`PersonalBiomeSource.REGION_WIDTH` wide), spawn on surface at region center.
- **BlueStickyTapeItem** — picks up any block (except bedrock/air) with full NBT; places back on right-click. Clears container before removal to prevent dupe. Name: "Wrapped \<block\>". Tooltip: container contents + spawner entity type.
- **OverCrookingHandler** — hoe enchantment via `BlockDropsEvent`; multiplies leaf drops (not saplings/sticks) by `level + 1`.
- **CelluloserBlock/BE/Menu/Screen** — enchanted book + water + FE → magic cellulose fluid. NeoForge energy+fluid capabilities. GUI: energy bar + water tank + cellulose tank + progress arrow; fill sprites packed at y=168 in 256×256 texture. Recipe: emerald/brush/shears/lectern/gold_ingot.
- **Magic Cellulose fluid** — `wnir:magic_cellulose` (still + flowing); bucket `wnir:magic_cellulose_bucket`. Registered before ITEMS (fluid static block initializer pattern).
- **Martial Lightning potion** — `wnir:martial_lightning`; Awkward + Golden Sword → 3600t amplifier 0.
- **DelegateSoundInstance** — `SoundInstance` wrapper. Single `factor` field: `0f` = silent, `0.1f` = attenuated. Replaces former `QuietSoundInstance` and `SilentSoundInstance` inner classes in separate handlers.
- **MouseyCompassItem** — compass item. Two search modes: `BlockItem` in offhand → search by registry ID; `Items.NAME_TAG` with `CUSTOM_NAME` in offhand → case-insensitive scan of `BuiltInRegistries.BLOCK` by display name. State in `CUSTOM_DATA`; needle driven by `LODESTONE_TRACKER`. BFS search via `MouseyCompassSearchManager` (one chunk/tick, palette pre-check, max radius 16).

## Item Rendering — SpecialModelRenderer (1.21.11)

```java
// Register in @EventBusSubscriber(Dist.CLIENT):
event.register(Identifier.fromNamespaceAndPath(MOD_ID, "my_renderer"), MyRenderer.MAP_CODEC);

// MapCodec<Unbaked>:
public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

// JSON item model (items/my_item.json):
{"model": {"type": "minecraft:special", "base": "...", "model": {"type": "wnir:my_renderer"}}}
```

- `BlockStateModel` from `mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state)`
- `model.collectParts(RandomSource)` → `List<BlockModelPart>` (deprecated; works)
- `part.getQuads(Direction)` → `List<BakedQuad>`; `BakedQuad` is a record → `.sprite()` (not `.getSprite()`)
- Sprite priority for "best face": SOUTH → UP → unculled quads (null dir) → `model.particleIcon()`
- Generated texture: `new DynamicTexture(() -> "name", nativeImage)`; register with `TextureManager.register(Identifier, texture)`; use `RenderTypes.itemEntityTranslucentCull(id)` for render type

## GuiGraphics Tooltip API (NeoForge 1.21.11 vs Vanilla)

**The decompiled `.java` sources in the Gradle cache do NOT match what the compiler sees.** NeoForge patches the compiled `.class` files directly; the decompiled sources are a mojmap view, not the actual compile-time API.

Vanilla-style `renderTooltip(Font, List<Component>, Optional<TooltipComponent>, int, int)` does NOT exist in the actual compiled class. Use:

| Goal | Method |
|------|--------|
| Multi-line component tooltip | `g.setComponentTooltipForNextFrame(font, List<Component>, mouseX, mouseY)` |
| Single component tooltip | `g.setTooltipForNextFrame(font, Component, mouseX, mouseY)` |
| Slot item tooltips | `renderTooltip(g, mouseX, mouseY)` — inherited `AbstractContainerScreen` method |

**Call `set*ForNextFrame` from `render()`, never from `renderLabels()`** — `renderLabels` runs in translated context (offset by leftPos/topPos), causing wrong tooltip position.

Pattern:
```java
@Override
public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    super.render(g, mouseX, mouseY, partialTick);
    renderTooltip(g, mouseX, mouseY); // slot item tooltips
    int rx = mouseX - leftPos, ry = mouseY - topPos;
    if (rx >= ... && ry >= ...) {
        g.setComponentTooltipForNextFrame(font, List.of(Component.literal("...")), mouseX, mouseY);
    }
}
```

## Celluloser Notes

- **NeoForge transfer APIs:** energy via `SimpleEnergyHandler` (`neoforged.neoforge.transfer.energy`); fluid via `FluidStacksResourceHandler` (`neoforged.neoforge.transfer.fluid`). Capabilities registered in `WnirMod` via `RegisterCapabilitiesEvent`.
- **Fluid slot write bypass:** `FluidStacksResourceHandler.isValid()` blocks external insert into slot 1 (cellulose out). Machine tick writes directly with `set(slot, resource, amount)` — bypasses `isValid`.
- **Transaction API:** `try (var tx = Transaction.openRoot()) { ...; tx.commit(); }` for atomic energy + fluid operations.
- **XP formula:** `levelToXp(n)` — `n≤16: n²+6n`; `n≤31: 2.5n²−40.5n+360`; `n>31: 4.5n²−162.5n+2220`.

## GeckoLib Notes (geckolib-neoforge-1.21.11 5.4.5)

- **Block entity:** implement `GeoBlockEntity`; provide `AnimatableInstanceCache` via `GeckoLibUtil.createInstanceCache(this)`; register controllers in `registerControllers`.
- **GeoModel:** extend `GeoModel<T>`; return `Identifier` for model, texture, animation resources. Model lookup is by bare name (no path prefix) in `assets/wnir/geo/`.
- **Resources:** model at `assets/wnir/geo/<name>.geo.json`; animation at `assets/wnir/animations/<name>.animation.json`. GeckoLib also scans `assets/wnir/geckolib/` (contains `models/` and `animations/` subdirs).
- **RenderShape:** set `RenderShape.INVISIBLE` on the Block — GeckoLib renderer handles all rendering.
- **Renderer registration:** `GeoBlockRenderer` registered in `WnirClientSetup.onRegisterBlockEntityRenderers` via `EntityRenderersEvent.RegisterRenderers`. GeckoLib 5.4.5 is missing `BlockEntityRenderState` from `interface_injections.json` — must use raw types: `(BlockEntityType) be.getType()` and `(BlockEntityRendererProvider) ctx -> new GeoBlockRenderer(model)`.
- **Fluid client extensions:** register `IClientFluidTypeExtensions` in `RegisterClientExtensionsEvent` to provide still/flowing texture identifiers and tint color. Magic Cellulose: `block/magic_cellulose_still`, `block/magic_cellulose_flow`, tint `0xFFFFB3D9` (pale pink ARGB).

## Skull Beehive Notes

- **136-slot container** — only slots 0-7 shown in GUI; slots 8-135 (arrow/gunpowder storage) accessible via hoppers/pipes through the `Container` interface (NeoForge auto-wraps `Container` as `IItemHandler`).
- **Target filter** — searches `Mob.class` AABB, then filters with `isValidTarget`: passes any `net.minecraft.world.entity.monster.Enemy` implementor except `EnderMan`. Covers Monster subclasses (Wither, Warden, Ravager, etc.) AND EnderDragon (which is a Mob but not a Monster). `Enemy` is in `net.minecraft.world.entity.monster` (not `net.minecraft.world.entity`).
- **`CrossbowItem.getChargeDuration(stack, null)`** — 1.21.11 requires a `LivingEntity` second arg; pass `null` for turret use.
- **`distanceToSqr(Vec3)`** — not `distanceSqTo(Vec3)` in 1.21.11.
- **Arrow knockback (Punch enchant)** — `AbstractArrow.setKnockback` removed in 1.21.11; Punch enchant effect currently not applied by the turret (TODO).
- **`ClipContext` ambiguity** — pass `CollisionContext.empty()` (not `null`) to resolve the two-constructor ambiguity.
- **Prediction** — uses `solveAimDir` gravity compensation `0.025 * t²`; iterative mob-velocity correction (8 iterations). Accuracy is good at typical ranges.
- **GUI texture** — `skull_beehive.png` is currently a copy of `mossy_hopper.png`; needs a proper texture before release.
- **GeckoLib wired** — `SkullBeehiveBlockEntity` implements `GeoBlockEntity`; `SkullBeehiveGeoModel` defined. Idle animation loops. Shooting animation not yet defined. `GeoBlockRenderer` registered in `WnirClientSetup` (raw-type cast required — see GeckoLib notes).
- **Sneak+right-click** — picks up block into player inventory (or drops at feet if full), preserving all NBT via `collectComponents()`.

- **LightingPostBlock** — warding column block, light level 15. No BE logic; reuses `WardingColumnBlockEntity`. Participates in mixed column height. Recipe: glowstone_dust × 4 + warding_post → 4. Dungeon loot weight 3.

## Known Limitations / Issues

- Spawner placed *after* agitator is only detected on chunk reload (`onLoad`). `neighborChanged` takes `Orientation` in 1.21.11 — not currently overridden to watch for new spawners.
- Repelling Post has no active effect — adds +4 radius only. Intentional placeholder.
- **GUI PNG bit depth:** Minecraft silently rejects 16-bit PNGs (shows missing/default texture). All GUI textures must be 8-bit RGBA. Convert with Pillow: `img.convert('RGBA').save(path, bits=8)`.
