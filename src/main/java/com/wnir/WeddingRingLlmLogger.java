package com.wnir;

import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Appends all LLM interactions to logs/llm.log in human-readable format.
 * Tokens are written as they stream in — tail -f llm.log to watch live.
 * Thread-safe via synchronized.
 */
public final class WeddingRingLlmLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter out;

    static {
        try {
            Path path = FMLPaths.GAMEDIR.get().resolve("logs/llm.log");
            Files.createDirectories(path.getParent());
            out = new PrintWriter(new BufferedWriter(new FileWriter(path.toFile(), true)));
        } catch (IOException e) {
            WnirMod.LOGGER.error("[LlmLogger] Cannot open llm.log: {}", e.getMessage());
        }
    }

    private WeddingRingLlmLogger() {}

    /** Write the full context window at the start of a call, then open the ASSISTANT block. */
    public static synchronized void logCallStart(String ctx, List<Map<String, Object>> messages) {
        if (out == null) return;
        out.println();
        out.println("════════════════════════════════════════════════════════════");
        out.println(LocalDateTime.now().format(TS) + "  " + ctx);
        out.println("════════════════════════════════════════════════════════════");
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.get("role")).toUpperCase();
            Object content  = msg.get("content");
            Object toolCalls = msg.get("tool_calls");
            out.println("[" + role + "]");
            if (content instanceof String s && !s.isBlank()) {
                for (String line : s.split("\n")) out.println("  " + line);
            }
            if (toolCalls instanceof List<?> tcs) {
                for (Object tc : tcs) {
                    if (tc instanceof Map<?, ?> m && m.get("function") instanceof Map<?, ?> f) {
                        out.println("  → " + f.get("name") + "(" + f.get("arguments") + ")");
                    }
                }
            }
        }
        out.print("[ASSISTANT] ");
        out.flush();
    }

    /** Append a streamed content token (no newline, flushed immediately). */
    public static synchronized void appendToken(String token) {
        if (out == null) return;
        out.print(token);
        out.flush();
    }

    /** Append a streamed reasoning/thinking token — written inline under [thinking]. */
    public static synchronized void appendThinkingToken(String token) {
        if (out == null) return;
        out.print(token);
        out.flush();
    }

    /** Open the thinking block before reasoning tokens start. */
    public static synchronized void logThinkingStart() {
        if (out == null) return;
        out.println();
        out.print("[thinking] ");
        out.flush();
    }

    /** Close the thinking block, open the response block. */
    public static synchronized void logThinkingEnd() {
        if (out == null) return;
        out.println();
        out.print("[response] ");
        out.flush();
    }

    /** Write a completed tool call on its own line. */
    public static synchronized void logToolCall(String name, String args) {
        if (out == null) return;
        out.println();
        out.println("  → " + name + "(" + args + ")");
        out.flush();
    }

    /** Close the ASSISTANT block with the finish reason. */
    public static synchronized void logFinish(String reason) {
        if (out == null) return;
        out.println();
        out.println("[" + reason.toUpperCase() + "]");
        out.flush();
    }
}
