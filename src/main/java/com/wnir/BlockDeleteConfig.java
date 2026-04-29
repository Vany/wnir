package com.wnir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;

public final class BlockDeleteConfig {

    public static Set<Identifier> BLOCKS_TO_DELETE = Collections.emptySet();
    public static Set<BlockPos>   POSITIONS_TO_DELETE = Collections.emptySet();

    static final Set<Identifier> DELETED_IDS_THIS_SESSION = ConcurrentHashMap.newKeySet();
    static final Set<BlockPos>   DELETED_POS_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private static final String FILE_NAME = "wnir_block_delete.toml";

    private static final String DEFAULT_CONTENT = """
        # Emergency block deletion — runs whenever the block's chunk loads.
        # Two formats per line (mix freely, '#' = comment):
        #   namespace:block_id      — deletes ALL blocks of this type everywhere (auto-removed from config once found)
        #   X,Y,Z                   — deletes whatever block sits at these exact coordinates (auto-removed once deleted)
        # Both entries are removed from this file automatically when the block is found and deleted.
        # Reload requires a server restart. Coordinates use standard Minecraft XYZ (Y is height).
        #
        # othermood:crashing_block
        # 64,51,1
        """;

    private BlockDeleteConfig() {}

    public static void load() {
        DELETED_IDS_THIS_SESSION.clear();
        DELETED_POS_THIS_SESSION.clear();
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);

        if (!Files.exists(path)) {
            try {
                Files.writeString(path, DEFAULT_CONTENT);
            } catch (IOException e) {
                WnirMod.LOGGER.error("Failed to create {}: {}", FILE_NAME, e.getMessage());
            }
            BLOCKS_TO_DELETE = Collections.emptySet();
            POSITIONS_TO_DELETE = Collections.emptySet();
            return;
        }

        Set<Identifier> ids = new LinkedHashSet<>();
        Set<BlockPos>   positions = new LinkedHashSet<>();
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                BlockPos pos = tryParsePos(line);
                if (pos != null) {
                    positions.add(pos);
                    continue;
                }

                Identifier id = Identifier.tryParse(line);
                if (id != null) {
                    ids.add(id);
                    continue;
                }

                WnirMod.LOGGER.warn("[BlockDeleteConfig] Skipping unrecognised entry: {}", line);
            }
        } catch (IOException e) {
            WnirMod.LOGGER.error("Failed to read {}: {}", FILE_NAME, e.getMessage());
        }

        BLOCKS_TO_DELETE   = Collections.unmodifiableSet(ids);
        POSITIONS_TO_DELETE = Collections.unmodifiableSet(positions);

        if (!ids.isEmpty())
            WnirMod.LOGGER.info("[BlockDeleteConfig] Will delete block type(s) on chunk load: {}", ids);
        if (!positions.isEmpty())
            WnirMod.LOGGER.info("[BlockDeleteConfig] Will delete block(s) at position(s) on chunk load: {}", positions);
    }

    /** Call on server stop. Rewrites the config removing entries that were actually deleted. */
    public static void pruneDeleted() {
        if (DELETED_IDS_THIS_SESSION.isEmpty() && DELETED_POS_THIS_SESSION.isEmpty()) return;

        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            WnirMod.LOGGER.error("[BlockDeleteConfig] Could not read config for pruning: {}", e.getMessage());
            return;
        }

        List<String> kept = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                BlockPos pos = tryParsePos(line);
                if (pos != null && DELETED_POS_THIS_SESSION.contains(pos)) {
                    WnirMod.LOGGER.info("[BlockDeleteConfig] Removing position {} from config", line);
                    continue;
                }
                Identifier id = Identifier.tryParse(line);
                if (id != null && DELETED_IDS_THIS_SESSION.contains(id)) {
                    WnirMod.LOGGER.info("[BlockDeleteConfig] Removing block type {} from config", id);
                    continue;
                }
            }
            kept.add(raw);
        }

        while (kept.size() > 1 && kept.get(kept.size() - 1).isBlank())
            kept.remove(kept.size() - 1);

        Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.writeString(tmp, String.join(System.lineSeparator(), kept) + System.lineSeparator());
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            WnirMod.LOGGER.error("[BlockDeleteConfig] Failed to write pruned config: {}", e.getMessage());
        }
    }

    private static BlockPos tryParsePos(String line) {
        String[] parts = line.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                Integer.parseInt(parts[0].strip()),
                Integer.parseInt(parts[1].strip()),
                Integer.parseInt(parts[2].strip())
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
