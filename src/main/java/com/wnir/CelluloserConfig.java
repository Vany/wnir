package com.wnir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Optional item sources for the Celluloser with fixed XP values.
 *
 * Config file: config/wnir_celluloser.toml
 * Created with commented examples on first run.
 *
 * Format:
 *   [sources]
 *   evilcraft:blood = 50
 *   ironsspells:blaze_manual = 500
 *
 * Lines starting with '#' are comments and are ignored.
 * The [sources] section header is optional — all valid key=value lines are parsed.
 * XP values must be positive integers.
 *
 * Loaded once on server start via CelluloserConfig.load(). Items in this map are
 * accepted by the Celluloser in addition to enchanted items.
 */
public final class CelluloserConfig {

    /** Registry-ID → XP value for extra accepted items. Unmodifiable after load(). */
    public static Map<Identifier, Integer> EXTRA_SOURCES =
        Collections.emptyMap();

    private static final String FILE_NAME = "wnir_celluloser.toml";

    private static final String DEFAULT_CONTENT = """
        # Celluloser extra item sources
        # Items listed here are accepted by the Celluloser even without enchantments.
        # The XP value determines processing time (same formula as enchanted books).
        #
        # Format:  item_registry_id = xp_value
        #
        [sources]
        minecraft:player_head = 10000
        evilcraft:origins_of_darkness = 200
        evilcraft:broom = 200
        evilcraft:vengeance_essence = 10000
        ars_nouveau:caster_tome = 400
        waystones:attuned_shard = 100
        """;

    private CelluloserConfig() {}

    /** Load (or create) the config file. Call on server start. */
    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);

        if (!Files.exists(path)) {
            try {
                Files.writeString(path, DEFAULT_CONTENT);
            } catch (IOException e) {
                WnirMod.LOGGER.error(
                    "Failed to create {}: {}",
                    FILE_NAME,
                    e.getMessage()
                );
            }
        }

        Map<Identifier, Integer> result = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.strip();
                if (
                    line.isEmpty() ||
                    line.startsWith("#") ||
                    line.startsWith("[")
                ) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();

                Identifier id = Identifier.tryParse(key);
                if (id == null) {
                    WnirMod.LOGGER.warn(
                        "[CelluloserConfig] Skipping invalid identifier: {}",
                        key
                    );
                    continue;
                }

                int xp;
                try {
                    xp = Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    WnirMod.LOGGER.warn(
                        "[CelluloserConfig] Skipping non-integer value for {}: {}",
                        key,
                        val
                    );
                    continue;
                }

                if (xp <= 0) {
                    WnirMod.LOGGER.warn(
                        "[CelluloserConfig] Skipping non-positive XP for {}: {}",
                        key,
                        xp
                    );
                    continue;
                }

                result.put(id, xp);
            }
        } catch (IOException e) {
            WnirMod.LOGGER.error(
                "Failed to read {}: {}",
                FILE_NAME,
                e.getMessage()
            );
        }

        EXTRA_SOURCES = Collections.unmodifiableMap(result);
        if (!result.isEmpty()) {
            WnirMod.LOGGER.info(
                "[CelluloserConfig] Loaded {} extra source(s): {}",
                result.size(),
                result.keySet()
            );
        }
    }
}
