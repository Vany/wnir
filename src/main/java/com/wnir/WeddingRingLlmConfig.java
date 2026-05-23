package com.wnir;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WeddingRingLlmConfig {

    public static String  url                = "http://localhost:8090";
    // volatile: auto-detect writes from LLM executor thread; reads from server thread
    public static volatile String model      = "";
    public static float   temperature        = 0.5f;
    public static int     contextWindow      = 262144;
    public static int     memoryBudgetTokens = 131072;

    private static final String FILE_NAME = "wnir_llm.toml";
    private static final String DEFAULT_CONTENT = """
        # Wedding Ring LLM Companion
        # url — llama.cpp base URL (no trailing slash)
        # model — leave blank to auto-detect from /v1/models
        # temperature — 0.0–1.0
        # context_window — max tokens (must match server --ctx-size)
        # memory_budget_tokens — token budget for system + memory + todo (first half)

        url = http://localhost:8090
        model =
        temperature = 0.5
        context_window = 262144
        memory_budget_tokens = 131072
        """;

    private WeddingRingLlmConfig() {}

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.exists(path)) {
            try { Files.writeString(path, DEFAULT_CONTENT); }
            catch (IOException e) {
                WnirMod.LOGGER.error("[LlmConfig] Cannot create {}: {}", FILE_NAME, e.getMessage());
            }
        }
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).strip();
                String v = line.substring(eq + 1).strip();
                switch (k) {
                    case "url"                  -> { if (!v.isEmpty()) url = v; }
                    case "model"                -> model = v;
                    case "temperature"          -> { try { temperature = Float.parseFloat(v); } catch (Exception ignored) {} }
                    case "context_window"       -> { try { contextWindow = Integer.parseInt(v); } catch (Exception ignored) {} }
                    case "memory_budget_tokens" -> { try { memoryBudgetTokens = Integer.parseInt(v); } catch (Exception ignored) {} }
                }
            }
        } catch (IOException e) {
            WnirMod.LOGGER.error("[LlmConfig] Cannot read {}: {}", FILE_NAME, e.getMessage());
        }
        WnirMod.LOGGER.info("[LlmConfig] url={} model={} temp={} ctx={} memBudget={}",
            url, model.isEmpty() ? "(auto)" : model, temperature, contextWindow, memoryBudgetTokens);
    }
}
