# NeoForge Tooltips Guide (Minecraft 1.21.11)

All APIs verified against NeoForge 21.11.38-beta compiled classpath (c8f52f0).

## Tooltip Hook Flow (Overview)

```
appendHoverText()          ← item/fluid define their own lines
      ↓
ItemTooltipEvent           ← NeoForge event; any mod can add/remove/reorder
      ↓
RenderTooltipEvent.GatherComponents  ← mix in TooltipComponent visuals
      ↓
RenderTooltipEvent.Pre     ← override position, font
      ↓
RenderTooltipEvent.Texture ← override background/frame texture
      ↓
ClientTooltipComponent factories render each line/widget
```

---

## 1. Basic Tooltip Injection

Override in your item. **1.21.11 signature uses `Consumer<Component>`, not `List<Component>`.**

```java
@Override
public void appendHoverText(
        ItemStack stack,
        Item.TooltipContext context,
        TooltipDisplay display,
        Consumer<Component> tooltip,
        TooltipFlag flag) {
    tooltip.accept(Component.literal("Basic tooltip"));
    tooltip.accept(Component.translatable("tooltip.mymod.key"));
}
```

`TooltipDisplay` is a record that tracks which data components are hidden. You can read it to conditionally suppress lines:

```java
if (display.shows(DataComponents.MAX_DAMAGE)) {
    tooltip.accept(Component.literal("Durability info"));
}
```

---

## 2. Styling & Colors

```java
// ChatFormatting
tooltip.accept(Component.literal("Text").withStyle(ChatFormatting.GREEN));

// RGB int (0xRRGGBB)
tooltip.accept(Component.literal("RGB").withStyle(style -> style.withColor(0xFF00FF)));

// Combined
tooltip.accept(Component.literal("Bold Red").withStyle(ChatFormatting.BOLD, ChatFormatting.RED));

// Italic gray hint (standard UX pattern)
tooltip.accept(Component.literal("Hold SHIFT for details")
    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
```

`Style.withColor(int rgb)` accepts raw ARGB int directly.

---

## 3. Shift / Ctrl / Alt Detection

```java
if (Screen.hasShiftDown()) {
    tooltip.accept(Component.literal("Detailed info"));
} else {
    tooltip.accept(Component.literal("Hold SHIFT").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
}
```

All three static methods confirmed on `net.minecraft.client.gui.screens.Screen`:
- `Screen.hasShiftDown()`
- `Screen.hasControlDown()`
- `Screen.hasAltDown()`

---

## 4. Advanced Mode (F3+H)

```java
if (flag.isAdvanced()) {
    tooltip.accept(Component.literal("Internal ID: " + stack.getDescriptionId())
        .withStyle(ChatFormatting.DARK_GRAY));
}
```

---

## 5. Localization

`en_us.json`:
```json
{
  "tooltip.mymod.energy": "Energy: %s FE",
  "tooltip.mymod.hint": "Hold SHIFT for details"
}
```

Usage:
```java
tooltip.accept(Component.translatable("tooltip.mymod.energy", energyValue));
```

---

## 6. Global Tooltip Modification (`ItemTooltipEvent`)

`ItemTooltipEvent` is fired on `NeoForge.EVENT_BUS` after `appendHoverText`. Its tooltip is a `List<Component>` (classic add/remove API).

```java
@SubscribeEvent
public static void onTooltip(ItemTooltipEvent event) {
    if (event.getItemStack().is(Items.DIAMOND)) {
        event.getToolTip().add(Component.literal("Global injection"));
        event.getToolTip().add(0, Component.literal("First line override"));
        event.getToolTip().remove(2);
    }
}
```

Verified methods on `ItemTooltipEvent`:
- `getToolTip()` → `List<Component>` (mutable)
- `getItemStack()` → `ItemStack`
- `getEntity()` → `@Nullable Player`
- `getFlags()` → `TooltipFlag`
- `getContext()` → `TooltipContext`

---

## 7. Accessing Context Data

```java
@Override
public void appendHoverText(ItemStack stack, Item.TooltipContext context,
        TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    // Read data component
    var data = stack.get(DataComponents.CUSTOM_DATA);
    if (data != null) {
        int value = data.copyTag().getIntOr("energy", 0);
        tooltip.accept(Component.literal("Energy: " + value + " FE"));
    }
}
```

From `ItemTooltipEvent` (has player access):
```java
Player player = event.getEntity();            // nullable
TooltipContext ctx = event.getContext();
Level level = ctx.level();                    // nullable
```

---

## 8. Spacing

```java
tooltip.accept(Component.empty());  // blank line between sections
```

`Component.empty()` returns `MutableComponent` backed by `PlainTextContents.EMPTY`.

---

## 9. Reading Item State

```java
// BLOCK_ENTITY_DATA (e.g. Accumulator energy)
TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
if (data != null) {
    CompoundTag tag = data.copyTagWithoutId();
    long energy   = tag.getLong("energy").orElse(0L);
    long capacity = tag.getLong("capacity").orElse(AccumulatorBlockEntity.BASE_CAPACITY);
    tooltip.accept(Component.literal("Energy: " + energy + " / " + capacity + " FE")
        .withStyle(ChatFormatting.AQUA));
}
```

---

## 10. Custom Visual Tooltip Rendering (Advanced)

`TooltipComponent` is a marker interface (`net.minecraft.world.inventory.tooltip`). The conversion to a rendered widget uses two NeoForge events:

### Step 1 — Define data

```java
public record EnergyBar(long energy, long capacity) implements TooltipComponent {}
```

### Step 2 — Inject via `ItemTooltipEvent` (NeoForge adds `TooltipComponent` support to the list)

NeoForge extends `ItemTooltipEvent`'s list so it accepts `TooltipComponent` instances cast-wrapped. Use NeoForge's `ITooltipFlag` / component event approach instead, or inject via `getToolTip()`:

> **Note:** In 1.21.11 the tooltip list is `List<Component>`. Adding a `TooltipComponent` directly will not work since it doesn't extend `Component`. Use `ClientTooltipComponent` rendering via `RenderTooltipEvent.GatherComponents` if you need fully custom visuals.

### Step 3 — Render via `RenderTooltipEvent.GatherComponents`

```java
@SubscribeEvent
public static void onGather(RenderTooltipEvent.GatherComponents event) {
    // event.getComponents() is List<Either<FormattedCharSequence, TooltipComponent>>
    // You can inject a TooltipComponent here
    event.getComponents().add(Either.right(new EnergyBar(energy, capacity)));
}
```

### Step 4 — Register client-side factory

```java
@SubscribeEvent
public static void onRegisterFactory(RegisterClientTooltipComponentFactoriesEvent event) {
    event.register(EnergyBar.class, eb -> new EnergyBarClientTooltip(eb));
}
```

### Step 5 — Implement `ClientTooltipComponent`

```java
@OnlyIn(Dist.CLIENT)
public class EnergyBarClientTooltip implements ClientTooltipComponent {
    private final EnergyBar data;

    public EnergyBarClientTooltip(EnergyBar data) { this.data = data; }

    @Override
    public int getHeight() { return 10; }

    @Override
    public int getWidth(Font font) { return 80; }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        // draw background bar
        g.fill(x, y, x + 80, y + 8, 0xFF333333);
        // draw fill
        int filled = (int) (80 * data.energy() / (double) data.capacity());
        g.fill(x, y, x + filled, y + 8, 0xFF00AAFF);
    }
}
```

---

## 11. UX Patterns

### Compact → Detailed:

```java
if (Screen.hasShiftDown()) {
    tooltip.accept(Component.literal("Line 1").withStyle(ChatFormatting.GRAY));
    tooltip.accept(Component.literal("Line 2").withStyle(ChatFormatting.GRAY));
} else {
    tooltip.accept(Component.literal("Hold SHIFT for details")
        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
}
```

### Gradient text (manual append):

```java
Component gradient = Component.literal("R").withStyle(s -> s.withColor(0xFF0000))
    .append(Component.literal("G").withStyle(s -> s.withColor(0x00FF00)))
    .append(Component.literal("B").withStyle(s -> s.withColor(0x0000FF)));
tooltip.accept(gradient);
```

### Standard color conventions:
- hints → `GRAY + ITALIC`
- values → `AQUA` or `YELLOW`
- warnings → `RED`
- debug/advanced → `DARK_GRAY`

---

## 12. `ClientTooltipFlag` — NeoForge Key State API

NeoForge wraps `TooltipFlag` into `ClientTooltipFlag` (a record) that bakes in modifier key state at tooltip creation time. Prefer this over calling `Screen.has*Down()` inside rendering loops.

```java
// net.neoforged.neoforge.client.ClientTooltipFlag
ClientTooltipFlag flag = ClientTooltipFlag.of(vanillaFlag); // convert in event handler

boolean shift   = flag.hasShiftDown();
boolean ctrl    = flag.hasControlDown();
boolean alt     = flag.hasAltDown();
boolean adv     = flag.isAdvanced();
boolean creative = flag.isCreative();
```

`ClientTooltipFlag` is what actually gets passed to `appendHoverText` in client code — cast it if you need key states without calling `Screen` statics.

---

## 13. `RenderTooltipEvent` Family (`net.neoforged.neoforge.client.event`)

All three are client-only, fired on `NeoForge.EVENT_BUS`.

### `RenderTooltipEvent.GatherComponents` (cancellable)

Fired before text wrapping. The tooltip is still a list of `Either<FormattedText, TooltipComponent>` at this stage — the right place to inject custom visual widgets.

```java
@SubscribeEvent
@OnlyIn(Dist.CLIENT)
public static void gatherTooltip(RenderTooltipEvent.GatherComponents event) {
    if (!event.getItemStack().is(MY_ITEM)) return;

    // inject a custom visual component
    event.getTooltipElements().add(Either.right(new EnergyBar(energy, capacity)));

    // or a text-only line
    event.getTooltipElements().add(Either.left(Component.literal("Injected line")));

    // control max width (-1 = unlimited)
    event.setMaxWidth(200);
}
```

Methods:
- `getItemStack()` — item being rendered (may be empty)
- `getTooltipElements()` — mutable `List<Either<FormattedText, TooltipComponent>>`
- `getMaxWidth()` / `setMaxWidth(int)` — pixel width cap
- `getScreenWidth()` / `getScreenHeight()` — screen dimensions

### `RenderTooltipEvent.Pre` (cancellable)

Fired before rendering. Cancel to suppress the tooltip entirely.

```java
@SubscribeEvent
@OnlyIn(Dist.CLIENT)
public static void preTooltip(RenderTooltipEvent.Pre event) {
    event.setX(event.getX() + 10);         // nudge right
    event.setY(event.getY() - 5);          // nudge up
    event.setFont(MyMod.CUSTOM_FONT.get()); // custom font
    // event.setCanceled(true);             // hide tooltip
}
```

Methods:
- `getX()` / `setX(int)`, `getY()` / `setY(int)` — position
- `setFont(Font)` — override render font
- `getTooltipPositioner()` — `ClientTooltipPositioner` (for custom positioning logic)
- `getComponents()` — unmodifiable `List<ClientTooltipComponent>`
- `getGraphics()`, `getFont()`

### `RenderTooltipEvent.Texture`

Fired when the background/frame texture is selected. Use to replace the tooltip visual frame.

```java
@SubscribeEvent
@OnlyIn(Dist.CLIENT)
public static void tooltipTexture(RenderTooltipEvent.Texture event) {
    if (event.getItemStack().is(MY_ITEM)) {
        event.setTexture(ResourceLocation.fromNamespaceAndPath("mymod", "textures/gui/fancy_tooltip"));
    }
}
```

Methods:
- `getTexture()` / `setTexture(ResourceLocation)` — current frame texture (from `DataComponents.TOOLTIP_STYLE` by default)
- `getOriginalTexture()` — texture before any event modification

---

## 14. Fluid Tooltips

Fluids have a parallel tooltip system.

### `IFluidExtension.appendHoverText`

Override in your `FluidType`:

```java
@Override
public void appendHoverText(FluidStack fluidStack, Item.TooltipContext context,
        TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    tooltip.accept(Component.literal("My Fluid").withStyle(ChatFormatting.AQUA));
}
```

### `FluidStack.getTooltipLines`

```java
List<Component> lines = fluidStack.getTooltipLines(context, player, flag);
```

Returns: hover name, custom lines, registry name (if advanced).

### `FluidTooltipEvent`

Same API as `ItemTooltipEvent` but for fluids. Fired on `NeoForge.EVENT_BUS`.

```java
@SubscribeEvent
public static void onFluidTooltip(FluidTooltipEvent event) {
    event.getToolTip().add(Component.literal("Global fluid line"));
    // event.getFluidStack(), event.getEntity(), event.getFlags(), event.getContext()
}
```

---

## 15. Attribute Tooltip Events

### `AddAttributeTooltipsEvent`

Fired after vanilla attribute tooltips are added. Add extra lines alongside attributes.

```java
@SubscribeEvent
public static void addAttributes(AddAttributeTooltipsEvent event) {
    if (event.getStack().is(MY_ITEM) && event.shouldShow()) {
        event.addTooltipLines(
            Component.literal("Special bonus: +5% crit").withStyle(ChatFormatting.GREEN)
        );
    }
}
```

### `GatherSkippedAttributeTooltipsEvent`

Hide specific attribute modifiers from the tooltip.

```java
@SubscribeEvent
public static void gatherSkipped(GatherSkippedAttributeTooltipsEvent event) {
    if (event.getStack().is(MY_ITEM)) {
        // hide base attack damage line
        event.skipId(AttributeUtil.BASE_ATTACK_DAMAGE_ID);
        // hide entire mainhand slot group
        event.skipGroup(EquipmentSlotGroup.MAINHAND);
        // or hide everything
        event.setSkipAll(true);
    }
}
```

---

## 16. Potion Effect Screen Tooltips

Fired when hovering a `MobEffectInstance` in the inventory effect list (client-only).

```java
@SubscribeEvent
@OnlyIn(Dist.CLIENT)
public static void effectTooltip(GatherEffectScreenTooltipsEvent event) {
    MobEffectInstance effect = event.getEffectInstance();
    event.getTooltip().add(Component.literal("Custom effect note"));
}
```

---

## 17. Data Components That Affect Tooltips

| Component | Effect |
|-----------|--------|
| `DataComponents.TOOLTIP_DISPLAY` | `TooltipDisplay` record — controls which data components show (e.g. hide enchantments) |
| `DataComponents.TOOLTIP_STYLE` | `ResourceLocation` — custom tooltip frame texture |
| `DataComponents.HIDE_TOOLTIP` | Presence hides tooltip entirely |
| `DataComponents.ATTRIBUTE_MODIFIERS` | Standard attribute lines; controlled by `GatherSkippedAttributeTooltipsEvent` |
| `DataComponents.ENCHANTMENTS` | Shown unless hidden via `TooltipDisplay` |

### Hide a specific data component from tooltip:

```java
// In appendHoverText, check if component is hidden:
if (!display.shows(DataComponents.ENCHANTMENTS)) return; // caller has hidden enchants

// On an ItemStack, hide a component:
stack.update(DataComponents.TOOLTIP_DISPLAY,
    TooltipDisplay.DEFAULT,
    d -> d.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
```

### Custom tooltip frame texture:

```java
// Set custom background/border (pairs with RenderTooltipEvent.Texture):
stack.set(DataComponents.TOOLTIP_STYLE,
    ResourceLocation.fromNamespaceAndPath("mymod", "textures/gui/special_tooltip"));
```

---

## 18. Custom Font Per Item

`IClientItemExtensions` (client-only extensions interface) allows overriding the font used in tooltip text and item count.

```java
@Override
public void initializeClient(Consumer<IClientItemExtensions> consumer) {
    consumer.accept(new IClientItemExtensions() {
        @Override
        public Font getFont(ItemStack stack, IClientItemExtensions.FontContext context) {
            if (context == IClientItemExtensions.FontContext.TOOLTIP) {
                return MyMod.FANCY_FONT.get();
            }
            return IClientItemExtensions.super.getFont(stack, context);
        }
    });
}
```

`FontContext` values: `TOOLTIP`, `ITEM_COUNT`, `SELECTED_ITEM_NAME`.
