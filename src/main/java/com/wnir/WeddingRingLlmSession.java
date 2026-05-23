package com.wnir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-pet LLM session.
 *
 * Manages:
 * - A single-thread executor for HTTP calls
 * - Trigger buffer (queued while in-flight)
 * - Combat event queue
 * - Particle state for the server tick to read
 */
public final class WeddingRingLlmSession {

    public enum ParticleState { IDLE, THINKING, DONE_BURST, TOOL_BURST }

    private static final Gson GSON = new Gson();
    private static final JsonArray TOOLS = WeddingRingLlmClient.buildToolsArray();

    public final UUID petUUID;
    private final ExecutorService executor;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final List<String> pendingTriggers = new ArrayList<>();
    private final List<String> combatQueue     = new ArrayList<>();

    // Readable from server tick without lock (volatile)
    public volatile ParticleState particleState = ParticleState.IDLE;
    public volatile int burstTicksLeft = 0; // server-tick countdown for burst effects

    public WeddingRingLlmSession(UUID petUUID) {
        this.petUUID = petUUID;
        String shortId = petUUID.toString().substring(0, 8);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wnir-llm-" + shortId);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Trigger API (called from server tick / event handlers) ───────────────

    public void addTrigger(MinecraftServer server, String text) {
        synchronized (lock) {
            pendingTriggers.add(text);
            if (!inFlight.get()) startCall(server);
        }
    }

    public void addCombatEvent(String text) {
        synchronized (lock) { combatQueue.add(text); }
    }

    /** Called by handler to deliver buffered combat events as a trigger after combat ends. */
    public void flushCombatEvents(MinecraftServer server, Mob pet) {
        synchronized (lock) {
            if (combatQueue.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String e : combatQueue) sb.append(e).append("\n");
            combatQueue.clear();
            // Append combat-end line
            sb.append("[Combat] The fight is over. I have ")
                .append(String.format("%.1f", pet.getHealth())).append("/")
                .append(String.format("%.1f", pet.getMaxHealth()))
                .append(" ♥ remaining.");
            pendingTriggers.add(sb.toString().trim());
            if (!inFlight.get()) startCall(server);
        }
    }

    // ── Internal call management ─────────────────────────────────────────────

    private void startCall(MinecraftServer server) {
        // Assumes lock is held
        if (pendingTriggers.isEmpty()) return;
        List<String> triggers = new ArrayList<>(pendingTriggers);
        pendingTriggers.clear();
        inFlight.set(true);
        particleState = ParticleState.THINKING;
        executor.submit(() -> runCall(server, triggers));
    }

    private void runCall(MinecraftServer server, List<String> triggers) {
        try {
            String triggerText = String.join("\n", triggers);
            runCallLoop(server, triggerText);
        } catch (Exception e) {
            WnirMod.LOGGER.error("[LlmSession] Unhandled error for pet {}: {}", petUUID, e.getMessage());
        } finally {
            inFlight.set(false);
            particleState = ParticleState.DONE_BURST;
            burstTicksLeft = 3; // handler will emit END_ROD burst for 3 ticks

            synchronized (lock) {
                if (!pendingTriggers.isEmpty()) startCall(server);
                else particleState = ParticleState.IDLE;
            }
        }
    }

    private void runCallLoop(MinecraftServer server, String triggerText) {
        // Load current pet state from server thread
        WeddingRingData[] dataHolder = new WeddingRingData[1];
        Mob[] petHolder = new Mob[1];
        java.util.concurrent.CompletableFuture<Void> init = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            Mob pet = findPet(server);
            if (pet != null) {
                petHolder[0] = pet;
                dataHolder[0] = WeddingRingData.get(pet);
            }
            init.complete(null);
        });
        try { init.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return; }
        if (petHolder[0] == null || dataHolder[0] == null) return;

        Mob pet = petHolder[0];
        WeddingRingData data = dataHolder[0];

        // Build messages
        List<Map<String, Object>> messages = WeddingRingLlmContext.build(pet, data, triggerText);
        // Add trigger as last user message (already done in build())

        // Tool call loop
        int maxRounds = 10;
        for (int round = 0; round < maxRounds; round++) {
            WeddingRingLlmClient.CompletionResult result =
                WeddingRingLlmClient.complete(messages, TOOLS, "auto");

            if (result.isToolCall() && !result.toolCalls().isEmpty()) {
                // Emit tool particles flag
                particleState = ParticleState.TOOL_BURST;
                burstTicksLeft = 2;

                // Execute all tool calls
                List<Map<String, Object>> toolResults = new ArrayList<>();
                for (var tc : result.toolCalls()) {
                    String toolResult = WeddingRingLlmTools.execute(server, petUUID, tc.name(), tc.argumentsJson());
                    toolResults.add(WeddingRingLlmContext.toolResult(tc.id(), toolResult));
                }

                // Append assistant tool-calls message + results to context
                messages.add(WeddingRingLlmContext.assistantWithToolCalls(result.toolCalls()));
                messages.addAll(toolResults);

                // Back to thinking
                particleState = ParticleState.THINKING;
            } else {
                // Final response — append to history and save
                String content = result.content();
                if (content != null && !content.isBlank()) {
                    // Append trigger + response to history
                    List<Map<String, Object>> history = new ArrayList<>(data.getLlmHistory());
                    history.add(WeddingRingLlmContext.user(triggerText));
                    history.add(WeddingRingLlmContext.assistant(content));

                    // Trim history to budget before saving
                    int historyBudget = WeddingRingLlmConfig.contextWindow - WeddingRingLlmConfig.memoryBudgetTokens;
                    int used = 0;
                    for (Map<String, Object> m : history) used += WeddingRingLlmContext.estimateTokens((String) m.getOrDefault("content", "")) + 4;
                    while (used > historyBudget && !history.isEmpty()) {
                        used -= WeddingRingLlmContext.estimateTokens((String) history.get(0).getOrDefault("content", "")) + 4;
                        history.remove(0);
                    }

                    final List<Map<String, Object>> finalHistory = history;
                    server.execute(() -> {
                        Mob p2 = findPet(server);
                        if (p2 == null) return;
                        WeddingRingData d2 = WeddingRingData.get(p2);
                        if (d2 != null) d2.setLlmHistory(finalHistory);
                    });
                }
                break;
            }
        }
    }

    /** Compact: summarise history into memory + todo via a no-tools LLM call. */
    public void compact(MinecraftServer server) {
        executor.submit(() -> {
            Mob pet = findPet(server);
            if (pet == null) return;
            WeddingRingData data = WeddingRingData.get(pet);
            if (data == null) return;

            List<Map<String, Object>> messages = WeddingRingLlmContext.build(pet, data,
                "[Compact] Summarise everything important from this conversation into a concise bullet-point memory list and a numbered todo list. Be brief; discard unimportant details.");

            WeddingRingLlmClient.CompletionResult result =
                WeddingRingLlmClient.complete(messages, null, "none");

            if (result.content() == null || result.content().isBlank()) {
                WnirMod.LOGGER.warn("[LlmSession] Compact returned empty for pet {}", petUUID);
                return;
            }

            String response = result.content();
            List<String> newMemory = new ArrayList<>();
            List<String> newTodo   = new ArrayList<>();
            for (String line : response.split("\n")) {
                String l = line.strip();
                if (l.startsWith("-") || l.startsWith("•")) {
                    newMemory.add(l.replaceFirst("^[-•]\\s*", "").strip());
                } else if (l.matches("^\\d+\\..*")) {
                    newTodo.add(l.replaceFirst("^\\d+\\.\\s*", "").strip());
                }
            }

            final List<String> fm = newMemory;
            final List<String> ft = newTodo;
            server.execute(() -> {
                Mob p2 = findPet(server);
                if (p2 == null) return;
                WeddingRingData d2 = WeddingRingData.get(p2);
                if (d2 == null) return;
                if (!fm.isEmpty()) d2.setLlmMemory(fm);
                if (!ft.isEmpty()) d2.setLlmTodo(ft);
                d2.setLlmHistory(new ArrayList<>());
                WnirMod.LOGGER.info("[LlmSession] Compact done for pet {}: {} memories, {} todos",
                    petUUID, fm.size(), ft.size());
            });
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private Mob findPet(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            var e = level.getEntity(petUUID);
            if (e instanceof Mob mob) return mob;
        }
        return null;
    }
}
