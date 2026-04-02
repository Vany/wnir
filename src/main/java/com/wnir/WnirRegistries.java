package com.wnir;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class WnirRegistries {

    private WnirRegistries() {}

    // ── Mob effects ──────────────────────────────────────────────────────

    private static final DeferredRegister<MobEffect> MOB_EFFECTS =
        DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, WnirMod.MOD_ID);

    public static final Holder<MobEffect> MARTIAL_LIGHTNING =
        MOB_EFFECTS.register("martial_lightning", () -> WnirEffects.marker(0x00BFFF));
    public static final Holder<MobEffect> STREAMER_PROTECT =
        MOB_EFFECTS.register("streamer_protect", () -> WnirEffects.marker(0xFFD700));
    public static final Holder<MobEffect> HOMING_ARCHERY =
        MOB_EFFECTS.register("homing_archery", () -> WnirEffects.marker(0x9B30FF));
    public static final Holder<MobEffect> MEGA_CHANTER =
        MOB_EFFECTS.register("mega_chanter", () -> WnirEffects.marker(0x00FF99));
    public static final Holder<MobEffect> INSANE_LIGHT =
        MOB_EFFECTS.register("insane_light", () -> WnirEffects.marker(0xFFFF44));
    public static final Holder<MobEffect> DEAD_BLOW =
        MOB_EFFECTS.register("dead_blow", () -> WnirEffects.marker(0xFF2200));

    // ── Potions ──────────────────────────────────────────────────────────

    private static final DeferredRegister<Potion> POTIONS =
        DeferredRegister.create(BuiltInRegistries.POTION, WnirMod.MOD_ID);

    public static final DeferredHolder<Potion, Potion> MEGA_CHANTER_POTION =
        POTIONS.register("mega_chanter", () ->
            new Potion("mega_chanter", new MobEffectInstance(MEGA_CHANTER, 3600, 0))
        );

    public static final DeferredHolder<Potion, Potion> MARTIAL_LIGHTNING_POTION =
        POTIONS.register("martial_lightning", () ->
            new Potion("martial_lightning", new MobEffectInstance(MARTIAL_LIGHTNING, 3600, 0))
        );

    // ── Block / Item registers (declared early; ITEMS needed before fluid bucket) ──

    private static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BuiltInRegistries.BLOCK, WnirMod.MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(BuiltInRegistries.ITEM, WnirMod.MOD_ID);

    // ── Fluids ───────────────────────────────────────────────────────────

    private static final DeferredRegister<FluidType> FLUID_TYPES =
        DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, WnirMod.MOD_ID);
    private static final DeferredRegister<Fluid> FLUIDS =
        DeferredRegister.create(BuiltInRegistries.FLUID, WnirMod.MOD_ID);

    // Set in static block below after all supplier fields are initialized.
    static BaseFlowingFluid.Properties MAGIC_CELLULOSE_FLUID_PROPS;

    public static final DeferredHolder<FluidType, FluidType> MAGIC_CELLULOSE_TYPE =
        FLUID_TYPES.register("magic_cellulose", () -> new FluidType(FluidType.Properties.create()));

    public static final DeferredHolder<Fluid, FlowingFluid> MAGIC_CELLULOSE_STILL =
        FLUIDS.register("magic_cellulose", () -> new BaseFlowingFluid.Source(MAGIC_CELLULOSE_FLUID_PROPS));
    public static final DeferredHolder<Fluid, FlowingFluid> MAGIC_CELLULOSE_FLOWING =
        FLUIDS.register("magic_cellulose_flowing", () -> new BaseFlowingFluid.Flowing(MAGIC_CELLULOSE_FLUID_PROPS));

    // BLOCKS registers after FLUIDS, so MAGIC_CELLULOSE_STILL.get() is safe in this lambda.
    public static final DeferredHolder<Block, LiquidBlock> MAGIC_CELLULOSE_BLOCK =
        BLOCKS.register("magic_cellulose_block", () -> new LiquidBlock(
            MAGIC_CELLULOSE_STILL.get(),
            BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)
                .setId(ResourceKey.create(Registries.BLOCK, id("magic_cellulose_block")))
        ));

    public static final Supplier<MagicCelluloseBucketItem> MAGIC_CELLULOSE_BUCKET =
        ITEMS.register("magic_cellulose_bucket", () ->
            new MagicCelluloseBucketItem(MAGIC_CELLULOSE_STILL.get(),
                new Item.Properties()
                    .craftRemainder(Items.BUCKET)
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id("magic_cellulose_bucket")))
            )
        );

    static {
        MAGIC_CELLULOSE_FLUID_PROPS = new BaseFlowingFluid.Properties(
            MAGIC_CELLULOSE_TYPE, MAGIC_CELLULOSE_STILL, MAGIC_CELLULOSE_FLOWING
        ).block(MAGIC_CELLULOSE_BLOCK).bucket(MAGIC_CELLULOSE_BUCKET);
    }

    // ── Block registration ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(
            (net.minecraft.core.Registry<BlockEntityType<?>>) (net.minecraft.core.Registry<?>) BuiltInRegistries.BLOCK_ENTITY_TYPE,
            WnirMod.MOD_ID
        );

    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, WnirMod.MOD_ID);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final DeferredRegister<com.mojang.serialization.MapCodec<? extends BiomeSource>> BIOME_SOURCES =
        DeferredRegister.create(
            (net.minecraft.core.Registry<com.mojang.serialization.MapCodec<? extends BiomeSource>>)
            (net.minecraft.core.Registry<?>) BuiltInRegistries.BIOME_SOURCE,
            WnirMod.MOD_ID
        );

    @SuppressWarnings("unused")
    private static final java.util.function.Supplier<com.mojang.serialization.MapCodec<PersonalBiomeSource>>
        PERSONAL_BIOME_SOURCE = BIOME_SOURCES.register("personal", () -> PersonalBiomeSource.CODEC);

    @SuppressWarnings("unchecked")
    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(
            (net.minecraft.core.Registry<RecipeSerializer<?>>) (net.minecraft.core.Registry<?>) BuiltInRegistries.RECIPE_SERIALIZER,
            WnirMod.MOD_ID
        );

    public static final Supplier<net.minecraft.world.item.crafting.CustomRecipe.Serializer<AccumulatorCombineRecipe>> ACCUMULATOR_COMBINE_RECIPE =
        RECIPE_SERIALIZERS.register("accumulator_combine", () -> AccumulatorCombineRecipe.SERIALIZER);

    public static final Supplier<net.minecraft.world.item.crafting.CustomRecipe.Serializer<KelpCompressionRecipe>> KELP_COMPRESSION_RECIPE =
        RECIPE_SERIALIZERS.register("kelp_compression", () -> KelpCompressionRecipe.SERIALIZER);

    public static final Supplier<net.minecraft.world.item.crafting.CustomRecipe.Serializer<SpawnerCraftingRecipe>> SPAWNER_CRAFTING_RECIPE =
        RECIPE_SERIALIZERS.register("spawner_crafting", () -> SpawnerCraftingRecipe.SERIALIZER);

    @SuppressWarnings("unchecked")
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(
            (net.minecraft.core.Registry<MenuType<?>>) (net.minecraft.core.Registry<?>) BuiltInRegistries.MENU,
            WnirMod.MOD_ID
        );

    private record BlockBundle<B extends Block, E extends BlockEntity>(
        Supplier<B> block,
        Supplier<BlockItem> item,
        Supplier<BlockEntityType<E>> entity
    ) {}

    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, name);
    }

    private static <B extends Block, E extends BlockEntity> BlockBundle<B, E> registerBlock(
        String name,
        Function<BlockBehaviour.Properties, B> blockFactory,
        BlockEntityType.BlockEntitySupplier<E> entityFactory,
        BlockBehaviour.Properties props
    ) {
        return registerBlock(name, blockFactory, entityFactory, props, (b, p) -> new WnirBlockItem(b, p, name));
    }

    private static <B extends Block, E extends BlockEntity> BlockBundle<B, E> registerBlock(
        String name,
        Function<BlockBehaviour.Properties, B> blockFactory,
        BlockEntityType.BlockEntitySupplier<E> entityFactory,
        BlockBehaviour.Properties props,
        BiFunction<B, Item.Properties, BlockItem> itemFactory
    ) {
        var blockKey = ResourceKey.create(Registries.BLOCK, id(name));
        var itemKey  = ResourceKey.create(Registries.ITEM,  id(name));

        Supplier<B> block = BLOCKS.register(name, () -> blockFactory.apply(props.setId(blockKey)));
        Supplier<BlockItem> item = ITEMS.register(name, () ->
            itemFactory.apply(block.get(), new Item.Properties().setId(itemKey))
        );
        Supplier<BlockEntityType<E>> entity = BLOCK_ENTITIES.register(
            name, () -> new BlockEntityType<>(entityFactory, Set.of(block.get()))
        );
        return new BlockBundle<>(block, item, entity);
    }

    private record SimpleBlockBundle<B extends Block>(Supplier<B> block, Supplier<BlockItem> item) {}

    private static <B extends Block> SimpleBlockBundle<B> registerSimpleBlock(
        String name,
        Function<BlockBehaviour.Properties, B> blockFactory,
        BlockBehaviour.Properties props
    ) {
        var blockKey = ResourceKey.create(Registries.BLOCK, id(name));
        var itemKey  = ResourceKey.create(Registries.ITEM,  id(name));

        Supplier<B> block = BLOCKS.register(name, () -> blockFactory.apply(props.setId(blockKey)));
        Supplier<BlockItem> item = ITEMS.register(name, () ->
            new WnirBlockItem(block.get(), new Item.Properties().setId(itemKey), name)
        );
        return new SimpleBlockBundle<>(block, item);
    }

    // ── Block instances ──────────────────────────────────────────────────

    private static final SimpleBlockBundle<ChunkLoaderBlock> CHUNK_LOADER =
        registerSimpleBlock("chunk_loader", ChunkLoaderBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).sound(SoundType.METAL));

    private static final BlockBundle<SpawnerAgitatorBlock, SpawnerAgitatorBlockEntity> SPAWNER_AGITATOR =
        registerBlock("spawner_agitator", SpawnerAgitatorBlock::new, SpawnerAgitatorBlockEntity::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).sound(SoundType.METAL).randomTicks());

    private static final SimpleBlockBundle<WardingPostBlock> WARDING_POST =
        registerSimpleBlock("warding_post", WardingPostBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE).strength(0.1f).noOcclusion().randomTicks());

    private static final SimpleBlockBundle<TeleporterInhibitorBlock> TELEPORTER_INHIBITOR =
        registerSimpleBlock("teleporter_inhibitor", TeleporterInhibitorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).sound(SoundType.AMETHYST).strength(0.1f).noOcclusion().randomTicks());

    private static final SimpleBlockBundle<RepellingPostBlock> REPELLING_POST =
        registerSimpleBlock("repelling_post", RepellingPostBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).sound(SoundType.BONE_BLOCK).strength(0.1f).noOcclusion().randomTicks());

    private static final SimpleBlockBundle<LightingPostBlock> LIGHTING_POST =
        registerSimpleBlock("lighting_post", LightingPostBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_YELLOW)
                .sound(SoundType.GLASS)
                .strength(0.3f)
                .noOcclusion()
                .randomTicks()
                .lightLevel(s -> 15));

    private static final SimpleBlockBundle<HurtPostBlock> HURT_POST =
        registerSimpleBlock("hurt_post", HurtPostBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .sound(SoundType.BONE_BLOCK)
                .strength(0.1f)
                .noOcclusion()
                .randomTicks());

    /** Single shared BE type for all warding column blocks. */
    private static final Supplier<BlockEntityType<WardingColumnBlockEntity>> WARDING_COLUMN_BE =
        BLOCK_ENTITIES.register("warding_column", () -> {
            Set<Block> blocks = Set.of(
                WARDING_POST.block.get(), TELEPORTER_INHIBITOR.block.get(),
                REPELLING_POST.block.get(), LIGHTING_POST.block.get(),
                HURT_POST.block.get()
            );
            return new BlockEntityType<>(WardingColumnBlockEntity::create, blocks);
        });

    private static final BlockBundle<EEClockBlock, EEClockBlockEntity> EE_CLOCK =
        registerBlock("ee_clock", EEClockBlock::new, EEClockBlockEntity::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).sound(SoundType.METAL).randomTicks());

    private static final BlockBundle<EEClockBuddingCrystalBlock, EEClockBuddingCrystalBlockEntity> EE_CLOCK_BUDDING_CRYSTAL =
        registerBlock("ee_clock_budding_crystal", EEClockBuddingCrystalBlock::new, EEClockBuddingCrystalBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .sound(SoundType.AMETHYST)
                .strength(1.5f)
                .requiresCorrectToolForDrops());

    private static final BlockBundle<MossyHopperBlock, MossyHopperBlockEntity> MOSSY_HOPPER =
        registerBlock("mossy_hopper", MossyHopperBlock::new, MossyHopperBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .requiresCorrectToolForDrops()
                .strength(3.0f)
                .sound(SoundType.METAL)
                .noOcclusion());

    private static final SimpleBlockBundle<AntiWitherBlock> ANTI_WITHER =
        registerSimpleBlock("antiwither", AntiWitherBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops()
                .strength(50f, 3_600_000f));

    private static final SimpleBlockBundle<PersonalDimensionTeleporterBlock> PERSONAL_DIMENSION_TELEPORTER =
        registerSimpleBlock("personal_dimension_teleporter", PersonalDimensionTeleporterBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.AMETHYST)
                .requiresCorrectToolForDrops()
                .strength(50f, 1_200_000f));

    private static final BlockBundle<TeleporterCrystalBlock, TeleporterCrystalBlockEntity> TELEPORTER_CRYSTAL =
        registerBlock("teleporter_crystal", TeleporterCrystalBlock::new, TeleporterCrystalBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.AMETHYST)
                .strength(1.5f)
                .requiresCorrectToolForDrops());

    private static final BlockBundle<SkullBeehiveBlock, SkullBeehiveBlockEntity> SKULL_BEEHIVE =
        registerBlock("skull_beehive", SkullBeehiveBlock::new, SkullBeehiveBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BROWN)
                .sound(SoundType.WOOD)
                .strength(2.0f)
                .requiresCorrectToolForDrops(),
            (b, p) -> new WnirBlockItem(b, p, "skull_beehive"));

    private static final BlockBundle<AccumulatorBlock, AccumulatorBlockEntity> ACCUMULATOR =
        registerBlock("accumulator", AccumulatorBlock::new, AccumulatorBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE)
                .sound(SoundType.METAL)
                .strength(3.5f)
                .requiresCorrectToolForDrops(),
            (b, p) -> new WnirBlockItem(b, p, "accumulator", WnirBlockItem::accumulatorHeaderLines, null));

    private static final BlockBundle<SteelHopperBlock, SteelHopperBlockEntity> STEEL_HOPPER =
        registerBlock("steel_hopper", SteelHopperBlock::new, SteelHopperBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .requiresCorrectToolForDrops()
                .strength(3.0f)
                .sound(SoundType.METAL)
                .noOcclusion());

    private static final BlockBundle<SpawnerBlock, SpawnerBlockEntity> SPAWNER =
        registerBlock("spawner", SpawnerBlock::new, SpawnerBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .sound(SoundType.METAL)
                .strength(3.5f)
                .requiresCorrectToolForDrops());

    private static final BlockBundle<CelluloserBlock, CelluloserBlockEntity> CELLULOSER =
        registerBlock("celluloser", CelluloserBlock::new, CelluloserBlockEntity::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .sound(SoundType.METAL)
                .strength(3.5f)
                .requiresCorrectToolForDrops(),
            (b, p) -> new WnirBlockItem(b, p, "celluloser", WnirBlockItem::celluloserDataLines));

    // ── Standalone items ─────────────────────────────────────────────────

    public static final Supplier<SeedBundleItem> SEED_BUNDLE_ITEM =
        ITEMS.register("seed_bundle", () ->
            new SeedBundleItem(
                new Item.Properties()
                    .stacksTo(1)
                    .component(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                    .setId(ResourceKey.create(Registries.ITEM, id("seed_bundle")))
            )
        );

    public static final Supplier<ProfessionalSeedBundleItem> PROFESSIONAL_SEED_BUNDLE_ITEM =
        ITEMS.register("professional_seed_bundle", () ->
            new ProfessionalSeedBundleItem(
                new Item.Properties()
                    .stacksTo(1)
                    .component(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                    .setId(ResourceKey.create(Registries.ITEM, id("professional_seed_bundle")))
            )
        );

    public static final Supplier<BlueStickyTapeItem> BLUE_STICKY_TAPE_ITEM =
        ITEMS.register("blue_sticky_tape", () ->
            new BlueStickyTapeItem(
                new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id("blue_sticky_tape")))
            )
        );

    public static final Supplier<MouseyCompassItem> MOUSEY_COMPASS_ITEM =
        ITEMS.register("mousey_compass", () ->
            new MouseyCompassItem(
                new Item.Properties()
                    .stacksTo(1)
                    .component(DataComponents.LODESTONE_TRACKER,
                        new LodestoneTracker(java.util.Optional.empty(), false))
                    .setId(ResourceKey.create(Registries.ITEM, id("mousey_compass")))
            )
        );

    public static final Supplier<WirelessFuelItem> WIRELESS_FUEL_ITEM =
        ITEMS.register("wireless_fuel", () ->
            new WirelessFuelItem(
                new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id("wireless_fuel")))
            )
        );

    // ── Public accessors ─────────────────────────────────────────────────

    public static final Supplier<MenuType<MossyHopperMenu>> MOSSY_HOPPER_MENU =
        MENU_TYPES.register("mossy_hopper", () -> new MenuType<>(MossyHopperMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<SteelHopperMenu>> STEEL_HOPPER_MENU =
        MENU_TYPES.register("steel_hopper", () -> new MenuType<>(SteelHopperMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<EEClockBuddingCrystalMenu>> EE_CLOCK_BUDDING_CRYSTAL_MENU =
        MENU_TYPES.register("ee_clock_budding_crystal", () -> new MenuType<>(EEClockBuddingCrystalMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<TeleporterCrystalMenu>> TELEPORTER_CRYSTAL_MENU =
        MENU_TYPES.register("teleporter_crystal", () -> new MenuType<>(TeleporterCrystalMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<SkullBeehiveMenu>> SKULL_BEEHIVE_MENU =
        MENU_TYPES.register("skull_beehive", () -> new MenuType<>(SkullBeehiveMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<CelluloserMenu>> CELLULOSER_MENU =
        MENU_TYPES.register("celluloser", () -> new MenuType<>(CelluloserMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<BlockEntityType<MossyHopperBlockEntity>> MOSSY_HOPPER_BE = MOSSY_HOPPER.entity;
    public static final Supplier<BlockItem> MOSSY_HOPPER_ITEM = MOSSY_HOPPER.item;

    public static final Supplier<BlockEntityType<SteelHopperBlockEntity>> STEEL_HOPPER_BE = STEEL_HOPPER.entity;
    public static final Supplier<BlockItem> STEEL_HOPPER_ITEM = STEEL_HOPPER.item;

    public static final Supplier<BlockEntityType<SpawnerAgitatorBlockEntity>> SPAWNER_AGITATOR_BE = SPAWNER_AGITATOR.entity;
    public static final Supplier<BlockEntityType<WardingColumnBlockEntity>> WARDING_COLUMN_BLOCK_ENTITY = WARDING_COLUMN_BE;
    public static final Supplier<BlockEntityType<EEClockBlockEntity>> EE_CLOCK_BE = EE_CLOCK.entity;
    public static final Supplier<EEClockBlock> EE_CLOCK_BLOCK = EE_CLOCK.block;
    public static final Supplier<BlockEntityType<EEClockBuddingCrystalBlockEntity>> EE_CLOCK_BUDDING_CRYSTAL_BE = EE_CLOCK_BUDDING_CRYSTAL.entity;
    public static final Supplier<EEClockBuddingCrystalBlock> EE_CLOCK_BUDDING_CRYSTAL_BLOCK = EE_CLOCK_BUDDING_CRYSTAL.block;
    public static final Supplier<BlockEntityType<TeleporterCrystalBlockEntity>> TELEPORTER_CRYSTAL_BE = TELEPORTER_CRYSTAL.entity;
    public static final Supplier<TeleporterCrystalBlock> TELEPORTER_CRYSTAL_BLOCK = TELEPORTER_CRYSTAL.block;
    public static final Supplier<PersonalDimensionTeleporterBlock> PERSONAL_DIMENSION_TELEPORTER_BLOCK = PERSONAL_DIMENSION_TELEPORTER.block;

    public static final Supplier<BlockEntityType<SkullBeehiveBlockEntity>> SKULL_BEEHIVE_BE = SKULL_BEEHIVE.entity;
    public static final Supplier<SkullBeehiveBlock> SKULL_BEEHIVE_BLOCK = SKULL_BEEHIVE.block;
    public static final Supplier<BlockItem> SKULL_BEEHIVE_ITEM = SKULL_BEEHIVE.item;

    public static final Supplier<BlockEntityType<SpawnerBlockEntity>> SPAWNER_BE = SPAWNER.entity;
    public static final Supplier<BlockItem> SPAWNER_ITEM = SPAWNER.item;

    public static final Supplier<BlockEntityType<CelluloserBlockEntity>> CELLULOSER_BE = CELLULOSER.entity;
    public static final Supplier<BlockItem> CELLULOSER_ITEM = CELLULOSER.item;

    public static final Supplier<BlockEntityType<AccumulatorBlockEntity>> ACCUMULATOR_BE = ACCUMULATOR.entity;
    public static final Supplier<AccumulatorBlock> ACCUMULATOR_BLOCK = ACCUMULATOR.block;
    public static final Supplier<BlockItem> ACCUMULATOR_ITEM = ACCUMULATOR.item;

    public static final Supplier<BlockItem> CHUNK_LOADER_ITEM = CHUNK_LOADER.item;
    public static final Supplier<BlockItem> SPAWNER_AGITATOR_ITEM = SPAWNER_AGITATOR.item;
    public static final Supplier<BlockItem> WARDING_POST_ITEM = WARDING_POST.item;
    public static final Supplier<BlockItem> TELEPORTER_INHIBITOR_ITEM = TELEPORTER_INHIBITOR.item;
    public static final Supplier<BlockItem> REPELLING_POST_ITEM = REPELLING_POST.item;
    public static final Supplier<BlockItem> LIGHTING_POST_ITEM = LIGHTING_POST.item;
    public static final Supplier<BlockItem> HURT_POST_ITEM = HURT_POST.item;
    public static final Supplier<BlockItem> ANTI_WITHER_ITEM = ANTI_WITHER.item;
    public static final Supplier<BlockItem> EE_CLOCK_ITEM = EE_CLOCK.item;
    public static final Supplier<BlockItem> EE_CLOCK_BUDDING_CRYSTAL_ITEM = EE_CLOCK_BUDDING_CRYSTAL.item;
    public static final Supplier<BlockItem> PERSONAL_DIMENSION_TELEPORTER_ITEM = PERSONAL_DIMENSION_TELEPORTER.item;
    public static final Supplier<BlockItem> TELEPORTER_CRYSTAL_ITEM = TELEPORTER_CRYSTAL.item;

    // ── Creative tab ─────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private static final Supplier<CreativeModeTab> WNIR_TAB =
        CREATIVE_TABS.register("wnir", () ->
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.wnir"))
                .icon(() -> CHUNK_LOADER_ITEM.get().getDefaultInstance())
                .displayItems((params, output) -> {
                    output.accept(CHUNK_LOADER_ITEM.get());
                    output.accept(SPAWNER_AGITATOR_ITEM.get());
                    output.accept(WARDING_POST_ITEM.get());
                    output.accept(TELEPORTER_INHIBITOR_ITEM.get());
                    output.accept(REPELLING_POST_ITEM.get());
                    output.accept(LIGHTING_POST_ITEM.get());
                    output.accept(HURT_POST_ITEM.get());
                    output.accept(ANTI_WITHER_ITEM.get());
                    output.accept(EE_CLOCK_ITEM.get());
                    output.accept(EE_CLOCK_BUDDING_CRYSTAL_ITEM.get());
                    output.accept(TELEPORTER_CRYSTAL_ITEM.get());
                    output.accept(MOSSY_HOPPER_ITEM.get());
                    output.accept(STEEL_HOPPER_ITEM.get());
                    output.accept(PERSONAL_DIMENSION_TELEPORTER_ITEM.get());
                    output.accept(BLUE_STICKY_TAPE_ITEM.get());
                    output.accept(SEED_BUNDLE_ITEM.get());
                    output.accept(PROFESSIONAL_SEED_BUNDLE_ITEM.get());
                    output.accept(MOUSEY_COMPASS_ITEM.get());
                    output.accept(WIRELESS_FUEL_ITEM.get());
                    output.accept(SKULL_BEEHIVE_ITEM.get());
                    output.accept(MAGIC_CELLULOSE_BUCKET.get());
                    output.accept(CELLULOSER_ITEM.get());
                    output.accept(ACCUMULATOR_ITEM.get());
                    output.accept(SPAWNER_ITEM.get());
                })
                .build()
        );

    // ── Registration ─────────────────────────────────────────────────────

    static void register(IEventBus modEventBus) {
        MOB_EFFECTS.register(modEventBus);
        POTIONS.register(modEventBus);
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        BIOME_SOURCES.register(modEventBus);
    }
}
