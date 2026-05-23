package com.wnir;

import com.google.gson.JsonArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-pet LLM session.
 *
 * Thread model:
 * - Server tick thread: calls addTrigger/addCombatEvent/flushCombatEvents; reads volatile fields.
 * - LLM executor thread: runs HTTP calls; reads/writes volatile fields.
 * - All WeddingRingData access from the executor thread goes through server.execute() + CompletableFuture.
 */
public final class WeddingRingLlmSession {

    public enum ParticleState { IDLE, THINKING, DONE_BURST, TOOL_BURST }

    private static final JsonArray TOOLS = WeddingRingLlmClient.buildToolsArray();

    public final UUID petUUID;
    private final ExecutorService executor;

    private final AtomicBoolean inFlight   = new AtomicBoolean(false);
    private final Object lock              = new Object();
    private final List<String> pendingTriggers = new ArrayList<>();
    private final List<String> combatQueue     = new ArrayList<>();

    // Readable from server tick without lock (volatile)
    public volatile ParticleState particleState = ParticleState.IDLE;
    public volatile int burstTicksLeft = 0;

    public WeddingRingLlmSession(UUID petUUID) {
        this.petUUID = petUUID;
        String shortId = shortPetId();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wnir-llm-" + shortId);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Trigger API (server tick thread) ─────────────────────────────────────

    public void addTrigger(MinecraftServer server, String text) {
        synchronized (lock) {
            pendingTriggers.add(text);
            if (!inFlight.get()) startCall(server);
        }
    }

    public void addCombatEvent(String text) {
        synchronized (lock) { combatQueue.add(text); }
    }

    public void flushCombatEvents(MinecraftServer server, Mob pet) {
        synchronized (lock) {
            if (combatQueue.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String e : combatQueue) sb.append(e).append("\n");
            combatQueue.clear();
            sb.append("[Combat] The fight is over. I have ")
                .append(String.format("%.1f", pet.getHealth())).append("/")
                .append(String.format("%.1f", pet.getMaxHealth()))
                .append(" ♥ remaining.");
            pendingTriggers.add(sb.toString().trim());
            if (!inFlight.get()) startCall(server);
        }
    }

    // ── Internal call management ──────────────────────────────────────────────

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
            runCallLoop(server, String.join("\n", triggers));
        } catch (Exception e) {
            WnirMod.LOGGER.error("[LlmSession:{}] unhandled error: {}", shortPetId(), e.getMessage(), e);
        } finally {
            inFlight.set(false);
            particleState = ParticleState.DONE_BURST;
            burstTicksLeft = 3;
            synchronized (lock) {
                if (!pendingTriggers.isEmpty()) startCall(server);
                else particleState = ParticleState.IDLE;
            }
        }
    }

    private void runCallLoop(MinecraftServer server, String triggerText) {
        String shortId = shortPetId();
        WnirMod.LOGGER.info("[LlmSession:{}] trigger ({} chars): {}", shortId, triggerText.length(),
            triggerText.length() > 150 ? triggerText.substring(0, 150) + "…" : triggerText);

        // Snapshot all server-side state on the server thread
        CompletableFuture<WeddingRingLlmContext.Snapshot> snapFuture = new CompletableFuture<>();
        server.execute(() -> {
            Mob pet = findPet(server);
            WeddingRingData data = pet != null ? WeddingRingData.get(pet) : null;
            snapFuture.complete(pet != null && data != null
                ? WeddingRingLlmContext.Snapshot.of(pet, data) : null);
        });

        WeddingRingLlmContext.Snapshot snap;
        try { snap = snapFuture.get(5, TimeUnit.SECONDS); }
        catch (Exception e) {
            WnirMod.LOGGER.warn("[LlmSession:{}] snapshot timeout: {}", shortId, e.getMessage());
            return;
        }
        if (snap == null) {
            WnirMod.LOGGER.warn("[LlmSession:{}] pet or data gone, skipping call", shortId);
            return;
        }

        List<Map<String, Object>> messages = WeddingRingLlmContext.build(snap, triggerText);
        WnirMod.LOGGER.info("[LlmSession:{}] context: {} messages, ~{} tokens", shortId,
            messages.size(), messages.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum());

        int maxRounds = 10;
        for (int round = 0; round < maxRounds; round++) {
            WeddingRingLlmClient.CompletionResult result =
                WeddingRingLlmClient.complete(messages, TOOLS, "auto");
            WnirMod.LOGGER.info("[LlmSession:{}] round {}: finish={}", shortId, round + 1, result.finishReason());

            if (result.isToolCall() && !result.toolCalls().isEmpty()) {
                particleState = ParticleState.TOOL_BURST;
                burstTicksLeft = 2;

                List<Map<String, Object>> toolResults = new ArrayList<>();
                for (var tc : result.toolCalls()) {
                    WnirMod.LOGGER.info("[LlmSession:{}] tool call: {} args={}", shortId, tc.name(), tc.argumentsJson());
                    String toolResult = WeddingRingLlmTools.execute(server, petUUID, tc.name(), tc.argumentsJson());
                    WnirMod.LOGGER.info("[LlmSession:{}] tool result: {}", shortId, toolResult);
                    toolResults.add(WeddingRingLlmContext.toolResult(tc.id(), toolResult));
                }

                messages.add(WeddingRingLlmContext.assistantWithToolCalls(result.toolCalls()));
                messages.addAll(toolResults);
                particleState = ParticleState.THINKING;
            } else {
                String content = result.content();
                WnirMod.LOGGER.info("[LlmSession:{}] response: {}", shortId,
                    content != null && content.length() > 300 ? content.substring(0, 300) + "…" : content);

                if (content != null && !content.isBlank()) {
                    // Append trigger + response to history, trim to budget, save on server thread
                    List<Map<String, Object>> history = new ArrayList<>(snap.history());
                    history.add(WeddingRingLlmContext.user(triggerText));
                    history.add(WeddingRingLlmContext.assistant(content));

                    int historyBudget = WeddingRingLlmConfig.contextWindow - WeddingRingLlmConfig.memoryBudgetTokens;
                    int used = history.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
                    while (used > historyBudget && !history.isEmpty()) {
                        used -= WeddingRingLlmContext.msgTokens(history.get(0));
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

    // ── Sleep compaction ──────────────────────────────────────────────────────

    public void compact(MinecraftServer server) {
        executor.submit(() -> {
            String shortId = shortPetId();
            WnirMod.LOGGER.info("[LlmSession:{}] compact start", shortId);

            // Snapshot on server thread
            CompletableFuture<WeddingRingLlmContext.Snapshot> snapFuture = new CompletableFuture<>();
            server.execute(() -> {
                Mob pet = findPet(server);
                WeddingRingData data = pet != null ? WeddingRingData.get(pet) : null;
                snapFuture.complete(pet != null && data != null
                    ? WeddingRingLlmContext.Snapshot.of(pet, data) : null);
            });

            WeddingRingLlmContext.Snapshot snap;
            try { snap = snapFuture.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { WnirMod.LOGGER.warn("[LlmSession:{}] compact snapshot timeout", shortId); return; }
            if (snap == null) { WnirMod.LOGGER.warn("[LlmSession:{}] compact: pet gone", shortId); return; }

            List<Map<String, Object>> messages = WeddingRingLlmContext.build(snap,
                "[Compact] Summarise everything important from this conversation into a concise " +
                "bullet-point memory list and a numbered todo list. Be brief; discard unimportant details.");

            WeddingRingLlmClient.CompletionResult result =
                WeddingRingLlmClient.complete(messages, null, "none");

            if (result.content() == null || result.content().isBlank()) {
                WnirMod.LOGGER.warn("[LlmSession:{}] compact returned empty", shortId);
                return;
            }

            List<String> newMemory = new ArrayList<>();
            List<String> newTodo   = new ArrayList<>();
            for (String line : result.content().split("\n")) {
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
            });

            WnirMod.LOGGER.info("[LlmSession:{}] compact done: {} memories, {} todos",
                shortId, fm.size(), ft.size());
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

    private String shortPetId() {
        return petUUID.toString().substring(0, 8);
    }
}
