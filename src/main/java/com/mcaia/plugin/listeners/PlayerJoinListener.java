package com.mcaia.plugin.listeners;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import com.mcaia.plugin.ai.ResponseRouter;
import com.mcaia.plugin.ai.ServerDataProvider;
import com.mcaia.plugin.util.UpdateChecker;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

/**
 * Handles player join events:
 *  - Displays the welcome message (configurable)
 *  - Clears stale AI conversation history for the joining player
 */
public class PlayerJoinListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MCAIAPlugin           plugin;
    private final AIConversationManager conversations;
    private final UpdateChecker         updateChecker;
    private final ServerDataProvider    serverDataProvider;
    private final ResponseRouter        responseRouter;

    public PlayerJoinListener(MCAIAPlugin plugin, AIConversationManager conversations,
                              UpdateChecker updateChecker, ServerDataProvider serverDataProvider,
                              ResponseRouter responseRouter) {
        this.plugin        = plugin;
        this.conversations = conversations;
        this.updateChecker = updateChecker;
        this.serverDataProvider = serverDataProvider;
        this.responseRouter = responseRouter;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        serverDataProvider.playerJoined(player);

        // Clear any stale conversation state from a previous session
        conversations.clearHistory(player.getUniqueId());
        conversations.clearPendingQuery(player.getUniqueId());
        updateChecker.notifyIfAvailable(player);

        // Send welcome message if enabled
        if (!plugin.getConfig().getBoolean("welcome.enabled", true)) return;

        String raw  = plugin.getConfig().getString("welcome.message",
                "<yellow>Welcome, <white><player></white>!</yellow>");
        String cmd  = plugin.getConfig().getString("command-name", "ai");

        player.sendMessage(MM.deserialize(raw,
                Placeholder.unparsed("player", plugin.getPluginCompat().getCleanName(player)),
                Placeholder.unparsed("cmd", cmd)));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        serverDataProvider.playerQuit(player);
        responseRouter.handlePlayerQuit(player.getUniqueId());
    }
}
