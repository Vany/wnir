package com.wnir;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Custom biome source for the wnir:personal dimension.
 *
 * Regions: each player owns a REGION_WIDTH-block strip on the X axis.
 *   Region N occupies X in [N * REGION_WIDTH, (N+1) * REGION_WIDTH).
 *   REGION_BIOMES maps region index → biome key; populated by PersonalDimensionManager at load time.
 *
 * Codec takes a list of all possible overworld biomes so that BiomeSource.possibleBiomes()
 * is non-empty and feature/structure generation works correctly.
 */
public class PersonalBiomeSource extends BiomeSource {

    /** Width of one player's region in world blocks. */
    static final int REGION_WIDTH = 4096;
    /** REGION_WIDTH converted to biome coordinate space (4:1 ratio). */
    private static final int REGION_BIOME_WIDTH = REGION_WIDTH / 4;

    /** Static runtime map: region index → assigned biome key. Thread-safe.
     *  Populated by PersonalDimensionManager on load; updated on first player visit. */
    static final ConcurrentHashMap<Integer, ResourceKey<Biome>> REGION_BIOMES = new ConcurrentHashMap<>();

    /** All eligible biomes listed in dimension JSON; used by collectPossibleBiomes(). */
    private final HolderSet<Biome> possibleBiomesHolder;

    /** O(1) lookup from ResourceKey<Biome> to Holder<Biome> for runtime getNoiseBiome. */
    private final Map<ResourceKey<Biome>, Holder<Biome>> keyToHolder;

    /** Fallback biome (first in list, expected to be minecraft:plains). */
    private final Holder<Biome> fallback;

    public static final MapCodec<PersonalBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            RegistryCodecs.homogeneousList(Registries.BIOME)
                .fieldOf("possible_biomes")
                .forGetter(s -> s.possibleBiomesHolder)
        ).apply(instance, PersonalBiomeSource::new)
    );

    PersonalBiomeSource(HolderSet<Biome> possibleBiomes) {
        this.possibleBiomesHolder = possibleBiomes;

        Map<ResourceKey<Biome>, Holder<Biome>> map = new HashMap<>();
        Holder<Biome> first = null;
        for (Holder<Biome> h : possibleBiomes) {
            if (first == null) first = h;
            h.unwrapKey().ifPresent(key -> map.put(key, h));
        }
        this.keyToHolder = map;
        this.fallback = first != null ? first
            : possibleBiomes.stream().findFirst().orElseThrow(
                () -> new IllegalStateException("wnir:personal biome source has no possible_biomes"));
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return possibleBiomesHolder.stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ, Climate.Sampler sampler) {
        // biomeX is already in biome coordinates (1 unit = 4 blocks)
        int regionIndex = Math.floorDiv(biomeX, REGION_BIOME_WIDTH);
        ResourceKey<Biome> key = REGION_BIOMES.get(regionIndex);
        if (key != null) {
            Holder<Biome> holder = keyToHolder.get(key);
            if (holder != null) return holder;
        }
        return fallback;
    }

    /** Returns a ResourceKey for a biome by Identifier. Used when building the ResourceKey at region assignment. */
    static ResourceKey<Biome> biomeKey(Identifier id) {
        return ResourceKey.create(Registries.BIOME, id);
    }
}
