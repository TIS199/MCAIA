package com.mcaia.plugin.listeners;

import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.ai.AIConversationManager;
import com.mcaia.plugin.ai.ResponseRouter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;

/**
 * Intercepts player chat messages to capture replies to pending AI questions.
 *
 * When the AI uses type "query_player", the player's NEXT chat message is
 * treated as their answer rather than a public chat message.
 */
public class PlayerChatListener implements Listener {

    private final MCAIAPlugin           plugin;
    private final AIConversationManager conversations;
    private final ResponseRouter        router;

    public PlayerChatListener(MCAIAPlugin plugin,
                              AIConversationManager conversations,
                              ResponseRouter router) {
        this.plugin        = plugin;
        this.conversations = conversations;
        this.router        = router;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        if (!conversations.hasPendingQuery(player.getUniqueId())) return;

        // Cancel public broadcast — this message is private (AI reply)
        event.setCancelled(true);

        // Extract plain text from the adventure Component
        String reply = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Resume the AI interaction on the main thread (ResponseRouter needs it)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Show the player their own reply privately with a prefix
            String prefix = plugin.getConfig().getString("prefix",
                    "<gradient:#00d2ff:#3a7bd5><b>[AI]</b></gradient> <gray>»</gray> ");
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(prefix + "<gray><i>You replied: " + reply + "</i></gray>"));

            router.handlePlayerReply(player, reply);
        });
    }
}
