package com.wnir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client wrapping the llama.cpp OpenAI-compatible API.
 * Stateless — all state lives in WeddingRingLlmSession / WeddingRingData.
 */
public final class WeddingRingLlmClient {

    public record ToolCall(String id, String name, String argumentsJson) {}

    public record CompletionResult(String finishReason, String content, List<ToolCall> toolCalls) {
        public boolean isToolCall() { return "tool_calls".equals(finishReason); }
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private WeddingRingLlmClient() {}

    /** Auto-detect the first model ID from /v1/models. Returns null on failure. */
    public static String autoDetectModel() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WeddingRingLlmConfig.url + "/v1/models"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray data = body.getAsJsonArray("data");
            if (data != null && !data.isEmpty()) {
                return data.get(0).getAsJsonObject().get("id").getAsString();
            }
        } catch (Exception e) {
            WnirMod.LOGGER.warn("[LlmClient] Model auto-detect failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Send a /v1/chat/completions request.
     *
     * @param messages  List of {role, content} maps (or role/content/tool_calls for assistant, role/tool_call_id/content for tool)
     * @param tools     OpenAI tools array as JsonArray, or null to omit
     * @param toolChoice  "auto", "none", or null (defaults to "auto" when tools present)
     */
    public static CompletionResult complete(List<Map<String, Object>> messages,
                                            JsonArray tools,
                                            String toolChoice) {
        return complete(messages, tools, toolChoice, WeddingRingLlmConfig.maxResponseTokens, "");
    }

    public static CompletionResult complete(List<Map<String, Object>> messages,
                                            JsonArray tools,
                                            String toolChoice,
                                            int maxTokens) {
        return complete(messages, tools, toolChoice, maxTokens, "");
    }

    /**
     * Stream a /v1/chat/completions request via SSE.
     * Tokens are written to llm.log as they arrive; the assembled result is returned when done.
     *
     * @param logCtx  short label written to llm.log header (e.g. "pet:abc R1")
     */
    public static CompletionResult complete(List<Map<String, Object>> messages,
                                            JsonArray tools,
                                            String toolChoice,
                                            int maxTokens,
                                            String logCtx) {
        String model = WeddingRingLlmConfig.model;
        if (model.isEmpty()) {
            model = autoDetectModel();
            if (model != null) WeddingRingLlmConfig.model = model;
            else model = "default";
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", WeddingRingLlmConfig.temperature);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", true);
        body.add("messages", GSON.toJsonTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", toolChoice != null ? toolChoice : "auto");
        } else if ("none".equals(toolChoice)) {
            body.addProperty("tool_choice", "none");
        }

        WnirMod.LOGGER.info("[LlmClient] → POST /v1/chat/completions | model={} msgs={} maxTok={} ctx={}",
            model, messages.size(), maxTokens, logCtx.isEmpty() ? "-" : logCtx);

        WeddingRingLlmLogger.logCallStart(
            logCtx.isEmpty() ? model : logCtx + "  model=" + model, messages);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WeddingRingLlmConfig.url + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(120))
                .build();

            HttpResponse<java.util.stream.Stream<String>> resp =
                HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
            WnirMod.LOGGER.info("[LlmClient] ← {}", resp.statusCode());

            // Accumulate streamed SSE chunks
            java.util.LinkedHashMap<Integer, ToolCallBuilder> tcBuilders = new java.util.LinkedHashMap<>();
            StringBuilder contentBuilder = new StringBuilder();
            String[] finishReason = {"stop"};
            boolean[] thinkingStarted = {false};
            boolean[] thinkingDone    = {false};

            try (var lines = resp.body()) {
                lines.forEach(line -> {
                    if (!line.startsWith("data: ")) return;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) return;
                    try {
                        JsonObject chunk  = JsonParser.parseString(data).getAsJsonObject();
                        JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();

                        JsonElement fr = choice.get("finish_reason");
                        if (fr != null && !fr.isJsonNull()) finishReason[0] = fr.getAsString();

                        JsonObject delta = choice.getAsJsonObject("delta");
                        if (delta == null) return;

                        // Thinking token (Qwen3 reasoning_content)
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                            String token = delta.get("reasoning_content").getAsString();
                            if (!token.isEmpty()) {
                                if (!thinkingStarted[0]) {
                                    WeddingRingLlmLogger.logThinkingStart();
                                    thinkingStarted[0] = true;
                                }
                                WeddingRingLlmLogger.appendThinkingToken(token);
                            }
                        }

                        // Content token
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String token = delta.get("content").getAsString();
                            if (!token.isEmpty()) {
                                if (thinkingStarted[0] && !thinkingDone[0]) {
                                    WeddingRingLlmLogger.logThinkingEnd();
                                    thinkingDone[0] = true;
                                }
                                contentBuilder.append(token);
                                WeddingRingLlmLogger.appendToken(token);
                            }
                        }

                        // Tool call argument chunks
                        if (delta.has("tool_calls")) {
                            for (JsonElement tcEl : delta.getAsJsonArray("tool_calls")) {
                                JsonObject tc  = tcEl.getAsJsonObject();
                                int idx = tc.get("index").getAsInt();
                                ToolCallBuilder b = tcBuilders.computeIfAbsent(idx, i -> new ToolCallBuilder());
                                if (tc.has("id") && !tc.get("id").isJsonNull())
                                    b.id = tc.get("id").getAsString();
                                if (tc.has("function")) {
                                    JsonObject fn = tc.getAsJsonObject("function");
                                    if (fn.has("name") && !fn.get("name").isJsonNull())
                                        b.name = fn.get("name").getAsString();
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull())
                                        b.args.append(fn.get("arguments").getAsString());
                                }
                            }
                        }
                    } catch (Exception e) {
                        WnirMod.LOGGER.warn("[LlmClient] SSE parse: {}", e.getMessage());
                    }
                });
            }

            // Log completed tool calls
            for (ToolCallBuilder b : tcBuilders.values()) {
                WeddingRingLlmLogger.logToolCall(b.name, b.args.toString());
            }
            WeddingRingLlmLogger.logFinish(finishReason[0]);
            WnirMod.LOGGER.info("[LlmClient] finish={} content={} toolCalls={}",
                finishReason[0], contentBuilder.length(), tcBuilders.size());

            List<ToolCall> toolCalls = tcBuilders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ToolCall(e.getValue().id, e.getValue().name, e.getValue().args.toString()))
                .collect(java.util.stream.Collectors.toList());

            return new CompletionResult(finishReason[0], contentBuilder.toString(), toolCalls);

        } catch (Exception e) {
            WeddingRingLlmLogger.logFinish("error: " + e.getMessage());
            WnirMod.LOGGER.error("[LlmClient] HTTP error: {}", e.getMessage());
            return new CompletionResult("stop", "[error: " + e.getMessage() + "]", List.of());
        }
    }

    private static class ToolCallBuilder {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    // ── Tool schema helpers ──────────────────────────────────────────────────

    /** Build the full OpenAI tools array for the pet companion. */
    public static JsonArray buildToolsArray() {
        JsonArray arr = new JsonArray();
        arr.add(buildTool("remember", "Append a bullet to long-term memory",
            prop("text", "string", "The memory text")));
        arr.add(buildTool("plan", "Append an entry to the todo list",
            prop("text", "string", "The task description")));
        arr.add(buildTool("todo", "Return the full current todo list", new JsonObject()));
        arr.add(buildTool("done", "Mark a todo item as completed and remove it by its 1-based index",
            prop("index", "integer", "1-based index of the completed todo item")));
        arr.add(buildTool("item_info", "Get info about a Minecraft item by registry name",
            prop("item_name", "string", "Registry path, e.g. minecraft:diamond")));
        arr.add(buildTool("craft", "Craft one unit of an item using pet storage",
            prop("item_name", "string", "Registry path of the item to craft")));
        arr.add(buildTool("nearest", "Find nearby blocks matching a name substring",
            mergeProps(
                prop("block_name", "string", "Registry path substring, e.g. oak_log"),
                prop("count", "integer", "Max results"))));
        arr.add(buildTool("inspect", "List contents of a container block within 8 blocks",
            xyzProps("Container block position")));
        arr.add(buildTool("inventory", "List pet's equipped items and all storage slots", new JsonObject()));
        arr.add(buildTool("put", "Move items from pet storage into a nearby container",
            mergeProps(xyzProps("Container position"),
                prop("item_name", "string", "Item registry path"),
                prop("count", "integer", "Number to transfer"))));
        arr.add(buildTool("get", "Move items from a nearby container into pet storage",
            mergeProps(xyzProps("Container position"),
                prop("item_name", "string", "Item registry path"),
                prop("count", "integer", "Number to transfer"))));
        arr.add(buildTool("goto", "Navigate the pet to coordinates",
            xyzProps("Destination coordinates")));
        arr.add(buildTool("stats", "Get all character attributes and current status", new JsonObject()));
        arr.add(buildTool("equip",
            "Move an item from pet storage into its appropriate equipment slot (weapon, shield, or armor). " +
            "Returns 'broken: ...' if the item has zero durability.",
            prop("item_name", "string", "Registry path of the item to equip, e.g. minecraft:diamond_sword")));
        arr.add(buildTool("place", "Place a block from pet storage at the given coordinates. Returns 'error: too far' if out of range.",
            mergeProps(
                prop("item_name", "string", "Registry path of the block item to place, e.g. minecraft:dirt"),
                xyzProps("Target position to place the block"))));
        return arr;
    }

    private static JsonObject buildTool(String name, String description, JsonObject properties) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);
        fn.add("parameters", params);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", fn);
        return tool;
    }

    private static JsonObject prop(String name, String type, String desc) {
        JsonObject props = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", desc);
        props.add(name, p);
        return props;
    }

    private static JsonObject xyzProps(String hint) {
        JsonObject p = new JsonObject();
        for (String axis : new String[]{"x", "y", "z"}) {
            JsonObject ap = new JsonObject();
            ap.addProperty("type", "integer");
            ap.addProperty("description", axis.toUpperCase() + " coordinate. " + hint);
            p.add(axis, ap);
        }
        return p;
    }

    private static JsonObject mergeProps(JsonObject... sources) {
        JsonObject merged = new JsonObject();
        for (JsonObject s : sources) {
            for (Map.Entry<String, JsonElement> e : s.entrySet()) {
                merged.add(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}
