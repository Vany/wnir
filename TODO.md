# Ideas to implement later

## BUGS

- ~~crash when open celluloser interface.~~ FIXED — TraderScreen was calling villager.getOffers() on the client; now synced via TraderSyncPayload
- check cherepuly timings.
- silencer must silence music

## Refactoring (copypaste reduction)

- [x] **R7** `DelegateSoundInstance` replaces `QuietSoundInstance` + `SilentSoundInstance`
- [x] **R1+R2** `WnirHopperMenu` + `WnirHopperScreen` replace 3 menu + 3 screen classes
- [x] **R3** `WnirHopperBlock` replaces `MossyHopperBlock` / `SteelHopperBlock` / `NetherHopperBlock`
- [x] **R4** `AbstractWnirHopperBlockEntity` base class extracted; ~200 lines of boilerplate removed
- [x] **R5+R6** `GrowingCrystalBlockEntity` + `GrowingCrystalMenu` + `GrowingCrystalScreen` replace 6 files; crystal BEs are now ~20 lines each

## ~~Mossy hopper.~~ DONE

~~Same as hopper, but have 10 slots for item stacks. When eject items, never eject last item in stack. this will be used as an item sorter.
texture same as hopper, but from mossy cobble.  recipe hoper + 5 mossy cobble: "M M", "MHM", " M ".~~


## misc undecided.
- [ ] Area spawn rate incrementer (like warding post, but for spawn rate)
- [ ] Mob damager
- [ ] Agitator random recheck configuration
