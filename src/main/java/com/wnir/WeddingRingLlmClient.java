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
        String model = WeddingRingLlmConfig.model;
        if (model.isEmpty()) {
            model = autoDetectModel();
            if (model != null) WeddingRingLlmConfig.model = model;
            else model = "default";
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", WeddingRingLlmConfig.temperature);
        // Reserve half the history budget for response (generous upper bound)
        body.addProperty("max_tokens", WeddingRingLlmConfig.contextWindow / 4);
        body.add("messages", GSON.toJsonTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", toolChoice != null ? toolChoice : "auto");
        } else if ("none".equals(toolChoice)) {
            body.addProperty("tool_choice", "none");
        }

        String requestJson = GSON.toJson(body);
        WnirMod.LOGGER.info("[LlmClient] → POST /v1/chat/completions | model={} msgs={} maxTok={}",
            model, messages.size(), body.get("max_tokens").getAsInt());
        WnirMod.LOGGER.info("[LlmClient] request: {}",
            requestJson.length() > 1000 ? requestJson.substring(0, 1000) + "…" : requestJson);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WeddingRingLlmConfig.url + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(120))
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            WnirMod.LOGGER.info("[LlmClient] ← {} | {}",
                resp.statusCode(),
                resp.body().length() > 3000 ? resp.body().substring(0, 3000) + "…" : resp.body());
            return parseResponse(resp.body());
        } catch (Exception e) {
            WnirMod.LOGGER.error("[LlmClient] HTTP error: {}", e.getMessage());
            return new CompletionResult("stop", "[error: " + e.getMessage() + "]", List.of());
        }
    }

    private static CompletionResult parseResponse(String json) {
        try {
            JsonObject root     = JsonParser.parseString(json).getAsJsonObject();
            JsonObject choice   = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            String finishReason = choice.get("finish_reason").getAsString();
            JsonObject msg      = choice.getAsJsonObject("message");

            // content may be null/empty when tool_calls is present
            String content = "";
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                content = msg.get("content").getAsString();
            }
            // reasoning_content is ignored (never stored in history)

            List<ToolCall> toolCalls = new ArrayList<>();
            if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                for (JsonElement el : msg.getAsJsonArray("tool_calls")) {
                    JsonObject tc = el.getAsJsonObject();
                    String id   = tc.get("id").getAsString();
                    JsonObject fn = tc.getAsJsonObject("function");
                    String name = fn.get("name").getAsString();
                    String args = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
                    toolCalls.add(new ToolCall(id, name, args));
                }
            }
            return new CompletionResult(finishReason, content, toolCalls);
        } catch (Exception e) {
            WnirMod.LOGGER.error("[LlmClient] Parse error: {} | body snippet: {}",
                e.getMessage(), json.length() > 200 ? json.substring(0, 200) : json);
            return new CompletionResult("stop", "[parse error]", List.of());
        }
    }

    // ── Tool schema helpers ──────────────────────────────────────────────────

    /** Build the full OpenAI tools array for the pet companion. */
    public static JsonArray buildToolsArray() {
        JsonArray arr = new JsonArray();
        arr.add(buildTool("say", "Send a chat message as the pet",
            prop("text", "string", "The message text")));
        arr.add(buildTool("remember", "Append a bullet to long-term memory",
            prop("text", "string", "The memory text")));
        arr.add(buildTool("plan", "Append an entry to the todo list",
            prop("text", "string", "The task description")));
        arr.add(buildTool("todo", "Return the full current todo list", new JsonObject()));
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
