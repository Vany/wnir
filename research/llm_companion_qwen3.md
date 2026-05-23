# LLM Companion — Qwen3 + llama.cpp Research Notes

## Model

`unsloth/Qwen3-30B-A3B-GGUF:Q4_K_M` via llama.cpp OpenAI-compatible server at `localhost:8090`.

llama.cpp launch flags that matter:
```
--ctx-size 65536 --preserve-thinking true
```

`--preserve-thinking` causes the server to emit `reasoning_content` SSE chunks separately from `content`. Without it, thinking would be interleaved in `content` as `<think>...</think>` XML, which pollutes the response.

## SSE Streaming

Each SSE line: `data: {...}` or `data: [DONE]`.

The `delta` object can carry:
- `reasoning_content` — thinking token (string)
- `content` — response token (string)
- `tool_calls` — array of partial tool-call chunks (accumulated by index)

`finish_reason` arrives on the final chunk choice: `"stop"` for prose, `"tool_calls"` for function calls.

Thinking + tool calls can appear in the **same response** — the model emits `reasoning_content` while thinking, then produces `tool_calls`. Both are supported simultaneously.

## Tool Calling

Works with `"tool_choice": "auto"` and a standard OpenAI `tools` array. Do **not** use `"required"` — it is silently ignored by llama.cpp.

When `finish_reason == "tool_calls"`:
- `content` is empty or absent
- `tool_calls` is a list of `{id, type, function: {name, arguments}}`
- Send each result back as `{role: "tool", tool_call_id: id, content: result_string}`
- Continue the loop until `finish_reason == "stop"`

## What Breaks Tool Calling

The model gets confused when tools are named after response-format instructions. In early iterations the schema included `say` and `think` tools. The model interpreted these as formatting conventions and wrote:

```
**[say]** "Hello!"
**[goto]** 123, 67, 89
```

…as plain text instead of making actual API function calls. Removing `say` and `think` from the schema fixed this immediately. The model then made clean JSON function calls for all remaining tools.

## History Poisoning

If garbage responses like `**[say]** "Hello!" **[goto]** 123,67,89 **[say]** "Hello!" ...` enter history, the model copies them on the next call (it pattern-matches on what it "said before"). The loop amplifies each iteration.

**Detection:** scan all history messages for `**[` in the content string.
**Fix:** clear history immediately and skip/restart. Log a warning.

**Prevention:** truncate any content at the first `**[` before saving to history. One real response can slip through — detection catches it on the next call.

## Token Budget

Qwen3 thinking is 300–600 tokens at `temperature=0.5`. With `max_tokens=1024` this leaves 400–700 tokens for the actual response — enough for 1–3 sentences of speech plus some narration.

If `max_tokens` is too high (e.g. 2048) the model can fill the budget with garbage repetition before hitting the stop condition. Keep it tight.

## World Summary and Trigger Cadence

Sending `[Tick] A moment passes.` every 200 ticks (10 s) floods the model. At 74 tokens/s, even a short response takes 3–5 s, so with queue buildup this causes permanent in-flight state with no downtime.

1200 ticks (60 s) gives the model time to finish a call, rest, and react to actual events without being constantly poked.

## Context Window

The `context_window` config must match llama.cpp `--ctx-size` exactly. If config says 262144 but server runs at 65536, the token-budget math will try to fit 262144 tokens of history into a 65536 token KV cache — the server truncates silently and you get incoherent responses.

## Speech / Narration Routing

Using `say` as a tool caused output format confusion. Instead the system prompt instructs the model to use `"double quotes"` for spoken speech and `*asterisks*` for private narration/actions. The session extracts quoted text and broadcasts it to all players in yellow chat; everything else goes as gray (`§7`) text to the owner's HUD only via `WeddingRingCaptionPayload`.

Regex: `[\\u201C"]([^\\u201C\\u201D"]+)[\\u201D"]` — handles both ASCII `"` and Unicode smart quotes `""`.

## Compact Prompt

The original compact prompt asked for "everything important" which caused the model to include transient world state (health=15.3/20, weather=Clear, etc.) that is useless to persist. Better prompt:

> Extract only what belongs in long-term memory: things you learned about yourself (strong opinions, unexpected feelings, capabilities, things you want or refuse). Numbered tasks for things you still want to do. Skip transient world state. One bullet = one concrete fact.

Also run poison detection before compacting — if history is garbage, clear it and skip rather than summarising garbage into memory.
