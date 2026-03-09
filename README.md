[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.172+-orange.svg)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1%20%7C%201.21.11-green.svg)](https://minecraft.net/)
[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

# WNIR — When Nothing Is Ready

A standalone NeoForge mod for Minecraft 1.21.11 that fills gaps in vanilla gameplay with custom blocks, mob effects, enchantments, and a potion.

**Mod ID:** `wnir` | **Loader:** NeoForge 21.11.38-beta | **Java:** 21+

---

## Contents

- [Blocks](#blocks)
- [Mob Effects](#mob-effects)
- [Enchantments](#enchantments)
- [Potion](#potion)
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
| **Layout** | `[agitators...][spawners...]` — agitators at bottom, spawners on top |
| **Effect** | Spawners always active; delays divided by agitator count |
| **Acquisition** | Dungeon loot (Overworld structures) — no crafting recipe |
| **Tool** | Pickaxe |

> **Limitation:** A spawner placed *above* the agitator after the fact is only detected on chunk reload.

---

### Warding Post

Column block that pushes hostile mobs outward. Stack posts to increase radius.

| | |
|---|---|
| **Radius** | 4 blocks per post in column (1 post → 4 blocks, 4 posts → 16 blocks) |
| **Vertical range** | ±2 blocks from column |
| **Acquisition** | Dungeon loot (jungle temples, desert pyramids, strongholds, mineshafts) — no crafting recipe |
| **Tool** | Pickaxe |

---

### Teleporter Inhibitor

Prevents entity teleportation (Endermen, Chorus Fruit, Ender Pearls) within radius. Can be mixed with Warding Posts in a single column.

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

---

### Mossy Hopper

A 10-slot hopper designed for item sorting. It **never ejects the last item** in a stack, keeping one copy of each item type in-place to act as a filter.

| | |
|---|---|
| **Slots** | 10 (two rows of 5) |
| **Transfer rate** | 2 items per 8 ticks from randomly chosen eligible slots |
| **Eligible slot** | Any slot with count > 1 |
| **Recipe** | Hopper surrounded by 5 mossy cobblestone: `"M M" / "MHM" / " M "` |
| **Tool** | Pickaxe |

Right-click to open the GUI. Connect to other containers and hoppers normally — it pulls from above and pushes toward its facing direction.

---

### Anti-Wither Block

Explosion-immune decorative block. Withstands Wither skulls, bed explosions in the Nether, and anything else.

| | |
|---|---|
| **Blast resistance** | 3,600,000 |
| **Recipe** | 8 obsidian + 1 nether star (shaped: `"IOI"/"IOI"/"IOI"` with O=obsidian, I=iron_bars) |
| **Tool** | Diamond pickaxe or better |

---

## Mob Effects

All effects are beneficial. Obtain them via potions, commands, or mods that grant effects.

### Martial Lightning `#00BFFF`

Dramatically boosts melee damage based on the held weapon tier. Weaker weapons hit harder but apply debuffs to the attacker.

| Weapon | Damage multiplier | AoE | Side effect on target |
|--------|------------------|-----|-----------------------|
| Bare hand | ×10 | Yes | — |
| Wooden | ×5 | Yes | Poison (31, 10s) |
| Stone | ×3 | Yes | Wither (3, 10s) |
| Iron | ×1.5 | No | — |
| Other | ×1 | No | — |

AoE hits all entities in the forward hemisphere within interaction range.

---

### Homing Archery `#9B30FF`

Replaces fired arrows with homing shulker bullets that track the nearest enemy.

- **Target:** entity under crosshair up to 100 blocks, or nearest entity within 50 blocks
- **Damage:** up to 18 at full draw (scales with bow pull and velocity)

---

### Insane Light `#FFFF44`

You glow. Mobs can always find you. They suffer for it.

- Permanent Glowing + Night Vision on the player
- All mobs within 48 blocks get 2× Follow Range
- When a mob locks onto you: 25% chance Blindness (5s), 25% chance Weakness (5s)

---

### Mega Chanter `#00FF99`

Removes the "Too Expensive!" anvil cap. All repair and enchanting operations go through regardless of accumulated cost.

Can also be brewed as a potion — see [Potion](#potion).

---

### Dead Blow `#FF2200`

Your next hit deals 8× damage, then the effect is consumed.

---

### Streamer Protect `#FFD700`

Indicator effect. Reserved for integration with external tools or manual creative use. No gameplay effect by itself.

---

## Enchantments

All three enchantments are available at the enchanting table on applicable item types.

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

Adds armor toughness. Stacks across all armor pieces.

Each piece of Toughness N armor contributes +N toughness. Four pieces at level III = +12 total.

**Applicable to:** all armor (`#minecraft:enchantable/armor`)

---

## Potion

### Potion of Mega Chanting

Grants **Mega Chanter** for 3 minutes. Brew splash and lingering variants via the standard vanilla brewing chain.

**Recipe:** Awkward Potion + Book → Potion of Mega Chanting

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

- No external mod dependencies
- No client-side-only components (server-safe)
- No config file — all values are fixed by design
