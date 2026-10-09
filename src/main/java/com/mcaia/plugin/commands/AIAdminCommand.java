package com.mcaia.plugin.commands;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.mcaia.plugin.util.FoliaTasks;
import com.mcaia.plugin.util.SafeMiniMessage;

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

    private static final MiniMessage MM = SafeMiniMessage.INSTANCE;

    private final MCAIAPlugin           plugin;
    private final AIConversationManager conversations;

    public AIAdminCommand(MCAIAPlugin plugin, AIConversationManager conversations) {
        this.plugin        = plugin;
        this.conversations = conversations;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();

        boolean reloadRequested = args.length > 0 && args[0].equalsIgnoreCase("reload");
        String requiredPermission = reloadRequested ? "mcaia.reload" : "mcaia.admin";
        if (!sender.hasPermission(requiredPermission)) {
            send(sender, "<red>You do not have permission to use /aiadmin.</red>");
            return;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                FoliaTasks.global(plugin, () -> {
                    plugin.reloadPluginConfig();
                    send(sender, "<green>✔ MCAIA configuration reloaded.</green>");
                });
            }

            case "status" -> sendStatus(sender);

            case "clearhistory" -> {
                if (args.length < 2) {
                    send(sender, "<red>Usage: /aiadmin clearhistory <player|*></red>");
                    return;
                }
                if (args[1].equals("*")) {
                    conversations.clearAll();
                    send(sender, "<green>✔ Cleared all AI conversation histories.</green>");
                } else {
                    java.util.UUID target = plugin.getServerDataProvider().findOnlinePlayerUuid(args[1]);
                    if (target == null) {
                        send(sender, "<red>Player '" + args[1] + "' is not online.</red>");
                        return;
                    }
                    conversations.clearHistory(target);
                    conversations.clearPendingQuery(target);
                    send(sender, "<green>✔ Cleared AI history for " + args[1] + ".</green>");
                }
            }

            case "debug" -> {
                FoliaTasks.global(plugin, () -> {
                    boolean current = plugin.getConfig().getBoolean("logging.debug-mode", false);
                    plugin.getConfig().set("logging.debug-mode", !current);
                    plugin.getFileLogger().setDebugEnabled(!current);
                    send(sender, "<yellow>Debug mode: " + (!current ? "<green>ON</green>" : "<red>OFF</red>") + "</yellow>");
                    send(sender, "<gray>Note: This change is not saved to disk.</gray>");
                });
            }

            default -> {
                send(sender, "<red>Unknown subcommand. Use /aiadmin for help.</red>");
                sendHelp(sender);
            }
        }
    }

    private void sendStatus(CommandSender sender) {
        FoliaTasks.global(plugin, () -> sendStatusOnGlobal(sender));
    }

    private void sendStatusOnGlobal(CommandSender sender) {
        boolean tosOk   = plugin.getConfig().getBoolean("tos-accepted", false);
        boolean debug   = plugin.getConfig().getBoolean("logging.debug-mode", false);
        String permBack = plugin.getPermissionManager() != null
                ? plugin.getPermissionManager().getActiveBackend().name() : "UNKNOWN";
        String cmdName  = plugin.getConfig().getString("command-name", "ai");
        var llm = plugin.getLlmClient();
        String providers = llm == null || llm.getConfiguredProviders().isEmpty()
                ? "None" : String.join(", ", llm.getConfiguredProviders());
        String active = llm == null || llm.getActiveProvider().isBlank()
                ? "Not selected yet" : llm.getActiveProvider() + " / " + llm.getActiveModel();

        send(sender, "<gold><b>=== MCAIA Status ===</b></gold>");
        send(sender, "<gray>Version:     </gray><white>" + plugin.getPluginMeta().getVersion() + "</white>");
        send(sender, "<gray>TOS Accepted:</gray> " + (tosOk ? "<green>Yes</green>" : "<red>No — plugin disabled!</red>"));
        send(sender, "<gray>Providers:   </gray><white>" + providers + "</white>");
        send(sender, "<gray>Active model:</gray><white>" + active + "</white>");
        send(sender, "<gray>Operational: </gray>" + (plugin.isOperational() ? "<green>Yes</green>" : "<red>No</red>"));
        send(sender, "<gray>Command:     </gray><white>/" + cmdName + "</white>");
        send(sender, "<gray>Perm Backend:</gray><white>" + permBack + "</white>");
        send(sender, "<gray>Debug Mode:  </gray>" + (debug ? "<yellow>ON</yellow>" : "<green>OFF</green>"));
        send(sender, "<gray>Online:      </gray><white>" + plugin.getServerDataProvider().onlinePlayerCount()
                + "/" + Bukkit.getMaxPlayers() + "</white>");

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
        FoliaTasks.forSender(plugin, sender, () -> {
            try {
                sender.sendMessage(MM.deserialize(prefix + msg));
            } catch (Exception ignored) {
                sender.sendMessage(Component.text("[MCAIA] " + prefix + msg));
            }
        }, () -> {});
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        boolean canAdmin = sender.hasPermission("mcaia.admin");
        boolean canReload = sender.hasPermission("mcaia.reload");
        if (!canAdmin && !canReload) return List.of();
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>();
            if (canReload) subs.add("reload");
            if (canAdmin) subs.addAll(List.of("status", "clearhistory", "debug"));
            String cur = args.length == 0 ? "" : args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(cur)).toList();
        }
        if (canAdmin && args.length == 2 && args[0].equalsIgnoreCase("clearhistory")) {
            List<String> targets = new ArrayList<>();
            targets.add("*");
            targets.addAll(plugin.getServerDataProvider().onlinePlayerNames());
            return targets.stream().filter(t -> t.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("mcaia.admin") || sender.hasPermission("mcaia.reload");
    }

    @Override
    public String permission() {
        return null;
    }
}
