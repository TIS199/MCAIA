package com.mcaia.plugin.ai;

import com.google.gson.JsonObject;
import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.logging.FileLogger;
import com.mcaia.plugin.logging.WebhookLogger;
import com.mcaia.plugin.util.FoliaTasks;
import com.mcaia.plugin.util.SafeMiniMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Coordinates the LLM loop and schedules Bukkit work on the owning Folia scheduler. */
public final class ResponseRouter {

    private static final MiniMessage MM = SafeMiniMessage.INSTANCE;

    private static final UUID CONSOLE_UUID = new UUID(0, 0);

    private final MCAIAPlugin plugin;
    private final LlmClient llmClient;
    private final AIConversationManager conversations;
    private final ServerDataProvider dataProvider;
    private final FileLogger fileLogger;
    private final WebhookLogger webhookLogger;
    private final Set<UUID> activeRequests = ConcurrentHashMap.newKeySet();

    public ResponseRouter(MCAIAPlugin plugin, LlmClient llmClient,
                          AIConversationManager conversations, ServerDataProvider dataProvider,
                          FileLogger fileLogger, WebhookLogger webhookLogger) {
        this.plugin = plugin;
        this.llmClient = llmClient;
        this.conversations = conversations;
        this.dataProvider = dataProvider;
        this.fileLogger = fileLogger;
        this.webhookLogger = webhookLogger;
    }

    /** Must be called in the sender's context (entity region for a player). */
    public void handleNewRequest(CommandSender sender, String prompt) {
        UUID uuid = uuidOf(sender);
        String senderName = senderName(sender);
        if (!activeRequests.add(uuid)) {
            sendToSender(sender, "<yellow>You already have an AI request in progress.</yellow>");
            return;
        }

        JsonObject accessContext;
        try {
            accessContext = getAccessContext(sender);
        } catch (RuntimeException e) {
            activeRequests.remove(uuid);
            sendToSender(sender, "<red>Could not read your current command permissions.</red>");
            return;
        }

        boolean collectPlayerCommands = sender instanceof Player player
                && "player".equals(accessContext.get("ai_access").getAsString())
                && plugin.getConfig().getBoolean("permissions.player-command-mode.enabled", false)
                && plugin.getPermissionManager().hasPermission(player, "mcaia.player");
        if (collectPlayerCommands) {
            Player player = (Player) sender;
            FoliaTasks.global(plugin, () -> {
                List<java.util.Map.Entry<String, Command>> commandSnapshot = PlayerCommandAccess.commandSnapshot();
                FoliaTasks.entity(plugin, player, () -> {
                    if (!player.isOnline()) {
                        finishRequest(uuid);
                        return;
                    }
                    var allowed = new com.google.gson.JsonArray();
                    PlayerCommandAccess.allowedCommands(player, plugin.getPermissionManager(), commandSnapshot)
                            .forEach(allowed::add);
                    accessContext.add("allowed_commands", allowed);
                    beginRequest(sender, uuid, senderName, prompt, accessContext);
                }, () -> finishRequest(uuid));
            });
        } else {
            beginRequest(sender, uuid, senderName, prompt, accessContext);
        }
    }

    private void beginRequest(CommandSender sender, UUID uuid, String senderName,
                              String prompt, JsonObject accessContext) {
        String accessContextJson = accessContext.toString();
        dataProvider.initialContext(context -> {
            JsonObject request = new JsonObject();
            request.addProperty("type", "request");
            request.addProperty("player", senderName);
            request.addProperty("prompt", prompt);
            request.add("server_context", context);
            request.add("access_context", com.google.gson.JsonParser.parseString(accessContextJson));
            conversations.addUserTurn(uuid, request.toString());
            webhookLogger.logCommandUsed(senderName, prompt);
            fileLogger.info("[REQUEST] " + senderName + ": " + prompt);
            runAsyncLoop(sender, plugin.getConfig().getInt("ai.max-iterations", 8), 0, accessContextJson);
        });
    }

    /** Called on the player's entity scheduler after a pending question reply. */
    public void handlePlayerReply(Player player, String reply) {
        UUID uuid = player.getUniqueId();
        if (!activeRequests.contains(uuid) || !conversations.hasPendingQuery(uuid)) return;
        conversations.clearPendingQuery(uuid);

        JsonObject replyMessage = new JsonObject();
        replyMessage.addProperty("type", "player_reply");
        replyMessage.addProperty("reply", reply);
        conversations.addUserTurn(uuid, replyMessage.toString());
        fileLogger.info("[REPLY] " + senderName(player) + ": " + reply);
        runAsyncLoop(player, plugin.getConfig().getInt("ai.max-iterations", 8), 0,
                getAccessContext(player).toString());
    }

    public void handlePlayerQuit(UUID uuid) {
        activeRequests.remove(uuid);
        conversations.clearPendingQuery(uuid);
    }

    private void runAsyncLoop(CommandSender sender, int maxIterations, int iteration, String runtimeContext) {
        UUID uuid = uuidOf(sender);
        if (iteration >= maxIterations) {
            finishRequest(uuid);
            sendToSender(sender, "<red>⚠ The AI reached the maximum number of steps without finishing. Try rephrasing your request.</red>");
            return;
        }

        FoliaTasks.async(plugin, () -> {
            JsonObject response;
            try {
                response = llmClient.sendMessage(uuid, conversations.getHistory(uuid), runtimeContext);
                conversations.addModelTurn(uuid, response.toString());
            } catch (RuntimeException error) {
                response = new JsonObject();
                response.addProperty("type", "error");
                response.addProperty("error_message", "The AI request failed: " + safeError(error));
            }
            JsonObject finalResponse = response;
            FoliaTasks.forSender(plugin, sender,
                    () -> processResponse(sender, finalResponse, maxIterations, iteration, runtimeContext),
                    () -> finishRequest(uuid));
        });
    }

    private void processResponse(CommandSender sender, JsonObject response, int maxIterations,
                                 int iteration, String runtimeContext) {
        if (sender instanceof Player player && !player.isOnline()) {
            finishRequest(uuidOf(sender));
            return;
        }
        String type = safeString(response, "type", "error");
        switch (type) {
            case "answer" -> {
                String message = safeString(response, "message", "Done.");
                sendToSender(sender, message);
                fileLogger.info("[ANSWER → " + senderName(sender) + "] " + message);
                finishRequest(uuidOf(sender));
            }
            case "execute" -> handleExecute(sender, response, maxIterations, iteration, runtimeContext);
            case "server_query" -> handleServerQuery(sender, response, maxIterations, iteration, runtimeContext);
            case "query_player" -> {
                UUID uuid = uuidOf(sender);
                String question = safeString(response, "question", "Can you provide more information?");
                AIConversationManager.PendingQuery pending = conversations.setPendingQuery(uuid, question);
                sendToSender(sender, "<yellow>❓ " + question + "</yellow>");
                long timeout = Math.max(1, plugin.getConfig().getLong("ai.query-timeout-seconds", 60));
                FoliaTasks.asyncDelayed(plugin, () -> {
                    if (conversations.expirePendingQuery(uuid, pending.timestampMs())) {
                        finishRequest(uuid);
                        sendToSender(sender, "<gray>Your AI follow-up expired. Start a new request if you still need help.</gray>");
                    }
                }, timeout, TimeUnit.SECONDS);
            }
            case "refuse" -> {
                String reason = safeString(response, "reason", "Request declined.");
                sendToSender(sender, "<red>🚫 " + reason + "</red>");
                fileLogger.info("[REFUSE → " + senderName(sender) + "] " + reason);
                webhookLogger.logCommandRefused(senderName(sender), reason);
                finishRequest(uuidOf(sender));
            }
            case "error" -> {
                String message = safeString(response, "error_message", "Unknown error.");
                sendToSender(sender, "<red>⚠ AI error: " + message + "</red>");
                fileLogger.error("[AI_ERROR → " + senderName(sender) + "] " + message);
                webhookLogger.logError("AI error for " + senderName(sender), message);
                finishRequest(uuidOf(sender));
            }
            default -> {
                sendToSender(sender, "<red>⚠ AI returned an unrecognized response type.</red>");
                fileLogger.warn("[UNKNOWN_TYPE → " + senderName(sender) + "] type=" + type);
                finishRequest(uuidOf(sender));
            }
        }
    }

    private void handleExecute(CommandSender sender, JsonObject response, int maxIterations,
                               int iteration, String runtimeContext) {
        String senderName = senderName(sender);
        String command = safeString(response, "command", "").trim();
        String description = safeString(response, "description", "Executing command");
        while (command.startsWith("/")) command = command.substring(1).stripLeading();
        if (command.isBlank()) {
            sendToSender(sender, "<red>⚠ AI tried to execute an empty command.</red>");
            reportExecutionResult(sender, "", false, "Empty command.", "", false,
                    maxIterations, iteration, runtimeContext);
            return;
        }

        String requestedCommand = command;
        FoliaTasks.global(plugin, () -> {
            List<String> configuredBans = plugin.getBannedCommandsConfig().getStringList("banned-commands");
            List<String> effectiveBans = new java.util.ArrayList<>(configuredBans);
            Bukkit.getCommandMap().getKnownCommands().forEach((label, registered) -> {
                if (CommandGuard.findBlockedCommand(registered.getName(), configuredBans) != null) {
                    effectiveBans.add(label);
                }
            });
            String blocked = CommandGuard.findBlockedCommand(requestedCommand, effectiveBans);
            if (blocked != null) {
                String reason = "Blocked by server command safety guard: " + blocked;
                sendToSender(sender, "<red>🔒 Command was blocked by the server safety guard.</red>");
                fileLogger.warn("[BLOCKED → " + senderName + "] " + requestedCommand + " (" + blocked + ")");
                webhookLogger.logCommandBlocked(senderName, requestedCommand);
                reportExecutionResult(sender, requestedCommand, false, reason, "", false,
                        maxIterations, iteration, runtimeContext);
                return;
            }

            List<Command> commandChain = CommandGuard.commandRoots(requestedCommand).stream()
                    .map(Bukkit.getCommandMap()::getCommand)
                    .toList();
            if (sender instanceof Player player) {
                executeForPlayer(player, commandChain, requestedCommand, description,
                        maxIterations, iteration, runtimeContext);
            } else {
                sendToSender(sender, "<gray>⚙ " + description + "...</gray>");
                dispatchAsConsole(sender, senderName, requestedCommand, maxIterations, iteration, runtimeContext);
            }
        });
    }

    private void executeForPlayer(Player player, List<Command> commandChain, String command, String description,
                                  int maxIterations, int iteration, String runtimeContext) {
        FoliaTasks.entity(plugin, player, () -> {
            if (!player.isOnline()) {
                finishRequest(player.getUniqueId());
                return;
            }
            if (plugin.getPermissionManager().hasPermission(player, "mcaia.use")) {
                sendToSender(player, "<gray>⚙ " + description + "...</gray>");
                String playerName = senderName(player);
                FoliaTasks.global(plugin, () -> dispatchAsConsole(player, playerName, command,
                        maxIterations, iteration, runtimeContext));
                return;
            }

            boolean playerMode = plugin.getConfig().getBoolean("permissions.player-command-mode.enabled", false);
            boolean hasAiPermission = plugin.getPermissionManager().hasPermission(player, "mcaia.player");
            boolean hasCommandPermission = !commandChain.isEmpty();
            for (Command commandPart : commandChain) {
                if (commandPart == null) {
                    hasCommandPermission = false;
                    break;
                }
                boolean allowed = commandPart.testPermissionSilent(player);
                if (!allowed) {
                    String required = commandPart.getPermission();
                    allowed = required != null && !required.isBlank()
                            && plugin.getPermissionManager().hasPermission(player, required);
                }
                if (!allowed) {
                    hasCommandPermission = false;
                    break;
                }
            }
            if (!playerMode || !hasAiPermission || commandChain.isEmpty() || !hasCommandPermission) {
                String reason = commandChain.isEmpty() || commandChain.contains(null)
                        ? "Unknown commands cannot run in player access mode."
                        : "Player command permission check denied this command.";
                reportExecutionResult(player, command, false, reason, "", false,
                        maxIterations, iteration, runtimeContext);
                return;
            }

            sendToSender(player, "<gray>⚙ " + description + "...</gray>");
            fileLogger.info("[EXECUTE → " + senderName(player) + "] " + command);
            boolean success;
            String error = null;
            try {
                success = player.performCommand(command);
                if (!success) error = "Command returned false (may not exist or had no effect)";
            } catch (RuntimeException e) {
                success = false;
                error = safeError(e);
            }
            webhookLogger.logCommandExecuted(senderName(player), command, success, error);
            reportExecutionResult(player, command, success, error, "", false,
                    maxIterations, iteration, runtimeContext);
        }, () -> finishRequest(player.getUniqueId()));
    }

    /** Must run on the global region because this dispatches through the console source. */
    private void dispatchAsConsole(CommandSender requester, String requesterName, String command, int maxIterations,
                                   int iteration, String runtimeContext) {
        boolean success;
        String error = null;
        CommandOutputCapture capture = null;
        try {
            capture = new CommandOutputCapture(Bukkit.getConsoleSender());
            success = Bukkit.dispatchCommand(capture.createSender(), command);
            if (!success) error = "Command returned false (may not exist or had no effect)";
        } catch (RuntimeException e) {
            success = false;
            error = safeError(e);
        }
        String output = capture == null ? "" : capture.getOutput();
        webhookLogger.logCommandExecuted(requesterName, command, success, error);
        fileLogger.info("[EXEC_RESULT] success=" + success + " cmd=" + command
                + (error == null ? "" : " error=" + error));

        if (success && output.isBlank()) {
            long waitTicks = Math.max(0, plugin.getConfig().getLong("ai.command-output-wait-ticks", 30));
            if (waitTicks > 0) {
                CommandOutputCapture pending = capture;
                String finalError = error;
                FoliaTasks.globalDelayed(plugin, () -> reportExecutionResult(requester, command, true,
                        finalError, pending == null ? "" : pending.getOutput(),
                        pending != null && pending.isTruncated(), maxIterations, iteration, runtimeContext), waitTicks);
                return;
            }
        }
        reportExecutionResult(requester, command, success, error, output,
                capture != null && capture.isTruncated(), maxIterations, iteration, runtimeContext);
    }

    private void reportExecutionResult(CommandSender sender, String command, boolean success, String error,
                                       String output, boolean truncated, int maxIterations, int iteration,
                                       String runtimeContext) {
        FoliaTasks.forSender(plugin, sender, () -> {
            JsonObject result = new JsonObject();
            result.addProperty("type", "execution_result");
            result.addProperty("success", success);
            result.addProperty("command", command);
            result.addProperty("output", output);
            result.addProperty("output_truncated", truncated);
            if (error != null) {
                result.addProperty("error", error);
                sendToSender(sender, "<red>⚠ Command error: " + error + "</red>");
            }
            conversations.addUserTurn(uuidOf(sender), result.toString());
            runAsyncLoop(sender, maxIterations, iteration + 1, runtimeContext);
        }, () -> finishRequest(uuidOf(sender)));
    }

    private void handleServerQuery(CommandSender sender, JsonObject response, int maxIterations,
                                   int iteration, String runtimeContext) {
        String query = safeString(response, "query", "");
        String senderName = senderName(sender);
        if (query.isBlank()) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "server_query_result");
            error.addProperty("query", "");
            error.addProperty("error", "Missing query field in server_query response.");
            conversations.addUserTurn(uuidOf(sender), error.toString());
            runAsyncLoop(sender, maxIterations, iteration + 1, runtimeContext);
            return;
        }
        fileLogger.debug("[SERVER_QUERY → " + senderName + "] " + query);
        dataProvider.query(query, result -> {
            conversations.addUserTurn(uuidOf(sender), result.toString());
            fileLogger.debug("[QUERY_RESULT → " + senderName + "] " + result);
            runAsyncLoop(sender, maxIterations, iteration + 1, runtimeContext);
        });
    }

    private JsonObject getAccessContext(CommandSender sender) {
        JsonObject access = new JsonObject();
        Player player = sender instanceof Player p ? p : null;
        boolean admin = player == null || plugin.getPermissionManager().hasPermission(player, "mcaia.use");
        access.addProperty("ai_access", admin ? "console" : "player");
        access.addProperty("is_op", player == null || player.isOp());
        var allowed = new com.google.gson.JsonArray();
        var effectivePermissions = new com.google.gson.JsonArray();
        if (player != null) {
            player.getEffectivePermissions().stream()
                    .filter(info -> info.getValue())
                    .map(info -> info.getPermission().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .limit(512)
                    .forEach(effectivePermissions::add);
            access.addProperty("permission_backend", plugin.getPermissionManager().getActiveBackend().name());
        }
        access.add("effective_permissions", effectivePermissions);
        access.add("allowed_commands", allowed);
        return access;
    }

    private void sendToSender(CommandSender sender, String message) {
        FoliaTasks.forSender(plugin, sender, () -> {
            String prefix = plugin.getConfig().getString("prefix",
                    "<gradient:#00d2ff:#3a7bd5><b>[AI]</b></gradient> <gray>»</gray> ");
            try {
                sender.sendMessage(MM.deserialize(prefix + message));
            } catch (RuntimeException ignored) {
                sender.sendMessage(Component.text("[AI] " + message));
            }
        }, () -> {});
    }

    private UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
    }

    /** Call only while handling a player on that player's owning region. */
    private String senderName(CommandSender sender) {
        return sender instanceof Player player
                ? plugin.getPluginCompat().getCleanName(player)
                : sender.getName();
    }

    private void finishRequest(UUID uuid) {
        activeRequests.remove(uuid);
    }

    private String safeString(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException e) { return fallback; }
    }

    private String safeError(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
