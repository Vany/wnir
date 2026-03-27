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

## New Blocks/Items Added

- **MossyHopperBlock** — extends `HopperBlock`. 10-slot sorter hopper. `MossyHopperBlockEntity` extends `RandomizableContainerBlockEntity`, implements `Hopper`. Never ejects last item. 2 items/8 ticks from random eligible slots. GUI via `MossyHopperMenu` + `MossyHopperScreen`. Client setup in `WnirClientSetup` (`@EventBusSubscriber`, `RegisterMenuScreensEvent`).
- **EEClockBuddingCrystalBlock/BE/Menu/Screen** — grows over 168000 ticks (÷ EE Clock column height below). Transforms into `EEClockBlock` when complete. Created when budding amethyst is placed on EE Clock column. Does NOT accelerate via EE Clock ticker (guarded in `EEClockBlockEntity`).
- **TeleporterCrystalBlock/BE/Menu/Screen** — same growth schedule; requires 16 ender pearls (14 via GUI + 2 at transform). Created when crying obsidian is placed on EE Clock column. Transforms into `PersonalDimensionTeleporterBlock`.
- **PersonalDimensionTeleporterBlock** — teleports player to `wnir:personal` dimension. Head mechanic: player skull → owner only; mob skull → any player; no skull → locked. Requires full hunger (20/20); drains hunger to 0 on use. Per-player X-axis regions (`PersonalBiomeSource.REGION_WIDTH` wide), spawn on surface at region center.
- **BlueStickyTapeItem** — picks up any block (except bedrock/air) with full NBT; places back on right-click. Clears container before removal to prevent dupe. Name: "Wrapped \<block\>". Tooltip: container contents + spawner entity type.
- **OverCrookingHandler** — hoe enchantment via `BlockDropsEvent`; multiplies leaf drops (not saplings/sticks) by `level + 1`.
- **CelluloserBlock/BE/Menu/Screen** — enchanted book + water + FE → magic cellulose fluid. NeoForge energy+fluid capabilities. GUI: energy bar + water tank + cellulose tank + progress arrow; fill sprites packed at y=168 in 256×256 texture. Recipe: emerald/brush/shears/lectern/gold_ingot.
- **Magic Cellulose fluid** — `wnir:magic_cellulose` (still + flowing); bucket `wnir:magic_cellulose_bucket`. Registered before ITEMS (fluid static block initializer pattern).
- **Martial Lightning potion** — `wnir:martial_lightning`; Awkward + Golden Sword → 3600t amplifier 0.

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

## Known Limitations / Issues

- Spawner placed *after* agitator is only detected on chunk reload (`onLoad`). `neighborChanged` takes `Orientation` in 1.21.11 — not currently overridden to watch for new spawners.
- Repelling Post currently has no unique behavior beyond Warding Post base.
