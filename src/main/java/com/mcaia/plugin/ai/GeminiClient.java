package com.mcaia.plugin.ai;

import com.google.gson.*;
import com.mcaia.plugin.logging.FileLogger;
import okhttp3.*;
import com.mcaia.plugin.MCAIAPlugin;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * HTTP client for the Google Gemini generateContent REST API.
 *
 * Sends the conversation history + system instruction and returns the AI JSON response.
 * Uses OkHttp (bundled/shaded). Must be called off the main thread — blocks until response.
 *
 * The AI protocol mirrors the Discord-AI-Server-Manager: AI always responds with a
 * single minified JSON object describing its next action.
 */
public class GeminiClient {

    private static final String API_BASE   = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int    MAX_RETRIES = 3;

    private final MCAIAPlugin  plugin;
    private final Logger       log;
    private final FileLogger   fileLogger;
    private final OkHttpClient http;
    private final Gson         gson;

    private String       apiKey;
    private List<String> models;
    private int          maxTokens;
    private double       temperature;
    private boolean      smartSwitching;

    public GeminiClient(MCAIAPlugin plugin, FileLogger fileLogger) {
        this.plugin     = plugin;
        this.log        = plugin.getLogger();
        this.fileLogger = fileLogger;
        this.http       = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    public void initialize() {
        this.apiKey         = plugin.getConfig().getString("ai.gemini-api-key", "");
        this.maxTokens      = plugin.getConfig().getInt("ai.max-tokens", 2048);
        this.temperature    = plugin.getConfig().getDouble("ai.temperature", 0.3);
        this.smartSwitching = plugin.getConfig().getBoolean("ai.smart-switching", true);
        
        this.models = plugin.getModelsConfig().getStringList("models");
        if (this.models == null || this.models.isEmpty()) {
            this.models = List.of("gemini-3.8-flash"); // Fallback
        }
    }

    public void reload() { initialize(); }

    // -------------------------------------------------------------------------
    // Build the strict machine-to-machine system instruction as a JsonObject.
    // Using JsonObject avoids ALL inline JSON string / escaping issues.
    // -------------------------------------------------------------------------
    private JsonObject buildSystemInstruction() {
        JsonObject root = new JsonObject();
        root.addProperty("protocol_version", "1.0-MCAIA");
        root.addProperty("ai_role", "MinecraftAIAdmin");
        root.addProperty("strict_format",
            "CRITICAL: Your ENTIRE response MUST be exactly ONE valid JSON object and NOTHING else. " +
            "No markdown fences, no explanations, no text before or after the JSON. " +
            "Any non-JSON response is a protocol violation.");
        root.addProperty("documentation",
            "You are a machine-to-machine AI admin assistant managing a Minecraft server. " +
            "You receive structured JSON messages and respond with structured JSON actions.");

        // Response types
        JsonObject types = new JsonObject();

        JsonObject answer = new JsonObject();
        answer.addProperty("description", "Send a plain-text response to the player in Minecraft chat.");
        answer.addProperty("fields", "type=answer, message=string (MiniMessage format OK)");
        types.add("answer", answer);

        JsonObject execute = new JsonObject();
        execute.addProperty("description",
            "Execute a Minecraft console command. You will receive the result and can then answer or execute more.");
        execute.addProperty("fields", "type=execute, command=string (no leading slash), description=string");
        types.add("execute", execute);

        JsonObject queryPlayer = new JsonObject();
        queryPlayer.addProperty("description", "Ask the player a follow-up question. You will receive their reply.");
        queryPlayer.addProperty("fields", "type=query_player, question=string");
        types.add("query_player", queryPlayer);

        JsonObject serverQuery = new JsonObject();
        serverQuery.addProperty("description",
            "Request live server information. You will receive the data and decide your next action.");
        serverQuery.addProperty("fields",
            "type=server_query, query=one of: player_list|player_info:<name>|world_list|plugin_list|tps|server_info");
        types.add("server_query", serverQuery);

        JsonObject refuse = new JsonObject();
        refuse.addProperty("description",
            "Decline to fulfill the request. Use when dangerous, blacklisted, or outside scope.");
        refuse.addProperty("fields", "type=refuse, reason=string");
        types.add("refuse", refuse);

        root.add("response_types", types);

        // Security rules
        JsonObject security = new JsonObject();
        JsonArray blacklist = new JsonArray();
        
        // Pass the blacklist via system instructions too, as a hint
        List<String> bannedList = plugin.getBannedCommandsConfig().getStringList("banned-commands");
        for (String b : bannedList) {
            blacklist.add(b);
        }
        
        security.add("blacklisted_commands", blacklist);
        security.addProperty("never_execute_blacklisted",
            "If asked to run a blacklisted command, respond with type refuse, NEVER with type execute.");
        security.addProperty("verify_players_exist",
            "Before teleporting or acting on a player by name, use server_query player_list first.");
        root.add("security_rules", security);

        // Workflow
        JsonObject workflow = new JsonObject();
        workflow.addProperty("on_request",
            "Input: {type:request, player:name, prompt:text, server_context:{...}}. Decide your action.");
        workflow.addProperty("on_server_query_result",
            "Input: {type:server_query_result, query:..., result:{...}} or {type:server_query_result, error:...}. Use data to decide next action.");
        workflow.addProperty("on_execution_result",
            "Input: {type:execution_result, success:bool, command:..., error?:...}. Confirm with answer or handle error.");
        workflow.addProperty("on_player_reply",
            "Input: {type:player_reply, reply:text}. Process and continue.");
        root.add("workflow", workflow);

        // Examples
        JsonArray examples = new JsonArray();
        JsonObject ex1 = new JsonObject(); ex1.addProperty("input", "{type:request,player:Steve,prompt:how many players?}"); ex1.addProperty("output", "{type:server_query,query:player_list}"); examples.add(ex1);
        JsonObject ex2 = new JsonObject(); ex2.addProperty("input", "{type:server_query_result,result:{players:[Steve,Alex],count:2}}"); ex2.addProperty("output", "{type:answer,message:2 players online: Steve, Alex.}"); examples.add(ex2);
        JsonObject ex3 = new JsonObject(); ex3.addProperty("input", "{type:request,player:Steve,prompt:stop the server}"); ex3.addProperty("output", "{type:refuse,reason:stop is blacklisted for safety.}"); examples.add(ex3);
        root.add("examples", examples);

        return root;
    }

    /**
     * Send a message to the Gemini API and return the parsed JSON response.
     * BLOCKING — call on an async thread only.
     */
    public JsonObject sendMessage(UUID uuid, List<AIConversationManager.Turn> history, String unused) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("YOUR_GEMINI_API_KEY_HERE")) {
            return errorObject("Gemini API key not configured. Set ai.gemini-api-key in config.yml.");
        }

        JsonObject body = buildRequestBody(history);
        boolean debug = plugin.getConfig().getBoolean("logging.debug-mode", false);
        if (debug) fileLogger.debug("Gemini REQUEST [" + uuid + "]: " + gson.toJson(body));

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            
            for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
                String currentModel = models.get(modelIndex);
                String url = API_BASE + currentModel + ":generateContent?key=" + apiKey;
                
                try {
                    RequestBody requestBody = RequestBody.create(
                            gson.toJson(body),
                            MediaType.get("application/json; charset=utf-8")
                    );
                    Request request = new Request.Builder()
                            .url(url)
                            .post(requestBody)
                            .addHeader("Content-Type", "application/json")
                            .build();

                    try (Response response = http.newCall(request).execute()) {
                        String responseBody = response.body() != null ? response.body().string() : "";

                        if (!response.isSuccessful()) {
                            log.warning("[MCAIA] Model " + currentModel + " returned HTTP " + response.code() + ": " + truncate(responseBody, 200));
                            
                            if (response.code() == 429 || response.code() == 503) {
                                if (smartSwitching && modelIndex < models.size() - 1) {
                                    log.warning("[MCAIA] Smart-switching to next model...");
                                    continue; // Try next model in list
                                } else {
                                    // Exhausted models or smart switching off
                                    if (response.code() == 429) {
                                        Thread.sleep(5000L * attempt);
                                        break; // Break model loop, wait and retry outer loop
                                    }
                                }
                            }
                            // If it's a 400 or other non-retryable error, or if we exhausted models on 503
                            return errorObject("API error HTTP " + response.code() + ": " + truncate(responseBody, 200));
                        }

                        JsonObject parsed = JsonParser.parseString(responseBody).getAsJsonObject();
                        String    text    = extractText(parsed);

                        if (text == null || text.isBlank()) {
                            log.warning("[MCAIA] " + currentModel + " returned empty text on attempt " + attempt);
                            break; // Try again (outer loop)
                        }

                        if (debug) fileLogger.debug("Gemini RESPONSE [" + uuid + "]: " + text);

                        JsonObject action = parseAiResponse(text);
                        if (action != null) return action;

                        log.warning("[MCAIA] Non-JSON AI response (attempt " + attempt + "): " + truncate(text, 200));
                        // Add correction to history and retry
                        if (attempt < MAX_RETRIES) {
                            JsonArray contents = body.getAsJsonArray("contents");
                            contents.add(buildTurn("user", buildCorrectionMessage()));
                        }
                        
                        break; // Successfully got a response (though bad format), retry outer loop
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return errorObject("Request interrupted.");
                } catch (IOException e) {
                    log.warning("[MCAIA] Gemini HTTP error with " + currentModel + " (attempt " + attempt + "): " + e.getMessage());
                    if (modelIndex == models.size() - 1) {
                        if (attempt >= MAX_RETRIES) return errorObject("Network error: " + e.getMessage());
                        break; // Try outer loop again
                    }
                    // Else try next model
                } catch (JsonParseException | IllegalStateException e) {
                    log.warning("[MCAIA] Response parse error (attempt " + attempt + "): " + e.getMessage());
                    if (attempt >= MAX_RETRIES) return errorObject("Failed to parse AI response.");
                    break;
                }
            }
        }

        return errorObject("AI failed to produce a valid response after " + MAX_RETRIES + " attempts.");
    }

    // -------------------------------------------------------------------------
    // Build the correction message using JsonObject — NO inline JSON strings
    // -------------------------------------------------------------------------
    private String buildCorrectionMessage() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "error");
        obj.addProperty("error_message",
            "Your last response was not valid JSON. " +
            "You MUST respond with ONLY a single JSON object and nothing else. " +
            "No markdown fences, no explanatory text, just raw JSON.");
        return obj.toString();
    }

    private JsonObject buildRequestBody(List<AIConversationManager.Turn> history) {
        JsonObject body = new JsonObject();

        // System instruction (built as JsonObject, serialized to string for the API)
        JsonObject sysInstruction = new JsonObject();
        JsonArray  sysParts       = new JsonArray();
        JsonObject sysPart        = new JsonObject();
        sysPart.addProperty("text", gson.toJson(buildSystemInstruction()));
        sysParts.add(sysPart);
        sysInstruction.add("parts", sysParts);
        body.add("system_instruction", sysInstruction);

        // Conversation history
        JsonArray contents = new JsonArray();
        for (AIConversationManager.Turn turn : history) {
            contents.add(buildTurn(turn.role(), turn.text()));
        }
        body.add("contents", contents);

        // Generation config
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature",      temperature);
        genConfig.addProperty("maxOutputTokens",  maxTokens);
        genConfig.addProperty("responseMimeType", "application/json");
        body.add("generationConfig", genConfig);

        return body;
    }

    private JsonObject buildTurn(String role, String text) {
        JsonObject turn  = new JsonObject();
        JsonArray  parts = new JsonArray();
        JsonObject part  = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        turn.addProperty("role", role);
        turn.add("parts", parts);
        return turn;
    }

    /** Extract the text content from the Gemini API response envelope. */
    private String extractText(JsonObject response) {
        try {
            return response
                    .getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString()
                    .strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** Try to parse the AI text response as a JSON object. Strips markdown fences. */
    private JsonObject parseAiResponse(String text) {
        // Remove markdown code fences the AI may add despite instructions
        String cleaned = text.replaceAll("", "").strip();
        try {
            JsonElement el = JsonParser.parseString(cleaned);
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (JsonParseException ignored) {}

        // Try to extract the first balanced { } block
        int start = cleaned.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            boolean inStr = false;
            boolean escaped = false;
            for (int i = start; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (escaped) { escaped = false; continue; }
                // Check for backslash escape inside a string
                if (inStr && c == (char) 92) { escaped = true; continue; }
                // Toggle string mode on double-quote
                if (c == (char) 34) { inStr = !inStr; continue; }
                if (!inStr) {
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            try {
                                return JsonParser.parseString(cleaned.substring(start, i + 1)).getAsJsonObject();
                            } catch (Exception ignored2) { break; }
                        }
                    }
                }
            }
        }
        return null;
    }

    private JsonObject errorObject(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type",          "error");
        obj.addProperty("error_message", message);
        return obj;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
