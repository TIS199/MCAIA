package com.mcaia.plugin.commands;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /aiadmin — Administrative command for MCAIA.
 *
 * Subcommands:
 *   reload                    — Reload all configuration
 *   status                    — Show plugin/AI/permission backend status
 *   clearhistory <player>     — Clear a specific player's AI conversation history
 *   clearhistory *            — Clear ALL players' histories
 *   debug                     — Toggle debug mode on/off at runtime
 *
 * Permission: mcaia.admin
 */
public class AIAdminCommand implements BasicCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MCAIAPlugin           plugin;
    private final AIConversationManager conversations;

    public AIAdminCommand(MCAIAPlugin plugin, AIConversationManager conversations) {
        this.plugin        = plugin;
        this.conversations = conversations;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();

        if (!sender.hasPermission("mcaia.admin")) {
            send(sender, "<red>You do not have permission to use /aiadmin.</red>");
            return;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                plugin.reloadPluginConfig();
                send(sender, "<green>✔ MCAIA configuration reloaded.</green>");
            }

            case "status" -> sendStatus(sender);

            case "clearhistory" -> {
                if (args.length < 2) {
                    send(sender, "<red>Usage: /aiadmin clearhistory <player|*></red>");
                    return;
                }
                if (args[1].equals("*")) {
                    // Clear everyone
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        conversations.clearHistory(p.getUniqueId());
                        conversations.clearPendingQuery(p.getUniqueId());
                    }
                    send(sender, "<green>✔ Cleared AI history for all online players.</green>");
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        send(sender, "<red>Player '" + args[1] + "' is not online.</red>");
                        return;
                    }
                    conversations.clearHistory(target.getUniqueId());
                    conversations.clearPendingQuery(target.getUniqueId());
                    send(sender, "<green>✔ Cleared AI history for " + target.getName() + ".</green>");
                }
            }

            case "debug" -> {
                boolean current = plugin.getConfig().getBoolean("logging.debug-mode", false);
                plugin.getConfig().set("logging.debug-mode", !current);
                send(sender, "<yellow>Debug mode: " + (!current ? "<green>ON</green>" : "<red>OFF</red>") + "</yellow>");
                send(sender, "<gray>Note: This change is not saved to disk. Use /aiadmin reload after editing config.yml to persist it.</gray>");
            }

            default -> {
                send(sender, "<red>Unknown subcommand. Use /aiadmin for help.</red>");
                sendHelp(sender);
            }
        }
    }

    private void sendStatus(CommandSender sender) {
        String model    = plugin.getConfig().getString("ai.model", "?");
        String apiKey   = plugin.getConfig().getString("ai.gemini-api-key", "");
        boolean keyOk   = apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_GEMINI_API_KEY_HERE");
        boolean tosOk   = plugin.getConfig().getBoolean("tos-accepted", false);
        boolean debug   = plugin.getConfig().getBoolean("logging.debug-mode", false);
        String permBack = plugin.getPermissionManager() != null
                ? plugin.getPermissionManager().getActiveBackend().name() : "UNKNOWN";
        String cmdName  = plugin.getConfig().getString("command-name", "ai");

        send(sender, "<gold><b>=== MCAIA Status ===</b></gold>");
        send(sender, "<gray>Version:     </gray><white>" + plugin.getPluginMeta().getVersion() + "</white>");
        send(sender, "<gray>TOS Accepted:</gray> " + (tosOk ? "<green>Yes</green>" : "<red>No — plugin disabled!</red>"));
        send(sender, "<gray>API Key:     </gray> " + (keyOk ? "<green>Configured</green>" : "<red>MISSING</red>"));
        send(sender, "<gray>Model:       </gray><white>" + model + "</white>");
        send(sender, "<gray>Command:     </gray><white>/" + cmdName + "</white>");
        send(sender, "<gray>Perm Backend:</gray><white>" + permBack + "</white>");
        send(sender, "<gray>Debug Mode:  </gray>" + (debug ? "<yellow>ON</yellow>" : "<green>OFF</green>"));
        send(sender, "<gray>Online:      </gray><white>" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers() + "</white>");

        // Integration status
        if (plugin.getPluginCompat() != null) {
            var c = plugin.getPluginCompat();
            send(sender, "<gray>Vault:       </gray>" + flag(c.hasVault));
            send(sender, "<gray>LuckPerms:   </gray>" + flag(c.hasLuckPerms));
            send(sender, "<gray>Geyser:      </gray>" + flag(c.hasGeyser));
            send(sender, "<gray>Floodgate:   </gray>" + flag(c.hasFloodgate));
        }
    }

    private void sendHelp(CommandSender sender) {
        send(sender, "<gold><b>=== /aiadmin Help ===</b></gold>");
        send(sender, "<yellow>/aiadmin reload</yellow> <gray>— Reload config.yml</gray>");
        send(sender, "<yellow>/aiadmin status</yellow> <gray>— Show plugin & integration status</gray>");
        send(sender, "<yellow>/aiadmin clearhistory <player|*></yellow> <gray>— Clear AI history</gray>");
        send(sender, "<yellow>/aiadmin debug</yellow> <gray>— Toggle debug mode</gray>");
    }

    private String flag(boolean v) {
        return v ? "<green>Enabled</green>" : "<red>Not installed</red>";
    }

    private void send(CommandSender sender, String msg) {
        String prefix = plugin.getConfig().getString("prefix",
                "<gradient:#00d2ff:#3a7bd5><b>[AI]</b></gradient> <gray>»</gray> ");
        sender.sendMessage(MM.deserialize(prefix + msg));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (!stack.getSender().hasPermission("mcaia.admin")) return List.of();
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>(List.of("reload", "status", "clearhistory", "debug"));
            String cur = args.length == 0 ? "" : args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(cur)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clearhistory")) {
            List<String> targets = new ArrayList<>();
            targets.add("*");
            Bukkit.getOnlinePlayers().forEach(p -> targets.add(p.getName()));
            return targets.stream().filter(t -> t.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("mcaia.admin");
    }

    @Override
    public String permission() {
        return "mcaia.admin";
    }
}
