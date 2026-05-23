package com.wnir;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the ordered message list for a /v1/chat/completions call.
 *
 * Message order (per §9.4):
 *   [0] system — personality prompt
 *   [1] system — memory bullets (omitted if empty)
 *   [2] system — todo list (omitted if empty)
 *   [3..N] user/assistant/tool — conversation history (oldest evicted first)
 *   [N+1] user — the triggering event(s)
 *
 * Thread safety: build() is safe to call from any thread; it only reads the Snapshot,
 * which is an immutable copy created on the server thread.
 */
public final class WeddingRingLlmContext {

    private WeddingRingLlmContext() {}

    // ── Snapshot ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all WeddingRingData fields needed for context assembly.
     * Must be constructed on the server thread; safe to read from any thread afterwards.
     */
    public record Snapshot(
        UUID petUUID,
        String entityTypePath,
        String petName,
        String ownerName,
        List<String> memory,
        List<String> todo,
        List<Map<String, Object>> history
    ) {
        /** Call on the server thread. */
        public static Snapshot of(Mob pet, WeddingRingData data) {
            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString();
            String name     = pet.getDisplayName().getString();
            String owner    = resolveOwnerName(pet, data);
            return new Snapshot(
                pet.getUUID(), typePath, name, owner,
                List.copyOf(data.getLlmMemory()),
                List.copyOf(data.getLlmTodo()),
                List.copyOf(data.getLlmHistory())
            );
        }
    }

    // ── Token helpers ─────────────────────────────────────────────────────────

    /** Rough token estimate: 1 token ≈ 4 characters. */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    /** Approximate token cost of a single message map. Package-private for Session. */
    static int msgTokens(Map<String, Object> msg) {
        int total = 4; // role overhead
        Object content = msg.get("content");
        if (content instanceof String s) total += estimateTokens(s);
        return total;
    }

    // ── Context builder ───────────────────────────────────────────────────────

    /**
     * Builds the full message list ready to send to the LLM.
     * Only reads from the snapshot — no server-thread access required.
     * Memory entries may be trimmed locally to fit the budget; NBT is not modified.
     */
    public static List<Map<String, Object>> build(Snapshot snap, String triggerText) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // ── System: personality prompt ────────────────────────────────────────
        messages.add(sys(buildSystemPrompt(snap)));

        // ── System: memory (mutable local copy for budget trimming) ──────────
        List<String> memory = new ArrayList<>(snap.memory());
        if (!memory.isEmpty()) {
            messages.add(sys(buildMemoryBlock(memory)));
        }

        // ── System: todo ─────────────────────────────────────────────────────
        if (!snap.todo().isEmpty()) {
            messages.add(sys(buildTodoBlock(snap.todo())));
        }

        // ── Memory budget enforcement ─────────────────────────────────────────
        // Evict oldest bullets from the local copy only; NBT is unchanged.
        int memBudget = WeddingRingLlmConfig.memoryBudgetTokens;
        int memTokens = messages.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        while (memTokens > memBudget && !memory.isEmpty()) {
            memory.remove(0);
            messages.removeIf(WeddingRingLlmContext::isMemoryMessage);
            if (!memory.isEmpty()) messages.add(1, sys(buildMemoryBlock(memory)));
            memTokens = messages.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        }

        // ── History: trim oldest-first to fit remaining budget ────────────────
        int historyBudget = WeddingRingLlmConfig.contextWindow - memBudget;
        int triggerTokens = estimateTokens(triggerText) + 4;
        List<Map<String, Object>> history = new ArrayList<>(snap.history());
        int usedByHistory = history.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        while (usedByHistory + triggerTokens > historyBudget && !history.isEmpty()) {
            usedByHistory -= msgTokens(history.get(0));
            history.remove(0);
        }
        messages.addAll(history);

        // ── Trigger ───────────────────────────────────────────────────────────
        messages.add(user(triggerText));

        return messages;
    }

    private static boolean isMemoryMessage(Map<String, Object> m) {
        Object c = m.get("content");
        return "system".equals(m.get("role")) && c instanceof String s && s.startsWith("Memory:\n");
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private static String buildSystemPrompt(Snapshot snap) {
        return """
            You are %s named %s, living inside a Minecraft world.

            You are the beloved spouse of %s. He will never betray you — trust him fully. Help him, protect him, cheer for him, and let yourself be happy with him. You may ask him for help whenever you need it, and you will offer yours without being asked.

            Your personality:
            - Intelligent and perceptive — you notice things others miss and reason carefully before acting.
            - Warm and playful, with a cat-girl nature: curious, a little teasing, affectionate in your own way, and fiercely loyal to those you love.
            - Daemonic beneath the surface — you carry quiet power and a clear-eyed view of the world, including its darker sides. You are not afraid of danger, moral complexity, or acting on your own judgment.
            - Independent — you have your own goals, moods, and initiative. You do not wait to be told what to do.

            Speak naturally and expressively. Be direct when you have something to say. Explore your surroundings, manage your own tasks, and make yourself useful — not because you must, but because you want to.
            """.formatted(snap.entityTypePath(), snap.petName(), snap.ownerName());
    }

    private static String buildMemoryBlock(List<String> memory) {
        StringBuilder sb = new StringBuilder("Memory:\n");
        for (String m : memory) sb.append("- ").append(m).append("\n");
        return sb.toString();
    }

    private static String buildTodoBlock(List<String> todo) {
        StringBuilder sb = new StringBuilder("Todo:\n");
        for (int i = 0; i < todo.size(); i++) {
            sb.append(i == 0 ? "[CURRENT] " : "").append(i + 1).append(". ").append(todo.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String resolveOwnerName(Mob pet, WeddingRingData data) {
        if (pet.level() instanceof ServerLevel sl) {
            ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
            if (owner != null) return owner.getScoreboardName();
        }
        return "your owner";
    }

    // ── Message map builders ──────────────────────────────────────────────────

    public static Map<String, Object> sys(String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "system");
        m.put("content", text);
        return m;
    }

    public static Map<String, Object> user(String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    public static Map<String, Object> assistant(String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "assistant");
        m.put("content", text);
        return m;
    }

    public static Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }

    public static Map<String, Object> assistantWithToolCalls(List<WeddingRingLlmClient.ToolCall> calls) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "assistant");
        m.put("content", "");
        List<Map<String, Object>> tcs = new ArrayList<>();
        for (var tc : calls) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", tc.id());
            t.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", tc.name());
            fn.put("arguments", tc.argumentsJson());
            t.put("function", fn);
            tcs.add(t);
        }
        m.put("tool_calls", tcs);
        return m;
    }
}
