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

**WNIR** (When Nothing Is Ready) is a standalone NeoForge Minecraft mod for **Minecraft 1.26 / NeoForge 26.1.2.30-beta** that provides custom blocks, mob effects, enchantments, and a brewing potion.

### Build structure
- Flat single-module Gradle project — no `versions/` subdirectory
- Source in `src/main/java/com/wnir/` and `src/main/resources/`
- `build.gradle` at root applies `net.neoforged.gradle.userdev` directly
- Java 21 toolchain required
- `gradle.properties`: `minecraft_version=26.1.2`, `neo_version=26.1.2.30-beta`
- Output jar: `build/libs/wnir-26.1.2-1.0.0.jar`

### 1.26 APIs (verified against compiled jars — do NOT trust decompiled Gradle cache sources)

**General**
- `BlockEntityType`: `new BlockEntityType<>(factory, Set.of(blocks))`
- Registry IDs: `props.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name)))`
- `ResourceKey.identifier()` — replaces `location()` (renamed)
- `CompoundTag.getInt(key)` returns `Optional<Integer>` — always use `.orElse(0)`
- `CompoundTag.getList(key)` returns `Optional<ListTag>` — no type param
- NBT load/save: `loadAdditional(ValueInput)` / `saveAdditional(ValueOutput)` — no HolderLookup param
- `ValueInput` int: `input.getIntOr("key", default)`
- `ContainerHelper`: `loadAllItems(ValueInput, list)` / `saveAllItems(ValueOutput, list)`
- `AttributeModifier`: `new AttributeModifier(Identifier.fromNamespaceAndPath(ns, path), amount, op)`
- `attr.removeModifier(Identifier)` — direct call
- `AbstractArrow`: `net.minecraft.world.entity.projectile.arrow.AbstractArrow`
- `AnvilUpdateEvent.setXpCost(int)` — direct (not `setCost`)
- `RegisterBrewingRecipesEvent` on `NeoForge.EVENT_BUS` (not modEventBus)
- `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` replaces `onRemove`
- `@EventBusSubscriber` `.bus()` param ignored — routing automatic via `IModBusEvent`; omit it
- `Level.isClientSide` private field — use `level.isClientSide()` method
- `MapCodec` covariant: `(MapCodec<Parent>)(MapCodec<?>) SUBTYPE_CODEC` + `@SuppressWarnings("unchecked")`
- `MenuType`: `new MenuType<>(MenuClass::new, FeatureFlags.VANILLA_SET)`
- `appendHoverText`: `(ItemStack, TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` — Consumer, not List

**Renamed / removed in 1.26**
- `SwordItem` — **removed**; check `DataComponents.WEAPON` or `s.getItem() instanceof AxeItem` instead
- `LivingHurtEvent` → `LivingDamageEvent.Pre`
- `entity.setSecondsOnFire(int)` → `entity.igniteForSeconds(float)`
- `entity.isOnGround()` → `entity.onGround()`
- `FoodProperties.saturationModifier()` → `FoodProperties.saturation()`
- `SoundEvents.*` constants are `Holder.Reference<SoundEvent>` — call `.value()` to get `SoundEvent`
- `Villager` / `AbstractVillager` moved to `net.minecraft.world.entity.npc.villager.*`
- `MerchantOffer` / `MerchantOffers` moved to `net.minecraft.world.item.trading.*`
- `level.getMinBuildHeight()` / `getMaxBuildHeight()` → `level.getMinY()` / `level.getMaxY()`

**GUI — screens (AbstractContainerScreen)**
- `GuiGraphics` **does not exist** in 1.26. Use `GuiGraphicsExtractor` everywhere.
- Override `extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial)` for background + slot rendering (replaces `renderBg` + `render`)
- Override `extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY)` for text labels (replaces `renderLabels`)
- `extractRenderState` is called by the framework; chain: `extractRenderState` → `extractContents` → `extractLabels`
- Text: `g.text(font, str, x, y, color, dropShadow)` and `g.centeredText(font, str, x, y, color)`
- Fill: `g.fill(x0, y0, x1, y1, color)` — same as before
- Scissor: `g.enableScissor(x0, y0, x1, y1)` / `g.disableScissor()`
- Pose: `g.pose().pushMatrix()` / `g.pose().translate(x, y)` / `g.pose().popMatrix()`
- `Slot.x` and `Slot.y` are **`public final int`** — cannot assign. For scrollable containers, use `g.pose().translate(0, -scrollOffset)` around slot rendering and override `isHovering` to adjust hit testing by the scroll offset.
- Mouse methods: `mouseClicked(MouseButtonEvent event, boolean doubleClick)`, `mouseDragged(MouseButtonEvent event, double dx, double dy)`, `mouseReleased(MouseButtonEvent event)` — `MouseButtonEvent` is a record with `event.x()`, `event.y()`, `event.button()`
- `mouseScrolled(double, double, double, double)` — unchanged
- `hoveredSlot` — protected field, set manually by calling `isHovering` in a loop when overriding `extractContents`

**GUI — HUD overlays (RenderGuiEvent)**
- `RenderGuiEvent.Post.getGuiGraphics()` returns `GuiGraphicsExtractor` (NOT `GuiGraphics`)
- Text on HUD: same `g.text(font, str, x, y, color, dropShadow)` as screens

**Networking**
- Packet registration: `RegisterPayloadHandlersEvent` — `event.registrar(MOD_ID).playToClient(...).playToServer(...)`
- Send to server from client: `net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload)` — `PacketDistributor.sendToServer` does NOT exist in compile-time jar
- `NeoForge packet registration`: `RegisterPayloadHandlersEvent` (NOT `RegisterPayloadsEvent`)

**Capabilities**
- NeoForge energy: `SimpleEnergyHandler`; register with `Capabilities.Energy.BLOCK`
- NeoForge fluid: `FluidStacksResourceHandler`; register with `Capabilities.Fluid.BLOCK`
- NeoForge item: `Capabilities.Item.BLOCK` → `ResourceHandler<ItemResource>`; insert via `ResourceHandlerUtil.insertStacking(handler, ItemResource.of(stack), amount, tx)`; `Capabilities.ItemHandler` does NOT exist at runtime
- Transactions: `try (var tx = Transaction.openRoot()) { ...; tx.commit(); }` — never nest `openRoot()`
- `RandomizableContainerBlockEntity` does NOT auto-expose `Capabilities.Item.BLOCK` — register explicitly

**Fluids / blocks**
- Custom fluid client: register `IClientFluidTypeExtensions` in `RegisterClientExtensionsEvent`
- Fluid textures: still/flow PNGs need `.mcmeta` with `{"animation":{"frametime":2}}`; use vanilla `minecraft:block/water_still` + `water_flow` with custom `getTintColor()` — custom PNGs often fail
- GUI textures: must be 256×256, **8-bit RGBA**. Use `RenderPipelines.GUI_TEXTURED`. 16-bit PNGs silently show missing texture.
- `blit`: `g.blit(RenderPipelines.GUI_TEXTURED, Identifier, x, y, uPixel, vPixel, w, h, texW, texH)`
- Hostile mob interface: `net.minecraft.world.entity.monster.Enemy` (NOT `net.minecraft.world.entity.Enemy`)

**Saving data**
- Save BE data to dropped item: `CompoundTag tag = be.saveCustomOnly(level.registryAccess()); stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(be.getType(), tag))` — do NOT use `collectComponents()` or `saveToItem()`
- `Item.craftingRemainingItem` field type: `@Nullable ItemStackTemplate` (not `Item`)
- `ItemStackTemplate(Item)` in Item constructor crashes — defer to post-registration callback

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
- **WardingColumnBlockEntity** — shared base block entity with column height caching; holds `targetName` (per-block filter from anvil rename), `targetPostCount`, `targetFilters` (union of names, computed by bottom BE)
- **ColumnHelper** — mixed-column traversal utilities (`countBelow`, `forEachInMixedColumn`, etc.)
- **WardingPostTeleportHandler** — scans for warding column blocks, cancels `EntityTeleportEvent`
- **TargetPostBlock** — warding column block; unnamed = damages all hostiles every 4 ticks; named = damages any mob of that entity type (hostile or passive); right-click mob with item → stamps entity type name onto item; name persists through break/place via `BlockDropsEvent`; craftable: warding_post + target (shapeless)

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

### Wedding Ring system
- **WeddingRingItem** — Shift+RC on owned pet binds ring; RC on bound pet opens menu
- **WeddingRingData** — NBT wrapper stored in `pet.getPersistentData()["WeddingRingData"]`; holds weapon/shield/food/armor/healing slots + hunger simulation state
- **WeddingRingMenu** — Container menu with 8 pet slots (weapon, shield, healing, food, 4 armor) + player inventory; scrollable viewport (4 rows)
- **WeddingRingScreen** — Custom screen using 1.26 `extractContents`/`extractLabels`; pose-translate scrolling; scroll-aware `isHovering`
- **WeddingRingGoalManager** — Adds/removes AI goals on the pet
- **WeddingRingAttributeManager** — Applies transient attribute modifiers from equipped items
- **WeddingRingTickHandler** — Server tick: hunger sim, food consumption, healing potion auto-use, pet death handling
- **WeddingRingAttackHandler** — `LivingDamageEvent.Pre` for fire aspect forwarding; `LivingIncomingDamageEvent` for shield blocking
- **WeddingRingTargetGoal / MeleeGoal / SpearGoal / MaceGoal** — Combat AI goals
- **HealingRingSlot** — Special slot that accumulates potion count (not a stack)
- **WeddingRingCaptionPayload** — Server→client packet for on-screen captions
- **WeddingRingCaptionRenderer** — HUD overlay for queued captions

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
- After two failed fix attempts: keep trying, but stop reasoning from assumptions — instrument heavily (log every relevant value at every key point) and let runtime evidence drive the next change.
- **If docs or source exist, read them first before guessing API shape. For 1.26 APIs, always verify against the compiled `.class` files in the Gradle cache — the decompiled `.java` sources may be from an older version.**
- Workflow: implement → build → install → wait for Vany's QA → commit. No commit before QA confirms it works.
- create release only if requested. Check readme and all documentation is updated before commit release.


### Module files
Each module directory may contain:
- **CLAUDE.md** — build commands, architecture overview, development rules
- **SPECS.md** — specifications, requirements, decisions
- **MEMO.md** — development memory
- **TODO.md** — task list (complete tasks one by one, mark finished)

Read these files if present. Maintain them. Use git commits to document project history. Store researched information in a `research/` folder.
