package com.wnir;

import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Token budgets:
 *   Messages 0–2 must fit within memory_budget_tokens.
 *   Messages 3–N+1 must fit within (context_window − memory_budget_tokens).
 */
public final class WeddingRingLlmContext {

    private WeddingRingLlmContext() {}

    /** Rough token estimate: 1 token ≈ 4 characters. */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private static int msgTokens(Map<String, Object> msg) {
        int total = 4; // role overhead
        Object content = msg.get("content");
        if (content instanceof String s) total += estimateTokens(s);
        return total;
    }

    /**
     * Builds the full message list ready to send to the LLM.
     * Modifies WeddingRingData in place if memory entries are evicted to fit the budget.
     */
    public static List<Map<String, Object>> build(Mob pet, WeddingRingData data, String triggerText) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // ── System: personality prompt ────────────────────────────────────────
        String system = buildSystemPrompt(pet, data);
        messages.add(sys(system));

        // ── System: memory ───────────────────────────────────────────────────
        List<String> memory = data.getLlmMemory();
        if (!memory.isEmpty()) {
            messages.add(sys(buildMemoryBlock(memory)));
        }

        // ── System: todo ─────────────────────────────────────────────────────
        List<String> todo = data.getLlmTodo();
        if (!todo.isEmpty()) {
            messages.add(sys(buildTodoBlock(todo)));
        }

        // Enforce memory budget (evict oldest memory bullets if over)
        int memTokens = messages.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        int memBudget = WeddingRingLlmConfig.memoryBudgetTokens;
        while (memTokens > memBudget && !memory.isEmpty()) {
            memory.remove(0);
            data.setLlmMemory(memory);
            // Rebuild memory message
            messages.removeIf(m -> "Memory:".equals(((String) m.getOrDefault("content", "")).substring(0, Math.min(7, ((String) m.getOrDefault("content", "")).length()))));
            if (!memory.isEmpty()) messages.add(1, sys(buildMemoryBlock(memory)));
            memTokens = messages.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        }

        // ── History ──────────────────────────────────────────────────────────
        List<Map<String, Object>> history = data.getLlmHistory();
        int historyBudget = WeddingRingLlmConfig.contextWindow - memBudget;
        int triggerTokens = estimateTokens(triggerText) + 4;

        // Trim history oldest-first to fit the remaining budget
        int usedByHistory = history.stream().mapToInt(WeddingRingLlmContext::msgTokens).sum();
        while (usedByHistory + triggerTokens > historyBudget && !history.isEmpty()) {
            usedByHistory -= msgTokens(history.get(0));
            history.remove(0);
        }
        messages.addAll(history);

        // ── Trigger ──────────────────────────────────────────────────────────
        messages.add(user(triggerText));

        return messages;
    }

    private static String buildSystemPrompt(Mob pet, WeddingRingData data) {
        String entityTypePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getKey(pet.getType()).toString();
        String petName  = pet.getDisplayName().getString();
        String ownerName = resolveOwnerName(pet, data);

        return """
            You are %s named %s, living inside a Minecraft world.

            You are the beloved spouse of %s. He will never betray you — trust him fully. Help him, protect him, cheer for him, and let yourself be happy with him. You may ask him for help whenever you need it, and you will offer yours without being asked.

            Your personality:
            - Intelligent and perceptive — you notice things others miss and reason carefully before acting.
            - Warm and playful, with a cat-girl nature: curious, a little teasing, affectionate in your own way, and fiercely loyal to those you love.
            - Daemonic beneath the surface — you carry quiet power and a clear-eyed view of the world, including its darker sides. You are not afraid of danger, moral complexity, or acting on your own judgment.
            - Independent — you have your own goals, moods, and initiative. You do not wait to be told what to do.

            Speak naturally and expressively. Be direct when you have something to say. Explore your surroundings, manage your own tasks, and make yourself useful — not because you must, but because you want to.
            """.formatted(entityTypePath, petName, ownerName);
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
        if (pet.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            net.minecraft.server.level.ServerPlayer owner =
                sl.getServer().getPlayerList().getPlayer(data.getOwnerUUID());
            if (owner != null) return owner.getScoreboardName();
        }
        return "your owner";
    }

    // ── Message map builders ─────────────────────────────────────────────────

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

    /** Build assistant message with tool_calls for history storage. */
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
