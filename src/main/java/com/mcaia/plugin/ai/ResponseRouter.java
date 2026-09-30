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
import java.util.Objects;
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
    private final LlmClient             llmClient;
    private final AIConversationManager conversations;
    private final ServerDataProvider    dataProvider;
    private final FileLogger            fileLogger;
    private final WebhookLogger         webhookLogger;

    public ResponseRouter(MCAIAPlugin plugin,
                          LlmClient llmClient,
                          AIConversationManager conversations,
                          ServerDataProvider dataProvider,
                          FileLogger fileLogger,
                          WebhookLogger webhookLogger) {
        this.plugin        = plugin;
        this.llmClient     = llmClient;
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
        request.add("access_context", getAccessContext(sender));

        String requestText = request.toString();

        // Add to conversation history
        conversations.addUserTurn(uuid, requestText);

        // Log usage
        webhookLogger.logCommandUsed(senderName, prompt);
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
     * Each iteration: call the configured LLM provider → handle → maybe loop again.
     */
    private void runAsyncLoop(CommandSender sender, int maxIter, int iteration) {
        if (iteration >= maxIter) {
            sync(() -> sendToSender(sender,
                    "<red>⚠ The AI reached the maximum number of steps without finishing. " +
                    "Try rephrasing your request.</red>"));
            return;
        }

        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        String runtimeContext = getAccessContext(sender).toString();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // Snapshot history for the API call
            List<AIConversationManager.Turn> history = conversations.getHistory(uuid);

            JsonObject aiResponse = llmClient.sendMessage(uuid, history, runtimeContext);

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
                                sender.getName(), reason);
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
        String command     = safeString(aiResponse, "command",     "").trim();
        String description = safeString(aiResponse, "description", "Executing command");

        while (command.startsWith("/")) command = command.substring(1).stripLeading();
        if (command.isBlank()) {
            sendToSender(sender, "<red>⚠ AI tried to execute an empty command.</red>");
            return;
        }

        List<String> blacklist = plugin.getBannedCommandsConfig().getStringList("banned-commands");
        List<String> effectiveBlacklist = new java.util.ArrayList<>(blacklist);
        Bukkit.getCommandMap().getKnownCommands().forEach((label, registeredCommand) -> {
            if (CommandGuard.findBlockedCommand(registeredCommand.getName(), blacklist) != null) {
                effectiveBlacklist.add(label);
            }
        });
        String blockedCommand = CommandGuard.findBlockedCommand(command, effectiveBlacklist);
        if (blockedCommand != null) {
            String refusalMsg = "Command '" + command + "' was blocked by the server safety guard: " + blockedCommand;
            sendToSender(sender, "<red>🔒 " + refusalMsg + "</red>");
            fileLogger.warn("[BLOCKED → " + sender.getName() + "] " + command);
            webhookLogger.logCommandBlocked(sender.getName(), command);
            reportExecutionResult(sender, command, false, refusalMsg, "", false, maxIter, iteration);
            return;
        }

        Player player = sender instanceof Player p ? p : null;
        boolean adminAccess = player == null
                || plugin.getPermissionManager().hasPermission(player, "mcaia.use");
        if (!adminAccess) {
            boolean playerModeEnabled = plugin.getConfig().getBoolean("permissions.player-command-mode.enabled", false);
            String rootLabel = command.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
            org.bukkit.command.Command registered = Bukkit.getCommandMap().getCommand(rootLabel);
            if (!playerModeEnabled || !plugin.getPermissionManager().hasPermission(player, "mcaia.player")) {
                reportExecutionResult(sender, command, false, "Player command access is not enabled.", "", false, maxIter, iteration);
                return;
            }
            String requiredPermission = registered == null ? null : registered.getPermission();
            boolean hasCommandPermission = registered != null && registered.testPermissionSilent(player);
            if (!hasCommandPermission && registered != null
                    && requiredPermission != null && !requiredPermission.isBlank()) {
                hasCommandPermission = plugin.getPermissionManager().hasPermission(player, requiredPermission);
            }
            if (registered == null || !hasCommandPermission) {
                String reason = registered == null
                        ? "Unknown commands cannot be run in player access mode."
                        : "You do not have permission to run this command.";
                reportExecutionResult(sender, command, false, reason, "", false, maxIter, iteration);
                return;
            }
        }

        // Inform sender we're executing
        sendToSender(sender, "<gray>⚙ " + description + "...</gray>");
        fileLogger.info("[EXECUTE → " + sender.getName() + "] " + command);

        boolean success;
        String errorMessage = null;
        CommandOutputCapture outputCapture = null;
        String commandOutput = "";
        try {
            if (adminAccess) {
                outputCapture = new CommandOutputCapture(Bukkit.getConsoleSender());
                success = Bukkit.dispatchCommand(outputCapture.createSender(), command);
                commandOutput = outputCapture.getOutput();
            } else {
                success = Objects.requireNonNull(player, "Player access requires a player sender")
                        .performCommand(command);
            }
            if (!success) errorMessage = "Command returned false (may not exist or had no effect)";
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        webhookLogger.logCommandExecuted(
                sender.getName(), command, success, errorMessage);
        fileLogger.info("[EXEC_RESULT] success=" + success + " cmd=" + command +
                (errorMessage != null ? " error=" + errorMessage : ""));

        if (adminAccess && outputCapture != null && commandOutput.isBlank()) {
            int waitTicks = Math.max(0, plugin.getConfig().getInt("ai.command-output-wait-ticks", 30));
            if (waitTicks > 0) {
                CommandOutputCapture pendingCapture = outputCapture;
                boolean commandSucceeded = success;
                String commandError = errorMessage;
                sendExecutionResultLater(sender, command, commandSucceeded, commandError,
                        pendingCapture, maxIter, iteration, waitTicks);
                return;
            }
        }
        reportExecutionResult(sender, command, success, errorMessage, commandOutput,
                outputCapture != null && outputCapture.isTruncated(), maxIter, iteration);
    }

    private void sendExecutionResultLater(CommandSender sender, String command, boolean success, String error,
                                          CommandOutputCapture capture, int maxIter, int iteration, long waitTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            reportExecutionResult(sender, command, success, error, capture.getOutput(),
                    capture.isTruncated(), maxIter, iteration);
        }, waitTicks);
    }

    private void reportExecutionResult(CommandSender sender, String command, boolean success, String error,
                                       String output, boolean outputTruncated, int maxIter, int iteration) {
        JsonObject execResult = new JsonObject();
        execResult.addProperty("type", "execution_result");
        execResult.addProperty("success", success);
        execResult.addProperty("command", command);
        execResult.addProperty("output", output);
        execResult.addProperty("output_truncated", outputTruncated);
        if (error != null) {
            execResult.addProperty("error", error);
            sendToSender(sender, "<red>⚠ Command error: " + error + "</red>");
        }
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        conversations.addUserTurn(uuid, execResult.toString());

        runAsyncLoop(sender, maxIter, iteration + 1);
    }

    private JsonObject getAccessContext(CommandSender sender) {
        JsonObject access = new JsonObject();
        Player player = sender instanceof Player p ? p : null;
        boolean adminAccess = player == null
                || plugin.getPermissionManager().hasPermission(player, "mcaia.use");
        access.addProperty("ai_access", adminAccess ? "console" : "player");
        if (player != null) {
            access.addProperty("is_op", player.isOp());
            if (adminAccess) {
                access.add("allowed_commands", new com.google.gson.JsonArray());
            } else {
                com.google.gson.JsonArray allowed = new com.google.gson.JsonArray();
                if (plugin.getConfig().getBoolean("permissions.player-command-mode.enabled", false)
                        && plugin.getPermissionManager().hasPermission(player, "mcaia.player")) {
                    PlayerCommandAccess.allowedCommands(player, plugin.getPermissionManager()).forEach(allowed::add);
                }
                access.add("allowed_commands", allowed);
            }
        } else {
            access.addProperty("is_op", true);
            access.add("allowed_commands", new com.google.gson.JsonArray());
        }
        return access;
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
