package com.mcaia.plugin.ai;

import com.google.gson.JsonObject;
import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.logging.FileLogger;
import com.mcaia.plugin.logging.WebhookLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Routes AI responses to the correct handler and manages the
 * request → server_query → execute → answer conversation loop.
 *
 * Threading model:
 *   - AI HTTP calls run on an async thread (via Bukkit scheduler).
 *   - Bukkit API calls (sending messages, dispatching commands, querying server data)
 *     are dispatched back to the main thread.
 *
 * Loop limit: config ai.max-iterations (default 8) prevents infinite loops.
 */
public class ResponseRouter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MCAIAPlugin           plugin;
    private final GeminiClient          gemini;
    private final AIConversationManager conversations;
    private final ServerDataProvider    dataProvider;
    private final FileLogger            fileLogger;
    private final WebhookLogger         webhookLogger;

    public ResponseRouter(MCAIAPlugin plugin,
                          GeminiClient gemini,
                          AIConversationManager conversations,
                          ServerDataProvider dataProvider,
                          FileLogger fileLogger,
                          WebhookLogger webhookLogger) {
        this.plugin        = plugin;
        this.gemini        = gemini;
        this.conversations = conversations;
        this.dataProvider  = dataProvider;
        this.fileLogger    = fileLogger;
        this.webhookLogger = webhookLogger;
    }

    /**
     * Start a new AI interaction from a player's or console's /ai prompt.
     * Must be called from the MAIN thread — it will schedule the async work itself.
     */
    public void handleNewRequest(CommandSender sender, String prompt) {
        String serverName = plugin.getServer().getName();
        String senderName = sender.getName();
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);

        // Build initial request message
        JsonObject context = new JsonObject();
        context.addProperty("online_players", Bukkit.getOnlinePlayers().size());
        context.addProperty("tps_1m",         Math.round(Bukkit.getTPS()[0] * 10.0) / 10.0);
        context.addProperty("server_version", Bukkit.getMinecraftVersion());

        JsonObject request = new JsonObject();
        request.addProperty("type",    "request");
        request.addProperty("player",  senderName);
        request.addProperty("prompt",  prompt);
        request.add("server_context",  context);

        String requestText = request.toString();

        // Add to conversation history
        conversations.addUserTurn(uuid, requestText);

        // Log usage
        webhookLogger.logCommandUsed(serverName, senderName, prompt);
        fileLogger.info("[REQUEST] " + senderName + ": " + prompt);

        // Fire the async loop
        int maxIter = plugin.getConfig().getInt("ai.max-iterations", 8);
        runAsyncLoop(sender, maxIter, 0);
    }

    /**
     * Resume an AI interaction after the player answered a pending question.
     * Called from the chat listener on the main thread.
     */
    public void handlePlayerReply(Player player, String reply) {
        conversations.clearPendingQuery(player.getUniqueId());

        JsonObject replyMsg = new JsonObject();
        replyMsg.addProperty("type",  "player_reply");
        replyMsg.addProperty("reply", reply);

        conversations.addUserTurn(player.getUniqueId(), replyMsg.toString());

        fileLogger.info("[REPLY] " + player.getName() + ": " + reply);

        int maxIter = plugin.getConfig().getInt("ai.max-iterations", 8);
        runAsyncLoop(player, maxIter, 0);
    }

    // ── Core async loop ───────────────────────────────────────────────────────

    /**
     * Run one iteration of the AI conversation loop asynchronously.
     * Each iteration: call Gemini → parse response → handle → maybe loop again.
     */
    private void runAsyncLoop(CommandSender sender, int maxIter, int iteration) {
        if (iteration >= maxIter) {
            sync(() -> sendToSender(sender,
                    "<red>⚠ The AI reached the maximum number of steps without finishing. " +
                    "Try rephrasing your request.</red>"));
            return;
        }

        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // Snapshot history for the API call
            List<AIConversationManager.Turn> history = conversations.getHistory(uuid);

            JsonObject aiResponse = gemini.sendMessage(uuid, history, "");

            // Store AI response in history
            String aiResponseText = aiResponse.toString();
            conversations.addModelTurn(uuid, aiResponseText);

            // Process the response on the main thread
            sync(() -> {
                if (sender instanceof Player p && !p.isOnline()) return; // Player left

                String type = aiResponse.has("type") ? aiResponse.get("type").getAsString() : "error";

                switch (type) {

                    case "answer" -> {
                        String msg = safeString(aiResponse, "message", "Done.");
                        sendToSender(sender, msg);
                        fileLogger.info("[ANSWER → " + sender.getName() + "] " + msg);
                    }

                    case "execute" -> handleExecute(sender, aiResponse, maxIter, iteration);

                    case "server_query" -> handleServerQuery(sender, aiResponse, maxIter, iteration);

                    case "query_player" -> {
                        String question = safeString(aiResponse, "question", "Can you provide more information?");
                        conversations.setPendingQuery(uuid, question);
                        sendToSender(sender, "<yellow>❓ " + question + "</yellow>");
                        fileLogger.info("[QUERY → " + sender.getName() + "] " + question);
                    }

                    case "refuse" -> {
                        String reason = safeString(aiResponse, "reason", "Request declined.");
                        sendToSender(sender, "<red>🚫 " + reason + "</red>");
                        fileLogger.info("[REFUSE → " + sender.getName() + "] " + reason);
                        webhookLogger.logCommandRefused(
                                plugin.getServer().getName(), sender.getName(), reason);
                    }

                    case "error" -> {
                        String errMsg = safeString(aiResponse, "error_message", "Unknown error.");
                        sendToSender(sender, "<red>⚠ AI error: " + errMsg + "</red>");
                        fileLogger.error("[AI_ERROR → " + sender.getName() + "] " + errMsg);
                        webhookLogger.logError("AI error for " + sender.getName(), errMsg);
                    }

                    default -> {
                        sendToSender(sender, "<red>⚠ AI returned an unrecognized response type: " + type + "</red>");
                        fileLogger.warn("[UNKNOWN_TYPE → " + sender.getName() + "] type=" + type);
                    }
                }
            });
        });
    }

    // ── Execute handler ───────────────────────────────────────────────────────

    private void handleExecute(CommandSender sender, JsonObject aiResponse, int maxIter, int iteration) {
        String command     = safeString(aiResponse, "command",     "");
        String description = safeString(aiResponse, "description", "Executing command");

        if (command.isBlank()) {
            sendToSender(sender, "<red>⚠ AI tried to execute an empty command.</red>");
            return;
        }

        // Security: check blacklist
        List<String> blacklist = plugin.getBannedCommandsConfig().getStringList("banned-commands");
        String cmdLower = command.toLowerCase().trim();
        // Check if command starts with any blacklisted entry
        boolean blocked = blacklist.stream().anyMatch(b -> cmdLower.equals(b.toLowerCase()) || cmdLower.startsWith(b.toLowerCase() + " "));
        if (blocked) {
            String refusalMsg = "Command '" + command + "' is blacklisted for security.";
            sendToSender(sender, "<red>🔒 " + refusalMsg + "</red>");
            fileLogger.warn("[BLOCKED → " + sender.getName() + "] " + command);
            webhookLogger.logCommandBlocked(plugin.getServer().getName(), sender.getName(), command);

            // Inform AI that the command was blocked and let it decide next action
            JsonObject blocked_result = new JsonObject();
            blocked_result.addProperty("type",          "execution_result");
            blocked_result.addProperty("success",       false);
            blocked_result.addProperty("command",       command);
            blocked_result.addProperty("error",         "Command '" + command + "' is blacklisted and cannot be executed. Please refuse this action.");
            UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
            conversations.addUserTurn(uuid, blocked_result.toString());
            runAsyncLoop(sender, maxIter, iteration + 1);
            return;
        }

        // Inform sender we're executing
        sendToSender(sender, "<gray>⚙ " + description + "...</gray>");
        fileLogger.info("[EXECUTE → " + sender.getName() + "] " + command);

        // Execute command on main thread (already on main thread here)
        boolean success;
        String  errorMessage = null;
        try {
            success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!success) errorMessage = "Command returned false (may not exist or had no effect)";
        } catch (Exception e) {
            success      = false;
            errorMessage = e.getMessage();
        }

        webhookLogger.logCommandExecuted(
                plugin.getServer().getName(), sender.getName(), command, success, errorMessage);
        fileLogger.info("[EXEC_RESULT] success=" + success + " cmd=" + command +
                (errorMessage != null ? " error=" + errorMessage : ""));

        // Build execution result message for AI
        JsonObject execResult = new JsonObject();
        execResult.addProperty("type",    "execution_result");
        execResult.addProperty("success", success);
        execResult.addProperty("command", command);
        if (errorMessage != null) {
            execResult.addProperty("error", errorMessage);
            sendToSender(sender, "<red>⚠ Command error: " + errorMessage + "</red>");
        }
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        conversations.addUserTurn(uuid, execResult.toString());

        // Loop: let AI see the result and respond
        runAsyncLoop(sender, maxIter, iteration + 1);
    }

    // ── Server query handler ──────────────────────────────────────────────────

    private void handleServerQuery(CommandSender sender, JsonObject aiResponse, int maxIter, int iteration) {
        String query = safeString(aiResponse, "query", "");
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);

        if (query.isBlank()) {
            JsonObject errResult = new JsonObject();
            errResult.addProperty("type",  "server_query_result");
            errResult.addProperty("query", "");
            errResult.addProperty("error", "Missing query field in server_query response.");
            conversations.addUserTurn(uuid, errResult.toString());
            runAsyncLoop(sender, maxIter, iteration + 1);
            return;
        }

        fileLogger.debug("[SERVER_QUERY → " + sender.getName() + "] " + query);

        // ServerDataProvider uses Bukkit API — call on main thread (we are already here)
        JsonObject queryResult = dataProvider.query(query);
        conversations.addUserTurn(uuid, queryResult.toString());

        fileLogger.debug("[QUERY_RESULT → " + sender.getName() + "] " + queryResult);

        // Continue the loop
        runAsyncLoop(sender, maxIter, iteration + 1);
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    /** Send a MiniMessage-formatted string to the sender with the plugin prefix. */
    private void sendToSender(CommandSender sender, String miniMessage) {
        String prefix = plugin.getConfig().getString("prefix",
                "<gradient:#00d2ff:#3a7bd5><b>[AI]</b></gradient> <gray>»</gray> ");
        try {
            sender.sendMessage(MM.deserialize(prefix + miniMessage));
        } catch (Exception e) {
            // Fallback: send plain text if MiniMessage parsing fails
            sender.sendMessage("[AI] " + miniMessage.replaceAll("<[^>]+>", ""));
        }
    }

    /** Dispatch a Runnable on the main server thread. */
    private void sync(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private String safeString(JsonObject obj, String key, String fallback) {
        try { return obj.has(key) ? obj.get(key).getAsString() : fallback; }
        catch (Exception e) { return fallback; }
    }
}
