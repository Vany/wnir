package com.wnir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpectralArrowItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Skull Beehive turret block entity.
 *
 * ── Inventory layout (136 slots) ────────────────────────────────────────────
 *   0-5   weapon slots (bow / crossbow; mixed ok)
 *   6     arrow receiver  → drained to arrow storage every tick
 *   7     gunpowder receiver → drained to gunpowder storage every tick
 *   8-71  arrow storage (64 slots, max 1024 total)
 *   72-135 gunpowder storage (64 slots, max 1024 total)
 *
 * ── Shooting ────────────────────────────────────────────────────────────────
 *   - Range: 24 blocks (spherical)
 *   - Targets: nearest Monster in range with clear LoS to predicted position
 *   - Prediction: iterative gravity-compensated mob-velocity prediction
 *   - Velocity: base bow/crossbow speed × 2; damage: enchantment-based × 2
 *   - Arrow type: random slot from arrow storage (tipped/spectral arrows work)
 *   - Cost per shot: 1 arrow + 1 gunpowder
 *   - Shot cooldown: 2 ticks between shots
 *   - Weapon selection: least-damaged, non-excluded, reload-ready weapon
 *   - Weapon protection: won't fire if doing so would break the weapon;
 *     marks it excluded and plays click sound instead
 *
 * ── NeoForge capability ─────────────────────────────────────────────────────
 *   Exposes IItemHandler (all 136 slots) via InvWrapper with canPlaceItem /
 *   canTakeItem overrides applied through the Container interface.
 *   Bow slots: pipes may ONLY EXTRACT bows that are currently excluded (damaged).
 *   Arrow / gunpowder slots: unrestricted insert & extract.
 */
public class SkullBeehiveBlockEntity extends RandomizableContainerBlockEntity implements GeoBlockEntity {

    // ── GeckoLib ─────────────────────────────────────────────────────────────

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.skull_beehive.idle");
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("controller", 0,
            state -> state.setAndContinue(IDLE)));
    }

    // ── Slot constants ───────────────────────────────────────────────────────

    static final int WEAPON_START          = 0;
    static final int WEAPON_SLOTS          = 6;
    static final int WEAPON_END            = WEAPON_START + WEAPON_SLOTS;   // 6
    static final int ARROW_RECEIVER        = WEAPON_END;                    // 6
    static final int GUNPOWDER_RECEIVER    = WEAPON_END + 1;                // 7
    static final int ARROW_STORAGE_START   = WEAPON_END + 2;                // 8
    static final int ARROW_STORAGE_END     = ARROW_STORAGE_START + 64;      // 72
    static final int GUNPOWDER_STORAGE_START = ARROW_STORAGE_END;           // 72
    static final int GUNPOWDER_STORAGE_END   = GUNPOWDER_STORAGE_START + 64; // 136
    static final int TOTAL_SLOTS           = GUNPOWDER_STORAGE_END;         // 136
    static final int MAX_AMMO              = 1024;

    private static final double RANGE       = 24.0;
    private static final float  BASE_BOW_V  = 3.0f;
    private static final float  BASE_XBOW_V = 3.15f;

    // ── State ────────────────────────────────────────────────────────────────

    private NonNullList<ItemStack> inventory = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);

    /** Remaining ticks before weapon[i] can fire again. */
    private final int[]     weaponReloadTimes = new int[WEAPON_SLOTS];
    /**
     * True if weapon[i] was found to have ≤ 1 durability remaining.
     * Cleared automatically when a fresh/repaired weapon is detected in the slot.
     */
    private final boolean[] weaponExcluded    = new boolean[WEAPON_SLOTS];

    /** Ticks remaining in the 2-tick shot cooldown between successive shots. */
    private int shotCooldown = 0;

    /** UUID of the player who placed this block; arrows are attributed to them. */
    @Nullable private UUID ownerUUID = null;

    // ── Cached targeting state (server-only, never serialized) ───────────────

    /**
     * Last known valid target.  Validated cheaply each tick (isAlive + distance).
     * Cleared when the mob dies, leaves range, or the block is removed.
     */
    @Nullable private Mob cachedTarget = null;

    /**
     * Counts up each tick when there is no valid target.
     * A full AABB search fires only when this reaches TARGET_SEARCH_INTERVAL.
     */
    private int targetSearchTimer = TARGET_SEARCH_INTERVAL; // start hot → search immediately

    /**
     * Counts down.  When it reaches 0 a new raycast is performed and
     * {@link #cachedLoS} is refreshed.  Reset to LOS_INTERVAL after each check.
     */
    private int losTimer = 0;  // 0 = needs check

    /** Cached result of the last LoS raycast. */
    private boolean cachedLoS = false;

    /** Counts up each tick; receivers are drained when it reaches DRAIN_INTERVAL. */
    private int drainTimer = 0;

    private static final int TARGET_SEARCH_INTERVAL = 10; // full AABB search at most once per 10 t
    private static final int LOS_INTERVAL           = 4;  // raycast at most once per 4 t
    private static final int DRAIN_INTERVAL         = 4;  // drain receivers once per 4 t

    // ── Construction ─────────────────────────────────────────────────────────

    public SkullBeehiveBlockEntity(BlockPos pos, BlockState state) {
        super(WnirRegistries.SKULL_BEEHIVE_BE.get(), pos, state);
    }

    /** Suppress the default Container spill — inventory is preserved via the loot table. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {}

    public void setOwnerUUID(@Nullable UUID uuid) {
        this.ownerUUID = uuid;
        setChanged();
    }

    // ── NBT ─────────────────────────────────────────────────────────────────

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        inventory = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
        if (!tryLoadLootTable(input)) ContainerHelper.loadAllItems(input, inventory);
        for (int i = 0; i < WEAPON_SLOTS; i++) {
            weaponReloadTimes[i] = input.getIntOr("Reload" + i, 0);
            weaponExcluded[i]    = input.getIntOr("Excl" + i, 0) != 0;
        }
        shotCooldown = input.getIntOr("ShotCooldown", 0);
        String ownerStr = input.getStringOr("OwnerUUID", "");
        ownerUUID = ownerStr.isEmpty() ? null : UUID.fromString(ownerStr);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!trySaveLootTable(output)) ContainerHelper.saveAllItems(output, inventory, false);
        for (int i = 0; i < WEAPON_SLOTS; i++) {
            output.putInt("Reload" + i, weaponReloadTimes[i]);
            output.putInt("Excl" + i, weaponExcluded[i] ? 1 : 0);
        }
        output.putInt("ShotCooldown", shotCooldown);
        if (ownerUUID != null) output.putString("OwnerUUID", ownerUUID.toString());
    }

    // ── Container / RandomizableContainerBlockEntity ─────────────────────────

    @Override public int getContainerSize()                     { return TOTAL_SLOTS; }
    @Override protected NonNullList<ItemStack> getItems()       { return inventory; }
    @Override protected void setItems(NonNullList<ItemStack> l) { inventory = l; }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.wnir.skull_beehive");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInv) {
        return new SkullBeehiveMenu(id, playerInv, this, syncData);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        // Clear weapon exclusion whenever the weapon slot changes (replaces per-tick scan).
        if (slot >= WEAPON_START && slot < WEAPON_END) {
            int i = slot - WEAPON_START;
            if (stack.isEmpty() || stack.getMaxDamage() - stack.getDamageValue() > 1) {
                weaponExcluded[i] = false;
            }
        }
        setChanged();
    }

    /**
     * Insert rules:
     *   weapon slots:           bows and crossbows only
     *   arrow receiver:         arrows only
     *   gunpowder receiver:     gunpowder only
     *   arrow storage:          arrows only, total ≤ MAX_AMMO
     *   gunpowder storage:      gunpowder only, total ≤ MAX_AMMO
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= WEAPON_START && slot < WEAPON_END) {
            return isWeapon(stack);
        }
        if (slot == ARROW_RECEIVER) {
            return isArrow(stack);
        }
        if (slot == GUNPOWDER_RECEIVER) {
            return stack.is(Items.GUNPOWDER);
        }
        if (slot >= ARROW_STORAGE_START && slot < ARROW_STORAGE_END) {
            return isArrow(stack) && countStorage(ARROW_STORAGE_START, ARROW_STORAGE_END) < MAX_AMMO;
        }
        if (slot >= GUNPOWDER_STORAGE_START && slot < GUNPOWDER_STORAGE_END) {
            return stack.is(Items.GUNPOWDER)
                && countStorage(GUNPOWDER_STORAGE_START, GUNPOWDER_STORAGE_END) < MAX_AMMO;
        }
        return false;
    }

    /**
     * Extract rules:
     *   weapon slots: only if the weapon is currently excluded (damaged / can't fire)
     *   all other slots: always allowed
     */
    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        if (slot >= WEAPON_START && slot < WEAPON_END) {
            return weaponExcluded[slot - WEAPON_START];
        }
        return true;
    }

    // ── ContainerData for GUI sync ───────────────────────────────────────────

    /**
     * 2 data slots sent to the client:
     *   0 → total arrows in storage (0-1024)
     *   1 → total gunpowder in storage (0-1024)
     */
    final net.minecraft.world.inventory.ContainerData syncData =
        new net.minecraft.world.inventory.ContainerData() {
            @Override public int get(int i) {
                return switch (i) {
                    case 0 -> countStorage(ARROW_STORAGE_START, ARROW_STORAGE_END);
                    case 1 -> countStorage(GUNPOWDER_STORAGE_START, GUNPOWDER_STORAGE_END);
                    default -> 0;
                };
            }
            @Override public void set(int i, int value) {} // client-only, no-op
            @Override public int getCount() { return 2; }
        };

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void serverTick(
        Level level, BlockPos pos, BlockState state, SkullBeehiveBlockEntity be
    ) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 1. Drain receivers — rate-limited to once every DRAIN_INTERVAL ticks.
        //    Reduces 128 slot-iterations/tick to ~32.
        if (++be.drainTimer >= DRAIN_INTERVAL) {
            be.drainTimer = 0;
            be.drainReceiver(ARROW_RECEIVER,
                ARROW_STORAGE_START, ARROW_STORAGE_END,
                MAX_AMMO, SkullBeehiveBlockEntity::isArrow);
            be.drainReceiver(GUNPOWDER_RECEIVER,
                GUNPOWDER_STORAGE_START, GUNPOWDER_STORAGE_END,
                MAX_AMMO, s -> s.is(Items.GUNPOWDER));
        }

        // 2. Tick down weapon reloads (cheap, 6 ops).
        for (int i = 0; i < WEAPON_SLOTS; i++) {
            if (be.weaponReloadTimes[i] > 0) be.weaponReloadTimes[i]--;
        }

        // 3. Shot cooldown — skip all expensive work while waiting.
        if (be.shotCooldown > 0) { be.shotCooldown--; return; }

        // (Weapon exclusion is now handled in setItem — no per-tick scan needed.)

        // 4. Quick ammo check before touching the entity list.
        if (be.countStorage(ARROW_STORAGE_START, ARROW_STORAGE_END) == 0) return;
        if (be.countStorage(GUNPOWDER_STORAGE_START, GUNPOWDER_STORAGE_END) == 0) return;

        // 5. Get or find a target.  Full AABB search runs at most once every
        //    TARGET_SEARCH_INTERVAL ticks; otherwise re-uses the cached reference.
        Vec3 shootOrigin = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        Mob target = be.getOrFindTarget(serverLevel, shootOrigin);
        if (target == null) return;

        // 6. LoS check — raycasted at most once every LOS_INTERVAL ticks.
        if (--be.losTimer <= 0) {
            be.losTimer  = LOS_INTERVAL;
            Vec3 mobCenter = target.position().add(0, target.getBbHeight() * 0.5, 0);
            be.cachedLoS = hasLineOfSight(serverLevel, shootOrigin, mobCenter);
        }
        if (!be.cachedLoS) return;

        // 7. Pick the best weapon (least damaged, reload done, not excluded).
        int weaponSlot = be.pickWeapon(serverLevel);
        if (weaponSlot < 0) return;

        // 8. Aim and fire.
        ItemStack bow = be.inventory.get(weaponSlot);
        double arrowSpeed = getArrowSpeed(bow) * 2.0;
        Vec3 predictedPos = be.predictTarget(target, shootOrigin, arrowSpeed);

        ItemStack arrowStack = be.takeRandomArrow();
        if (arrowStack.isEmpty()) return;
        be.consumeFromStorage(GUNPOWDER_STORAGE_START, GUNPOWDER_STORAGE_END);

        be.fireArrow(serverLevel, shootOrigin, predictedPos, bow, arrowStack, arrowSpeed);

        bow.hurtAndBreak(1, serverLevel, null, i -> {});
        be.weaponReloadTimes[weaponSlot] = getReloadTicks(bow);
        be.shotCooldown = 2;
        be.setChanged();
    }

    /**
     * Returns the cached target if still alive and in range, otherwise runs a
     * full AABB entity search at most once per TARGET_SEARCH_INTERVAL ticks.
     *
     * Replaces calling {@link #findTarget} every tick, which was the single
     * largest per-tick cost.
     */
    @Nullable
    private Mob getOrFindTarget(ServerLevel level, Vec3 origin) {
        // Fast path: validate the cached entity without touching the entity list.
        if (cachedTarget != null) {
            if (!cachedTarget.isRemoved() && cachedTarget.isAlive()
                && origin.distanceToSqr(
                    cachedTarget.position().add(0, cachedTarget.getBbHeight() * 0.5, 0)
                ) <= RANGE * RANGE) {
                targetSearchTimer = 0; // we have a target, no need to count up
                return cachedTarget;
            }
            // Target lost — drop the reference and force an immediate re-search.
            cachedTarget = null;
            cachedLoS    = false;
            targetSearchTimer = TARGET_SEARCH_INTERVAL;
        }

        // Slow path: only run the AABB search every TARGET_SEARCH_INTERVAL ticks.
        if (++targetSearchTimer < TARGET_SEARCH_INTERVAL) return null;
        targetSearchTimer = 0;

        cachedTarget = findTarget(level, origin);
        if (cachedTarget != null) {
            losTimer = 0; // force immediate LoS check for the new target
        }
        return cachedTarget;
    }

    // ── Targeting ────────────────────────────────────────────────────────────

    /** Returns true if the mob is a valid turret target: implements Enemy but is not an Enderman. */
    private static boolean isValidTarget(Mob m) {
        if (m instanceof net.minecraft.world.entity.monster.EnderMan) return false;
        return m instanceof Enemy;
    }

    @Nullable
    private Mob findTarget(ServerLevel level, Vec3 origin) {
        AABB searchBox = AABB.ofSize(origin, RANGE * 2, RANGE * 2, RANGE * 2);
        return level.getEntitiesOfClass(Mob.class, searchBox)
            .stream()
            .filter(SkullBeehiveBlockEntity::isValidTarget)
            .filter(m -> origin.distanceTo(m.position().add(0, m.getBbHeight() * 0.5, 0)) <= RANGE)
            .min(Comparator.comparingDouble(
                m -> origin.distanceToSqr(m.position().add(0, m.getBbHeight() * 0.5, 0))
            ))
            .orElse(null);
    }

    /**
     * Iteratively predict where the mob will be when the arrow arrives.
     * Uses distance/speed to estimate travel time — never returns null.
     * Accounts for mob velocity (horizontal only) and gravity compensation.
     */
    private Vec3 predictTarget(Mob target, Vec3 origin, double arrowSpeed) {
        Vec3 mobCenter = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 mobVel    = new Vec3(target.getDeltaMovement().x, 0, target.getDeltaMovement().z);

        Vec3 predicted = mobCenter;
        for (int iter = 0; iter < 6; iter++) {
            double dx = predicted.x - origin.x;
            double dz = predicted.z - origin.z;
            double hDist = Math.sqrt(dx * dx + dz * dz);
            double tEst  = Math.max(1.0, hDist / (arrowSpeed * 0.95));
            predicted = mobCenter.add(mobVel.scale(tEst));
        }
        return predicted;
    }

    /**
     * Compute the launch direction that will land an arrow at {@code target}
     * from {@code origin} at the given speed, compensating for gravity.
     *
     * Gravity compensation: assume travel time ≈ hDist / (speed × 0.95) ticks;
     * aim this many blocks higher to offset the accumulated drop.
     */
    private static Vec3 solveAimDir(Vec3 origin, Vec3 target, double speed) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        double tEst  = hDist / (speed * 0.95) + 1.0;
        // Accumulated gravity drop ≈ sum(0.05 * 0.99^i for i=0..t-1) * t/2 ≈ 0.025 * t^2
        double gravComp = 0.025 * tEst * tEst;
        Vec3 aimTarget = new Vec3(target.x, target.y + gravComp, target.z);
        return aimTarget.subtract(origin).normalize();
    }

    // ── Weapon selection ─────────────────────────────────────────────────────

    /**
     * Returns the slot index of the best weapon to fire, or -1 if none are ready.
     * Best = not excluded, reload finished, least damaged (most durability remaining).
     * Weapons with ≤ 1 durability remaining are excluded with a click sound.
     */
    private int pickWeapon(ServerLevel level) {
        int bestSlot       = -1;
        int bestDurability = -1;

        for (int i = WEAPON_START; i < WEAPON_END; i++) {
            if (weaponExcluded[i]) continue;
            if (weaponReloadTimes[i] > 0) continue;
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty() || !isWeapon(stack)) continue;

            int durLeft = stack.getMaxDamage() - stack.getDamageValue();
            if (durLeft <= 1) {
                // Guard: would break on next shot — exclude it.
                weaponExcluded[i] = true;
                level.playSound(null, worldPosition, SoundEvents.DISPENSER_FAIL,
                    SoundSource.BLOCKS, 0.8f, 1.2f);
                continue;
            }

            if (bestSlot < 0 || durLeft > bestDurability) {
                bestSlot       = i;
                bestDurability = durLeft;
            }
        }
        return bestSlot;
    }

    // ── Arrow retrieval ──────────────────────────────────────────────────────

    /** Picks one arrow at random from the arrow storage and removes it. */
    private ItemStack takeRandomArrow() {
        List<Integer> slots = new ArrayList<>();
        for (int i = ARROW_STORAGE_START; i < ARROW_STORAGE_END; i++) {
            if (!inventory.get(i).isEmpty()) slots.add(i);
        }
        if (slots.isEmpty()) return ItemStack.EMPTY;
        int idx   = level.random.nextInt(slots.size());
        int slot  = slots.get(idx);
        ItemStack s    = inventory.get(slot);
        ItemStack taken = s.copyWithCount(1);
        s.shrink(1);
        if (s.isEmpty()) inventory.set(slot, ItemStack.EMPTY);
        return taken;
    }

    /** Removes 1 item from the first non-empty slot in [start, end). */
    private void consumeFromStorage(int start, int end) {
        for (int i = start; i < end; i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty()) {
                s.shrink(1);
                if (s.isEmpty()) inventory.set(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    // ── Firing ───────────────────────────────────────────────────────────────

    /** Spawn and configure the arrow entity. */
    private void fireArrow(
        ServerLevel level, Vec3 origin, Vec3 targetPos,
        ItemStack bow, ItemStack arrowStack, double arrowSpeed
    ) {
        // Create the appropriate arrow type, attributed to the owner player if online.
        Player owner = (ownerUUID != null) ? level.getPlayerByUUID(ownerUUID) : null;
        AbstractArrow arrow;
        if (owner != null) {
            // Use player-based constructor so vanilla kill-credit / stats work correctly.
            // Arrow spawns at player position by default; we move it to the turret below.
            if (arrowStack.getItem() instanceof SpectralArrowItem) {
                arrow = new SpectralArrow(level, owner, arrowStack, bow);
            } else {
                arrow = new Arrow(level, owner, arrowStack, bow);
            }
            arrow.setPos(origin.x, origin.y, origin.z);
        } else {
            if (arrowStack.getItem() instanceof SpectralArrowItem) {
                arrow = new SpectralArrow(level, origin.x, origin.y, origin.z, arrowStack, bow);
            } else {
                arrow = new Arrow(level, origin.x, origin.y, origin.z, arrowStack, bow);
            }
        }

        // Doubled base damage + Power enchantment bonus.
        double baseDamage = 2.0;
        int powerLevel = getEnchantLevel(bow, Enchantments.POWER);
        if (powerLevel > 0) baseDamage += 0.5 * powerLevel + 0.5;
        arrow.setBaseDamage(baseDamage * 2.0);

        // Punch knockback — setKnockback removed in 1.21.11; encode via delta movement instead.
        // TODO: revisit if knockback API is re-exposed.

        // Flame.
        int flame = getEnchantLevel(bow, Enchantments.FLAME);
        if (flame > 0) arrow.igniteForSeconds(5);

        // Critical hit (full charge = always critical).
        arrow.setCritArrow(true);

        // No pickup.
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        // Aim and launch.
        Vec3 aimDir = solveAimDir(origin, targetPos, arrowSpeed);
        arrow.shoot(aimDir.x, aimDir.y, aimDir.z, (float) arrowSpeed, 0f);

        level.addFreshEntity(arrow);
    }

    // ── Receiver drain ───────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ItemPredicate { boolean test(ItemStack stack); }

    /**
     * Moves as many items as possible from {@code receiverSlot} into the storage
     * range [storageStart, storageEnd), respecting the total {@code maxTotal} cap.
     */
    private void drainReceiver(
        int receiverSlot, int storageStart, int storageEnd,
        int maxTotal, ItemPredicate predicate
    ) {
        ItemStack receiver = inventory.get(receiverSlot);
        if (receiver.isEmpty() || !predicate.test(receiver)) return;

        int available = maxTotal - countStorage(storageStart, storageEnd);
        if (available <= 0) return;

        int toMove    = Math.min(receiver.getCount(), available);
        ItemStack ref = receiver.copyWithCount(toMove); // what we'll move

        // Fill matching existing stacks first.
        for (int i = storageStart; i < storageEnd && !ref.isEmpty(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, ref)) {
                int space = s.getMaxStackSize() - s.getCount();
                if (space > 0) {
                    int add = Math.min(space, ref.getCount());
                    s.grow(add);
                    ref.shrink(add);
                }
            }
        }
        // Then fill empty slots.
        for (int i = storageStart; i < storageEnd && !ref.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) {
                int put = Math.min(ref.getCount(), ref.getMaxStackSize());
                inventory.set(i, ref.copyWithCount(put));
                ref.shrink(put);
            }
        }

        int moved = toMove - ref.getCount();
        if (moved > 0) {
            receiver.shrink(moved);
            if (receiver.isEmpty()) inventory.set(receiverSlot, ItemStack.EMPTY);
            setChanged();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    int countStorage(int start, int end) {
        int total = 0;
        for (int i = start; i < end; i++) total += inventory.get(i).getCount();
        return total;
    }

    static boolean isWeapon(ItemStack stack) {
        return stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem;
    }

    static boolean isArrow(ItemStack stack) {
        return stack.getItem() instanceof ArrowItem;
    }

    /** Returns the reload time (ticks) for the given weapon. */
    private static int getReloadTicks(ItemStack stack) {
        if (stack.getItem() instanceof CrossbowItem) {
            return CrossbowItem.getChargeDuration(stack, null);
        }
        return BowItem.MAX_DRAW_DURATION; // 20 ticks for all bows
    }

    /** Base arrow velocity (blocks/tick) at full charge, before doubling. */
    private static double getArrowSpeed(ItemStack stack) {
        if (stack.getItem() instanceof CrossbowItem) return BASE_XBOW_V;
        return BASE_BOW_V;
    }

    /** Returns the enchantment level on {@code stack} for the given vanilla enchantment key. */
    private int getEnchantLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        return WnirEnchantments.getLevel(stack, key);
    }

    /**
     * Ray-cast from {@code from} to {@code to} in the block world.
     * Returns true if there is no opaque block between the two points.
     */
    private static boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty());
        HitResult result = level.clip(ctx);
        return result.getType() == HitResult.Type.MISS;
    }
}
