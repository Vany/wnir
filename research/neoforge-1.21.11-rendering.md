# NeoForge 1.21.11 — Item Rendering Research

Discovered while implementing `BlueStickyTapeRenderer` (SpecialModelRenderer for a data-carrying item).

---

## SpecialModelRenderer pipeline

### Registration
```java
// In @EventBusSubscriber(Dist.CLIENT):
@SubscribeEvent
public static void onRegisterSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
    event.register(
        Identifier.fromNamespaceAndPath(MOD_ID, "my_renderer"),
        MyRenderer.MAP_CODEC   // MapCodec<MyRenderer.Unbaked>
    );
}
```

### MapCodec
```java
public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

public static final class Unbaked implements SpecialModelRenderer.Unbaked {
    @Override public MapCodec<? extends SpecialModelRenderer.Unbaked> type() { return MAP_CODEC; }
    @Override public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext ctx) {
        return new MyRenderer();
    }
}
```

### Item model JSON (`assets/<mod>/items/my_item.json`)
```json
{
  "model": {
    "type": "minecraft:range_dispatch",
    "property": "minecraft:custom_model_data",
    "fallback": {"type": "minecraft:model", "model": "<mod>:item/my_item_icon"},
    "entries": [{
      "threshold": 1.0,
      "model": {
        "type": "minecraft:special",
        "base": "<mod>:item/my_item_icon",
        "model": {"type": "<mod>:my_renderer"}
      }
    }]
  }
}
```
The `"base"` model provides transforms (GUI/ground/hand); the special renderer provides geometry.

### SpecialModelRenderer interface methods
```java
// Extract per-stack data (called on the item stack each frame)
@Nullable T extractArgument(ItemStack stack);

// Submit geometry to the collector
void submit(@Nullable T arg, ItemDisplayContext ctx, PoseStack poseStack,
    SubmitNodeCollector collector, int packedLight, int packedOverlay,
    boolean foilEnabled, int tint);

// Bounding box for culling
void getExtents(Consumer<Vector3fc> consumer);
```

### Submitting custom quads
```java
collector.submitCustomGeometry(poseStack, renderType, (pose, vc) -> {
    vc.addVertex(pose, x, y, z)
      .setColor(r, g, b, a)
      .setUv(u, v)
      .setOverlay(packedOverlay)
      .setLight(packedLight)
      .setNormal(nx, ny, nz);
    // 4 vertices per quad
});
```

---

## BlockStateModel — getting sprites from block models

```java
// 1.21.11: BlockStateModel replaces BakedModel for blocks
BlockStateModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);

// collectParts is @Deprecated but works
@SuppressWarnings("deprecation")
List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));

// BakedQuad is now a Java record — accessor is .sprite() not .getSprite()
for (BlockModelPart part : parts) {
    List<BakedQuad> quads = part.getQuads(Direction.SOUTH);
    if (!quads.isEmpty()) return quads.get(0).sprite();
}

// Unculled quads (flowers, torches, cross models):
List<BakedQuad> unculled = part.getQuads(null);

// Particle icon fallback:
TextureAtlasSprite sprite = model.particleIcon();
```

### Best-face selection strategy (BlueStickyTapeRenderer):
1. SOUTH — shows front of furnace, chest, etc.
2. UP — shows grass top, decorated pots, etc.
3. First unculled quad — handles cross/flower/torch models
4. `particleIcon()` — always present, usually the side texture

---

## DynamicTexture — generating textures at runtime

```java
NativeImage img = new NativeImage(NativeImage.Format.RGBA, 16, 16, false);
// setPixelABGR(x, y, abgrColor) — note ABGR not ARGB
img.setPixelABGR(x, y, 0xFF_FF_80_1E); // ABGR: alpha=0xFF, blue=0xFF, green=0x80, red=0x1E

Identifier textureId = Identifier.fromNamespaceAndPath(MOD_ID, "dynamic/my_texture");
Minecraft.getInstance().getTextureManager()
    .register(textureId, new DynamicTexture(() -> "debug_label", img));

RenderType rt = RenderTypes.itemEntityTranslucentCull(textureId);
```

**Important:** `setPixelABGR` stores bytes in ABGR order. To get a blue colour with alpha:
- ABGR `0xFFFF501E` = alpha=255, blue=255, green=80, red=30 → renders as RGB(30, 80, 255) = blue.

---

## What does NOT work in 1.21.11 (attempted, failed)

### Offscreen framebuffer capture for item icons
The goal was to capture the block's inventory icon (isometric render) as a texture.

**Why it fails:**
- `GuiGraphics` is now retained-mode: `renderItem(stack, x, y)` records to `GuiRenderState`, does NOT immediately draw to the GL framebuffer.
- `GuiRenderState` drains at end of frame, not mid-render.
- `TextureTarget` constructor changed to `(String name, int w, int h, boolean useDepth)` — no `clearError` param.
- `RenderSystem.setProjectionMatrix(GpuBufferSlice, ProjectionType)` — not `(Matrix4f, VertexSorting)`.
- `NativeImage.downloadTexture(int, boolean)` does not exist in this version.

**Conclusion:** face-sprite selection is the correct approach for this version.

---

## API removed in 1.21.11 vs older versions

| What | Old (1.21.1) | New (1.21.11) |
|------|-------------|---------------|
| BakedQuad sprite | `.getSprite()` | `.sprite()` (record accessor) |
| BakedModel | `IBakedModel` / `BakedModel` | `BlockStateModel` for blocks |
| GuiGraphics blit | `blit(Identifier, x, y, u, v, w, h)` | `blit(RenderPipelines.GUI_TEXTURED, Identifier, x, y, u, v, w, h, texW, texH)` |
| RenderSystem projection | `setProjectionMatrix(Matrix4f, VertexSorting)` | `setProjectionMatrix(GpuBufferSlice, ProjectionType)` |
| CompoundTag.getList | `getList(key, type)` | `getList(key)` → `Optional<ListTag>` |
| CompoundTag.getString | `getString(key)` → `String` (empty if missing) | `getString(key)` → `Optional<String>` |
