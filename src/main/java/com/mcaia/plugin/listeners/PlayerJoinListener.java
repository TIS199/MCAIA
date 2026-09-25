package com.mcaia.plugin.listeners;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

    public PlayerJoinListener(MCAIAPlugin plugin, AIConversationManager conversations) {
        this.plugin        = plugin;
        this.conversations = conversations;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Clear any stale conversation state from a previous session
        conversations.clearHistory(player.getUniqueId());
        conversations.clearPendingQuery(player.getUniqueId());

        // Send welcome message if enabled
        if (!plugin.getConfig().getBoolean("welcome.enabled", true)) return;

        String raw  = plugin.getConfig().getString("welcome.message",
                "<yellow>Welcome, <white><player></white>!</yellow>");
        String cmd  = plugin.getConfig().getString("command-name", "ai");

        String msg  = raw
                .replace("<player>", player.getName())
                .replace("<cmd>",    cmd);

        player.sendMessage(MM.deserialize(msg));
    }
}
