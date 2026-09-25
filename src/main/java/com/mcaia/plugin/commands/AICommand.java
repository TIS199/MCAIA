package com.mcaia.plugin.commands;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import com.mcaia.plugin.ai.ResponseRouter;
import com.mcaia.plugin.compat.PluginCompat;
import com.mcaia.plugin.permissions.PermissionManager;
import com.mcaia.plugin.util.RateLimiter;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * /ai <prompt> — Main AI command for players.
 *
 * Subcommands:
 *   /ai <prompt>     — Send a request to the AI
 *   /ai help         — Show usage help
 *   /ai history      — Show conversation history summary
 *   /ai reset        — Clear personal AI conversation history
 *
 * Permission: mcaia.use
 * Rate-limited: yes (configurable cooldown)
 * Geyser/Floodgate: Bedrock players fully supported
 */
public class AICommand implements BasicCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MCAIAPlugin           plugin;
    private final ResponseRouter        router;
    private final AIConversationManager conversations;
    private final PermissionManager     permissions;
    private final RateLimiter           rateLimiter;
    private final PluginCompat          compat;

    public AICommand(MCAIAPlugin plugin,
                     ResponseRouter router,
                     AIConversationManager conversations,
                     PermissionManager permissions,
                     RateLimiter rateLimiter,
                     PluginCompat compat) {
        this.plugin        = plugin;
        this.router        = router;
        this.conversations = conversations;
        this.permissions   = permissions;
        this.rateLimiter   = rateLimiter;
        this.compat        = compat;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();

        // ── TOS gate ──────────────────────────────────────────────────────────
        if (!plugin.getConfig().getBoolean("tos-accepted", false)) {
            send(sender, "<red>⚠ The server owner has not accepted the MCAIA Terms of Service.</red>");
            send(sender, "<gray>Server owner: Set <white>tos-accepted: true</white> in plugins/MCAIA/config.yml and restart.</gray>");
            return;
        }

        // ── Bedrock player check ──────────────────────────────────────────────
        if (sender instanceof Player player && compat.isBedrockPlayer(player)) {
            boolean bedrockAllowed = plugin.getConfig().getBoolean("geyser.allow-bedrock-players", true);
            if (!bedrockAllowed) {
                send(player, "<red>This command is not available for Bedrock players on this server.</red>");
                return;
            }
        }

        // ── Permission check ──────────────────────────────────────────────────
        if (sender instanceof Player player && !permissions.hasPermission(player, "mcaia.use")) {
            send(player, "<red>You do not have permission to use this command.</red>");
            return;
        }

        // ── Subcommands with no args ──────────────────────────────────────────
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        java.util.UUID uuid = sender instanceof Player p ? p.getUniqueId() : new java.util.UUID(0, 0);

        if (sub.equals("help")) {
            sendHelp(sender);
            return;
        }

        if (sub.equals("reset")) {
            conversations.clearHistory(uuid);
            conversations.clearPendingQuery(uuid);
            send(sender, "<green>✔ Your AI conversation history has been cleared.</green>");
            return;
        }

        if (sub.equals("history")) {
            if (sender instanceof Player player && !permissions.hasPermission(player, "mcaia.history")) {
                send(player, "<red>You do not have permission to view AI history.</red>");
                return;
            }
            sendHistory(sender, uuid);
            return;
        }

        // ── Rate limiting ─────────────────────────────────────────────────────
        if (sender instanceof Player player) {
            if (plugin.getConfig().getBoolean("rate-limit.enabled", true)
                    && !permissions.hasPermission(player, "mcaia.bypass-rate-limit")) {
                long remaining = rateLimiter.getRemainingCooldown(player);
                if (remaining > 0) {
                    send(player, "<yellow>⏳ Please wait <white>" + remaining + "s</white> before using /ai again.</yellow>");
                    plugin.getWebhookLogger().logRateLimited(player.getName());
                    return;
                }
            }
            // Record rate limit timestamp
            rateLimiter.recordUse(player);
        }

        // ── Build prompt from all args ────────────────────────────────────────
        String prompt = String.join(" ", args).trim();

        if (prompt.isBlank()) {
            sendHelp(sender);
            return;
        }

        // Show "thinking" indicator
        String thinkMsg = plugin.getConfig().getString("thinking-message",
                "<gray><i>🤖 AI is thinking...</i></gray>");
        send(sender, thinkMsg);

        // Dispatch to router (handles async + conversation loop)
        router.handleNewRequest(sender, prompt);
    }

    private void sendHelp(CommandSender sender) {
        String cmd = plugin.getConfig().getString("command-name", "ai");
        send(sender, "<gold><b>=== AI Admin Help ===</b></gold>");
        send(sender, "<yellow>/" + cmd + " <prompt></yellow> <gray>— Ask the AI anything or give it a task</gray>");
        send(sender, "<yellow>/" + cmd + " history</yellow>  <gray>— View your recent AI conversation</gray>");
        send(sender, "<yellow>/" + cmd + " reset</yellow>    <gray>— Clear your AI conversation history</gray>");
        send(sender, "<yellow>/" + cmd + " help</yellow>     <gray>— Show this help message</gray>");
        send(sender, "<gray>Examples:</gray>");
        send(sender, "  <aqua>/" + cmd + " how many players are online?</aqua>");
        send(sender, "  <aqua>/" + cmd + " teleport Steve to Alex</aqua>");
        send(sender, "  <aqua>/" + cmd + " give me a diamond sword</aqua>");
    }

    private void sendHistory(CommandSender sender, java.util.UUID uuid) {
        var history = conversations.getHistory(uuid);
        if (history.isEmpty()) {
            send(sender, "<gray>Your AI conversation history is empty.</gray>");
            return;
        }
        send(sender, "<gold><b>=== AI Conversation History ===</b></gold>");
        int shown = Math.min(history.size(), 6); // Show last 6 turns
        for (int i = history.size() - shown; i < history.size(); i++) {
            var turn = history.get(i);
            String roleColor = turn.role().equals("user") ? "<aqua>You</aqua>" : "<green>AI</green>";
            String text = turn.text();
            // Truncate long entries
            if (text.length() > 120) text = text.substring(0, 120) + "…";
            // Strip JSON brackets for display
            if (text.startsWith("{")) text = "<italic>" + text + "</italic>";
            send(sender, roleColor + "<gray>: " + text + "</gray>");
        }
    }

    private void send(CommandSender sender, String msg) {
        String prefix = plugin.getConfig().getString("prefix",
                "<gradient:#00d2ff:#3a7bd5><b>[AI]</b></gradient> <gray>»</gray> ");
        try {
            sender.sendMessage(MM.deserialize(prefix + msg));
        } catch (Exception e) {
            sender.sendMessage("[AI] " + msg.replaceAll("<[^>]+>", ""));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (sender instanceof Player player && !permissions.hasPermission(player, "mcaia.use")) return List.of();
        if (args.length <= 1) {
            String cur = args.length == 0 ? "" : args[0].toLowerCase();
            return List.of("help", "history", "reset").stream()
                    .filter(s -> s.startsWith(cur)).toList();
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        if (sender instanceof Player player) {
            return permissions.hasPermission(player, "mcaia.use");
        }
        return true; // Console is always allowed
    }

    @Override
    public String permission() {
        return "mcaia.use";
    }
}
