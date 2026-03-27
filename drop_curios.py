#!/usr/bin/env python3
"""
drop_curios.py — moves all Curios slot items into the player's main inventory.
Minecraft will drop any overflow on login.

Usage: python3 drop_curios.py [world_name]
Default world: ModPlay
"""

import sys
import shutil
from pathlib import Path
import nbtlib
from nbtlib import tag

SAVES = Path.home() / "Library/Application Support/PrismLauncher/instances/VanyLLa3d/minecraft/saves"
WORLD = sys.argv[1] if len(sys.argv) > 1 else "ModPlay"
PLAYER_DIR = SAVES / WORLD / "playerdata"
PLAYER_DAT = PLAYER_DIR / "77ed8d0b-61ed-4181-97ec-67c1699111e5.dat"
PLAYER_OLD = PLAYER_DIR / "77ed8d0b-61ed-4181-97ec-67c1699111e5.dat_old"
LEVEL_DAT   = SAVES / WORLD / "level.dat"
LEVEL_OLD   = SAVES / WORLD / "level.dat_old"

# ── Load ─────────────────────────────────────────────────────────────────────

print(f"Loading {PLAYER_DAT}")
nbt = nbtlib.load(str(PLAYER_DAT))

# ── Collect curios items from player.dat ──────────────────────────────────────

collected = []  # list of nbtlib Compound (item stacks)

curios_list = nbt["neoforge:attachments"]["curios:inventory"]["Curios"]
for slot_entry in curios_list:
    for slot_name, slot_data in slot_entry.items():
        stacks = slot_data["Stacks"]["Items"]
        if len(stacks) == 0:
            continue
        for item in stacks:
            item_id = str(item.get("id", "?"))
            count   = int(item.get("count", 1))
            print(f"  Found in [{slot_name}]: {item_id} x{count}")
            collected.append(item)
        # Clear the slot
        slot_data["Stacks"]["Items"] = nbtlib.List[nbtlib.Compound]()

def clear_curios(nbt_root, label):
    """Clear all curios slots from a player NBT root. Returns list of found items."""
    found = []
    try:
        curios_list = nbt_root["neoforge:attachments"]["curios:inventory"]["Curios"]
    except KeyError:
        print(f"  [{label}] No curios attachment found — skipping")
        return found
    for slot_entry in curios_list:
        for slot_name, slot_data in slot_entry.items():
            stacks = slot_data["Stacks"]["Items"]
            if len(stacks) == 0:
                continue
            for item in stacks:
                item_id = str(item.get("id", "?"))
                count   = int(item.get("count", 1))
                print(f"  [{label}] Found in [{slot_name}]: {item_id} x{count}")
                found.append(item)
            slot_data["Stacks"]["Items"] = nbtlib.List[nbtlib.Compound]()
    return found


if not collected:
    print("No items found in player.dat Curios slots.")
else:
    print(f"\nCollected {len(collected)} item(s) from Curios.")

# ── Find free ender chest slots and move items ────────────────────────────────

placed = 0
if collected:
    ender = nbt["EnderItems"]
    # nbtlib types empty lists as List[End]; replace with typed list if needed
    if not isinstance(ender, nbtlib.List) or ender.subtype != nbtlib.Compound:
        ender = nbtlib.List[nbtlib.Compound]()
        nbt["EnderItems"] = ender
    used_slots = {int(item["Slot"]) for item in ender}
    free_slots = [s for s in range(27) if s not in used_slots]

    print(f"Free ender chest slots: {len(free_slots)}")

    for item in collected:
        if free_slots:
            slot_id = free_slots.pop(0)
            item["Slot"] = nbtlib.Byte(slot_id)
            ender.append(item)
            placed += 1
        else:
            print(f"  WARNING: ender chest full — {item.get('id', '?')} not placed")

    print(f"Placed {placed} item(s) into ender chest.")

# ── Also clear curios from level.dat (Data.Player) ───────────────────────────

if LEVEL_DAT.exists():
    print(f"\nLoading {LEVEL_DAT}")
    level_nbt = nbtlib.load(str(LEVEL_DAT))
    try:
        level_player = level_nbt["Data"]["Player"]
        extra = clear_curios(level_player, "level.dat")
        if extra:
            print(f"  Cleared {len(extra)} item(s) from level.dat curios (items already moved via player.dat).")
        level_backup = LEVEL_DAT.with_suffix(".dat.curios_backup")
        shutil.copy2(LEVEL_DAT, level_backup)
        print(f"Backup saved to {level_backup.name}")
        level_nbt.save(str(LEVEL_DAT))
        if LEVEL_OLD.exists():
            shutil.copy2(LEVEL_DAT, LEVEL_OLD)
            print(f"Also patched {LEVEL_OLD.name}")
    except KeyError:
        print("  level.dat has no Data.Player block — skipping.")
else:
    print(f"\nNo level.dat found at {LEVEL_DAT} — skipping.")

# ── Backup + save player.dat ──────────────────────────────────────────────────

backup = PLAYER_DAT.with_suffix(".dat.curios_backup")
shutil.copy2(PLAYER_DAT, backup)
print(f"\nBackup saved to {backup.name}")

nbt.save(str(PLAYER_DAT))
# Also overwrite .dat_old so Minecraft can't fall back to the old version with curios filled.
shutil.copy2(PLAYER_DAT, PLAYER_OLD)
print(f"Also patched {PLAYER_OLD.name}")
print("Done. Start Minecraft — curios items are in your ender chest.")
