package com.wnir;

import com.google.gson.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player personal dimension regions.
 *
 * Persistence: JSON file at &lt;world-root&gt;/wnir_personal_dimensions.json
 * Format: { "next": N, "players": { "uuid": regionIndex }, "biomes": { "regionIndex": "biome:id" } }
 *
 * On load, populates PersonalBiomeSource.REGION_BIOMES for the biome source to use.
 * Plain-file approach (like ChunkLoaderData) to avoid SavedData API version concerns.
 */
public class PersonalDimensionManager {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String FILE_NAME = "wnir_personal_dimensions.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path filePath;
    private final Map<UUID, Integer> playerRegions = new ConcurrentHashMap<>();
    private final Map<Integer, Identifier> regionBiomeIds = new ConcurrentHashMap<>();
    private int nextRegionIndex = 0;

    private PersonalDimensionManager(Path filePath) {
        this.filePath = filePath;
    }

    // ── Singleton management ─────────────────────────────────────────────

    private static volatile PersonalDimensionManager instance;

    public static PersonalDimensionManager get(MinecraftServer server) {
        if (instance == null) {
            synchronized (PersonalDimensionManager.class) {
                if (instance == null) {
                    Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
                    PersonalDimensionManager mgr = new PersonalDimensionManager(path);
                    mgr.load(server);
                    instance = mgr;
                }
            }
        }
        return instance;
    }

    public static void reset() {
        instance = null;
    }

    // ── Region assignment ────────────────────────────────────────────────

    /**
     * Returns the existing region index for this player, or assigns a new one.
     * biomeId is the biome at the teleporter's location and is used only on first assignment.
     * Also updates PersonalBiomeSource.REGION_BIOMES so the dimension can render correctly.
     */
    public int getOrCreateRegion(UUID playerId, Identifier biomeId) {
        if (!playerRegions.containsKey(playerId)) {
            int index = nextRegionIndex++;
            playerRegions.put(playerId, index);
            regionBiomeIds.put(index, biomeId);
            PersonalBiomeSource.REGION_BIOMES.put(index, PersonalBiomeSource.biomeKey(biomeId));
            save();
            LOGGER.info("Assigned personal dimension region {} ({}) to player {}", index, biomeId, playerId);
        }
        return playerRegions.get(playerId);
    }

    /** World-space X of the center of this player's region. */
    public int getRegionCenterX(UUID playerId) {
        int index = playerRegions.getOrDefault(playerId, 0);
        return index * PersonalBiomeSource.REGION_WIDTH + PersonalBiomeSource.REGION_WIDTH / 2;
    }

    public Identifier getRegionBiome(UUID playerId) {
        int index = playerRegions.getOrDefault(playerId, -1);
        return regionBiomeIds.getOrDefault(index, Identifier.parse("minecraft:plains"));
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private void load(MinecraftServer server) {
        if (!Files.exists(filePath)) return;
        try (Reader reader = Files.newBufferedReader(filePath)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            nextRegionIndex = root.has("next") ? root.get("next").getAsInt() : 0;

            if (root.has("players")) {
                JsonObject players = root.getAsJsonObject("players");
                for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                    playerRegions.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
                }
            }
            if (root.has("biomes")) {
                JsonObject biomes = root.getAsJsonObject("biomes");
                for (Map.Entry<String, JsonElement> e : biomes.entrySet()) {
                    int idx = Integer.parseInt(e.getKey());
                    Identifier biomeId = Identifier.parse(e.getValue().getAsString());
                    regionBiomeIds.put(idx, biomeId);
                    // Populate the static biome source map so the dimension generates correctly
                    PersonalBiomeSource.REGION_BIOMES.put(idx, PersonalBiomeSource.biomeKey(biomeId));
                }
            }
            LOGGER.info("Loaded {} personal dimension regions", playerRegions.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load personal dimension data", e);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("next", nextRegionIndex);

        JsonObject players = new JsonObject();
        playerRegions.forEach((uuid, idx) -> players.addProperty(uuid.toString(), idx));
        root.add("players", players);

        JsonObject biomes = new JsonObject();
        regionBiomeIds.forEach((idx, biomeId) -> biomes.addProperty(String.valueOf(idx), biomeId.toString()));
        root.add("biomes", biomes);

        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(root, writer);
        } catch (Exception e) {
            LOGGER.error("Failed to write personal dimension data", e);
            return;
        }
        try {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("Failed to finalize personal dimension data file", e);
        }
    }
}
