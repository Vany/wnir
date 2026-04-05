[![NeoForge](https://img.shields.io/badge/NeoForge-21.11.38--beta-orange.svg)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green.svg)](https://minecraft.net/)
[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

# WNIR — When Nothing Is Ready

A standalone NeoForge mod for Minecraft 1.21.11 that fills gaps in vanilla gameplay with custom blocks, mob effects, enchantments, potions, and utility items.

All items and blocks show a one-sentence description in their tooltip. Hold **SHIFT** while hovering to expand into a full usage description.

**Mod ID:** `wnir` | **Loader:** NeoForge 21.11.38-beta | **Java:** 21+

---

## Contents

- [Blocks](#blocks)
  - [Chunk Loader](#chunk-loader)
  - [Spawner Agitator](#spawner-agitator)
  - [Warding Post](#warding-post)
  - [Teleporter Inhibitor](#teleporter-inhibitor)
  - [EE Clock](#ee-clock)
  - [EE Clock Budding Crystal](#ee-clock-budding-crystal)
  - [Teleporter Crystal](#teleporter-crystal)
  - [Personal Dimension Teleporter](#personal-dimension-teleporter)
  - [Mossy Hopper](#mossy-hopper)
  - [Steel Hopper](#steel-hopper)
  - [Nether Hopper](#nether-hopper)
  - [Anti-Wither Block](#anti-wither-block)
  - [Wither Silencer](#wither-silencer)
  - [Skull Beehive](#skull-beehive)
  - [Celluloser](#celluloser)
  - [Spawner](#spawner)
- [Fluids](#fluids)
  - [Magic Cellulose](#magic-cellulose)
- [Items](#items)
  - [Blue Sticky Tape](#blue-sticky-tape)
  - [Mousey Compass](#mousey-compass)
- [Recipes](#recipes)
  - [Kelp Compression](#kelp-compression)
- [Mob Effects](#mob-effects)
- [Enchantments](#enchantments)
  - [Swift Strike](#swift-strike)
  - [Accelerate](#accelerate)
  - [Toughness](#toughness)
  - [OverCrooking](#overcrooking)
- [Potions](#potions)
- [Building](#building)

---

## Blocks

### Chunk Loader

Permanently force-loads its chunk so machines keep running when no player is nearby. Positions survive server restarts.

| | |
|---|---|
| **Acquisition** | Dungeon loot (Overworld structures) — no crafting recipe |
| **Tool** | Pickaxe |
| **Notes** | One loader per chunk is sufficient; stacking has no extra effect |

---

### Spawner Agitator

Place below a column of vanilla mob spawners. Removes the player-proximity requirement and scales spawn delays down with each agitator added.

| | |
|---|---|
| **Layout** | `[agitators...][spawners...]` — agitators at bottom, spawners stacked on top |
| **Effect** | Spawners always active; delays divided by agitator count |
| **Acquisition** | Dungeon loot (Overworld structures) — no crafting recipe |
| **Tool** | Pickaxe |

> **Limitation:** A spawner placed above the agitator after the fact is only detected on chunk reload.

---

### Warding Post

Column block that pushes hostile mobs outward. Stack posts to increase radius.

| | |
|---|---|
| **Radius** | 4 blocks per post in column (1 post → 4 blocks, 4 posts → 16 blocks) |
| **Vertical range** | ±2 blocks from column |
| **Acquisition** | Dungeon loot (jungle temples, desert pyramids, strongholds, mineshafts) — no crafting recipe |
| **Tool** | Pickaxe |

Can be mixed with Teleporter Inhibitor blocks in a single column — the combined column height counts toward both effects.

---

### Teleporter Inhibitor

Prevents entity teleportation (Endermen blink, Chorus Fruit, Ender Pearls) within radius. Stacks with Warding Posts in a mixed column.

| | |
|---|---|
| **Radius** | 4 blocks per (warding post + inhibitor) in mixed column |
| **Exemptions** | Player `/tp` commands are not blocked |
| **Acquisition** | Dungeon loot (same sources as Warding Post) — no crafting recipe |
| **Tool** | Pickaxe |

---

### EE Clock

Column block that accelerates the block-entity machine directly above (or below the column bottom). Each clock in the column adds one extra tick per game tick.

| | |
|---|---|
| **Effect** | N clocks → machine ticks N+1 times per game tick |
| **Compatible** | Any vanilla or modded machine with a server-side ticker |
| **Acquisition** | End City treasure chests — no crafting recipe |
| **Tool** | Pickaxe |

**Interaction with crystals:** placing a Budding Amethyst or Crying Obsidian directly on top of an EE Clock column transforms them into the corresponding crystal growth block (see below).

---

### EE Clock Budding Crystal

A growth block that slowly converts into a new EE Clock. Created automatically when a Budding Amethyst block is placed on top of an EE Clock column, or when an EE Clock is placed beneath an existing Budding Amethyst.

| | |
|---|---|
| **Growth time** | 168 000 ticks (1 Minecraft week) ÷ EE Clock column height below |
| **Transforms into** | EE Clock |
| **GUI** | Right-click to monitor progress |
| **Acquisition** | Budding Amethyst + EE Clock column interaction; crafting recipe |
| **Tool** | Pickaxe |

This block is **not** accelerated by the EE Clock it sits on top of — that would be recursive.

---

### Teleporter Crystal

A growth block that slowly converts into a Personal Dimension Teleporter. Created automatically when Crying Obsidian is placed on top of an EE Clock column, or when an EE Clock is placed beneath existing Crying Obsidian.

| | |
|---|---|
| **Growth time** | 168 000 ticks (1 Minecraft week) ÷ EE Clock column height below |
| **Fuel required** | 16 ender pearls (feed 14 via GUI; 2 consumed at final transformation) |
| **Transforms into** | Personal Dimension Teleporter |
| **GUI** | Right-click to insert ender pearls and monitor progress |
| **Acquisition** | Crying Obsidian + EE Clock column interaction; crafting recipe |
| **Tool** | Pickaxe |

The crystal will not transform until it is both fully grown and fully fuelled.

---

### Personal Dimension Teleporter

Teleports a player to the shared `wnir:personal` dimension, into their own private region. Each player gets a separate area along the X-axis.

**Head mechanic** — place a skull directly above the teleporter to control access:

| Skull type | Who can use |
|------------|-------------|
| No skull | Nobody |
| Player skull | Skull owner only |
| Mob skull (skeleton, creeper, etc.) | Any player |

**Hunger cost:** player must have a full hunger bar (20 food points).
- Attempting to teleport with less than 20 food deals 1 HP damage and is denied.
- Successful teleport drains hunger and saturation to 0.

| | |
|---|---|
| **Dimension** | `wnir:personal` — single shared dimension with per-player X-axis regions |
| **Spawn point** | Surface at center of player's region; determined by player UUID |
| **Acquisition** | Grown from a Teleporter Crystal |
| **Tool** | Pickaxe |

---

### Mossy Hopper

A 10-slot hopper designed for item sorting. It **never ejects the last item** in a slot, keeping one copy in-place as a permanent filter.

| | |
|---|---|
| **Slots** | 10 (two rows of 5) |
| **Transfer rate** | 2 items per 8 ticks from randomly chosen eligible slots |
| **Eligible slot** | Any slot where count > 1 |
| **Recipe** | Hopper + 5 mossy cobblestone: `"M M" / "MHM" / " M "` |
| **Tool** | Pickaxe |

Right-click to open the GUI. Pulls from above and pushes in its facing direction, like a vanilla hopper.

---

### Steel Hopper

A 10-slot high-throughput hopper. Transfers 8 items per 8-tick cycle — no restrictions on which slots are used.

| | |
|---|---|
| **Slots** | 10 (two rows of 5) |
| **Transfer rate** | Up to 8 items per 8 ticks |
| **Recipe** | Hopper + 5 iron ingots: `"I I" / "IHI" / " I "` |
| **Tool** | Pickaxe |

---

### Nether Hopper

A regulator hopper. Instead of blindly inserting items, it checks how many non-empty slots the target already has (= N) and pulls from its own slot N. It will not insert an item if the target already contains that item type.

| | |
|---|---|
| **Slots** | 10 (two rows of 5) |
| **Transfer rate** | 1 item per 8 ticks |
| **Recipe** | Hopper + 5 netherrack: `"M M" / "MHM" / " M "` |
| **Tool** | Pickaxe |

**How it works:**
- Count occupied slots in target = N
- Pull from hopper slot N
- If target already has that item type → skip (regulator rule)
- Otherwise push 1 item to the first accepting empty slot in target

This naturally fills a target inventory with exactly one of each item type — each hopper slot controls what goes into the next available target slot.

Supports vanilla containers (slot-mapped) and modded inventories via NeoForge item capability (regulator-only, no slot mapping).

---

### Anti-Wither Block

Explosion-immune decorative block. Withstands Wither skulls, Creeper and TNT explosions, bed explosions in the Nether — anything.

| | |
|---|---|
| **Blast resistance** | 3,600,000 |
| **Recipe** | Shaped: 8× obsidian + 1× nether star in centre (`"OOO" / "ONO" / "OOO"`) |
| **Tool** | Diamond pickaxe or better |

---

### Wither Silencer

Completely suppresses Wither spawn and death sounds across the entire dimension. Place anywhere in the dimension — one block is sufficient; the effect is dimension-wide.

| | |
|---|---|
| **Blast resistance** | 3,600,000 |
| **Recipe** | Shapeless: Silencer Post + Nether Star |
| **Tool** | Diamond pickaxe or better |

The sounds suppressed are `entity.wither.spawn` and `entity.wither.death`. All other Wither sounds (ambient, hurt, shoot) are unaffected. Suppression is client-side — the block registers its position on chunk load and deregisters on removal.

---

### Skull Beehive

An automated turret that fires arrows at nearby hostile mobs. Load it with bows, arrows, and gunpowder and it handles the rest.

| | |
|---|---|
| **Range** | 24 blocks (spherical) |
| **Targets** | All hostile mobs (`Enemy` implementors) — Wither, Warden, Ender Dragon, Ravager, Elder Guardian, etc. Endermen excluded |
| **Ammo cost** | 1 arrow + 1 gunpowder per shot |
| **Shot cooldown** | 2 ticks |
| **Damage** | (2.0 + Power bonus) × 2.0, always critical |
| **Recipe** | `" S " / "SHS" / " S "` — S = skeleton skull, H = beehive |
| **Tool** | Axe |

**Inventory (136 slots):**

| Slots | Purpose |
|-------|---------|
| 0–5 | Bow / crossbow weapons (mixed ok) |
| 6 | Arrow receiver → drains into arrow storage |
| 7 | Gunpowder receiver → drains into gunpowder storage |
| 8–71 | Arrow storage (64 slots, max 1024 total) |
| 72–135 | Gunpowder storage (64 slots, max 1024 total) |

- Selects the **least-damaged, reload-ready** weapon each shot
- Weapons about to break are excluded automatically (plays click sound); replace or repair to re-enable
- Supports Flame (5s fire) and Power enchants on the bows
- Arrow type chosen at random from storage — tipped and spectral arrows work
- **Sneak + right-click** to pick the block up with all contents preserved

---

### Celluloser

Processes enchanted books, water, and Forge Energy into **Magic Cellulose** fluid.

| | |
|---|---|
| **Inputs** | Enchanted book (any enchant level) + water + FE |
| **Output** | Magic Cellulose fluid |
| **Recipe** | Shaped: emerald / brush / shears / lectern / gold ingot |
| **Tool** | Pickaxe |

Right-click to open the GUI showing energy bar, water tank, cellulose tank, and progress arrow. Contents are preserved when the block is mined.

---

### Spawner

Consumes **Magic Cellulose** fluid to spawn hostile mobs of the types that naturally appear at its location. Learns which mobs to spawn from the biome's mob spawn list **and** any active structure spawn overrides — so placing it inside a Nether Fortress also produces Wither Skeletons and Blazes.

| | |
|---|---|
| **Fluid** | Magic Cellulose (tank: 16,000 mB) |
| **Rate** | 10 mB/tick consumed; 5 XP/tick accumulated |
| **Spawn cost** | Mob base XP × 20 ticks |
| **Redstone** | Signal pauses operation |
| **Kill credit** | Spawned mobs receive a ½-heart hit attributed to the player who placed the block, so loot tables and XP credit that player |
| **Recipe** | (crafting recipe or loot — see `data/wnir/recipe/spawner.json`) |
| **Tool** | Pickaxe |

Mob type is chosen randomly each cycle, weighted by the biome's spawn weight table. The next target is picked immediately after a spawn.

---

## Fluids

### Magic Cellulose

A pale-pink fluid produced by the Celluloser. Collect it with a bucket to get a **Magic Cellulose Bucket**.

| | |
|---|---|
| **Source** | Celluloser block |
| **Bucket** | `wnir:magic_cellulose_bucket` |
| **Color** | Pale pink |

---

## Items

### Blue Sticky Tape

Picks up any block (except bedrock and air) along with its full NBT data, then places it back on right-click — consuming the tape.

**Picking up:**
- Stores the block's state and block entity data (inventory contents, spawner config, etc.)
- Containers have their items cleared before removal to prevent duplication
- The tape switches to a special visual showing the block's face texture with a blue X cross

**Placing:**
- Restores the block and reloads all stored NBT into the new block entity
- Places the block on the face you click

**Information:**
- Item name changes to **"Wrapped \<block name\>"** while holding a block
- Tooltip shows container contents (up to 8 items) with counts
- Tooltip shows spawner entity type when wrapping a mob spawner

**Crafting recipe:** see `data/wnir/recipe/blue_sticky_tape.json`

---

### Mousey Compass

A compass that spirals outward from the player's position to find the nearest instance of a target block.

**Searching by block item:**
- Hold any block in your offhand
- Right-click the compass to begin searching

**Searching by name:**
- Rename a **name tag** on an anvil to the block's display name (e.g. `Chest`, `Nether Brick`, `Wither Skeleton Skull`)
- Hold the name tag in your offhand
- Right-click to search — match is case-insensitive

**While searching:**
- The needle spins toward the currently scanned chunk
- The action bar shows the current search radius in chunks
- On success: needle locks onto the found block, item glints, coordinates shown
- On failure: "Block not found" message, needle resets

**Notes:**
- Search radius: up to 16 chunks from the player's position
- Only loaded chunks are scanned; unloaded chunks are skipped and the search continues around them
- Dropping or switching away from the compass cancels the search
- Right-click with an empty offhand shows a usage hint

| | |
|---|---|
| **Recipe** | see `data/wnir/recipe/mousey_compass.json` |

---

## Mob Effects

All effects are beneficial. Obtain via potions, commands, or any mod that grants effects.

### Martial Lightning `#00BFFF`

Boosts melee damage based on the held weapon tier. Weaker weapons hit harder but apply debuffs to the attacker.

| Weapon | Damage multiplier | AoE | Side effect on target |
|--------|-----------------|-----|-----------------------|
| Bare hand | ×10 | Yes | — |
| Wooden | ×5 | Yes | Poison (31, 10s) |
| Stone | ×3 | Yes | Wither (3, 10s) |
| Iron | ×1.5 | No | — |
| Other (gold, diamond, netherite) | ×1 | No | — |

AoE hits all entities in the forward hemisphere within interaction range.

Obtainable as a potion: Potion / Splash Potion / Lingering Potion / Arrow of Martial Lightning.

---

### Homing Archery `#9B30FF`

Replaces fired arrows with homing shulker bullets that track the nearest enemy.

- **Target priority:** entity under crosshair within 100 blocks, then nearest entity within 50 blocks
- **Damage:** up to 18 at full draw (scales with bow pull and arrow velocity)

---

### Insane Light `#FFFF44`

You glow. Mobs can always find you. They suffer for it.

- Permanent Glowing + Night Vision on the player
- All mobs within 48 blocks get 2× Follow Range
- When a mob locks onto you: 25% chance Blindness (5s), 25% chance Weakness (5s), applied to the mob

---

### Mega Chanter `#00FF99`

Removes the "Too Expensive!" anvil cap. All repair and enchanting operations go through regardless of accumulated cost. Cost display is capped at 39 XP.

Can also be brewed as a potion — see [Potions](#potions).

---

### Dead Blow `#FF2200`

Your next hit deals 8× damage, then the effect is consumed. One shot.

---

### Streamer Protect `#FFD700`

Indicator effect. No gameplay behaviour. Reserved for integration with external tools or manual creative use.

---

## Enchantments

All enchantments are available at the enchanting table on applicable item types.

### Swift Strike

Increases attack speed.

| Level | Attack speed bonus | Effective delay reduction |
|-------|--------------------|--------------------------|
| I | +33% | −25% |
| II | +100% | −50% |
| III | +300% | −75% |

**Applicable to:** swords, axes (`#minecraft:enchantable/weapon`)

---

### Accelerate

Increases arrow velocity, resulting in a flatter arc and more damage at range.

| Level | Velocity multiplier |
|-------|---------------------|
| I | ×1.5 |
| II | ×2.0 |
| III | ×3.0 |

**Applicable to:** bows, crossbows (`#wnir:enchantable/ranged`)

---

### Toughness

Adds armor toughness. Stacks across all equipped armor pieces.

Each piece of Toughness N armor contributes +N toughness. Four pieces at level III = +12 total toughness.

**Applicable to:** all armor (`#minecraft:enchantable/armor`)

---

### OverCrooking

Multiplies the number of drops when breaking **leaves** with a hoe. Saplings and sticks are excluded from the bonus.

| Level | Drop multiplier |
|-------|----------------|
| I | ×2 |
| II | ×3 |
| III | ×4 |

**Applicable to:** hoes

Useful for farming apples, berries-from-leaves mods, and anything else leaves drop.

---

## Potions

### Potion of Mega Chanting

Grants **Mega Chanter** for 3 minutes. Brew splash and lingering variants via the standard vanilla brewing chain.

**Recipe:** Awkward Potion + Book → Potion of Mega Chanting

### Potion of Martial Lightning

Grants **Martial Lightning**. Available as Potion, Splash Potion, Lingering Potion, and Arrow.

**Recipe:** (via custom brewing ingredient — see mod source)

---

## Building

```bash
make build      # Build the mod jar
make run        # Launch dev client
make clean      # Clean build artifacts
make jar        # Build and print jar path
make setup      # Initialize Gradle wrapper (8.14)
```

Output: `build/libs/wnir-1.21.11-1.0.0.jar`

**Requirements:** Java 21, NeoForge 21.11.38-beta, Minecraft 1.21.11.

---

## Compatibility

- **Requires:** GeckoLib 5.4.5 (`geckolib-neoforge-1.21.11`) — used for Skull Beehive animations
- No client-side-only components (fully server-safe)
- No config file — all values are fixed by design
- 1.21.11 only — no cross-version compatibility shims
