package com.mcaia.plugin;

import com.mcaia.plugin.ai.AIConversationManager;
import com.mcaia.plugin.ai.LlmClient;
import com.mcaia.plugin.ai.ResponseRouter;
import com.mcaia.plugin.ai.ServerDataProvider;
import com.mcaia.plugin.commands.AIAdminCommand;
import com.mcaia.plugin.commands.AICommand;
import com.mcaia.plugin.compat.PluginCompat;
import com.mcaia.plugin.listeners.PlayerChatListener;
import com.mcaia.plugin.listeners.PlayerJoinListener;
import com.mcaia.plugin.logging.FileLogger;
import com.mcaia.plugin.logging.WebhookLogger;
import com.mcaia.plugin.permissions.PermissionManager;
import com.mcaia.plugin.util.RateLimiter;
import com.mcaia.plugin.util.FoliaTasks;
import com.mcaia.plugin.util.UpdateChecker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MCAIA — Minecraft AI Admin Plugin
 *
 * Main plugin class. Initializes all components, registers commands and listeners,
 * enforces the TOS gate, and tears down cleanly on disable.
 *
 * Component initialization order:
 *   1. Config + TOS check
 *   2. PluginCompat (detect soft-deps)
 *   3. FileLogger, WebhookLogger
 *   4. PermissionManager, RateLimiter
 *   5. AIConversationManager, ServerDataProvider, LlmClient, ResponseRouter
 *   6. Register commands (/ai + /aiadmin) via LifecycleEvents
 *   7. Register event listeners
 *
 * Author: TIS199 | https://github.com/TIS199
 */
public final class MCAIAPlugin extends JavaPlugin {

    // ── Component references (accessible from commands/listeners) ─────────────
    private PluginCompat          pluginCompat;
    private FileLogger            fileLogger;
    private WebhookLogger         webhookLogger;
    private PermissionManager     permissionManager;
    private RateLimiter           rateLimiter;
    private AIConversationManager conversationManager;
    private LlmClient             llmClient;
    private ResponseRouter        responseRouter;
    private UpdateChecker         updateChecker;
    private ServerDataProvider    serverDataProvider;

    // ── Configs ───────────────────────────────────────────────────────────────
    private FileConfiguration modelsConfig;
    private FileConfiguration bannedCommandsConfig;

    // ── Whether the plugin is fully operational ───────────────────────────────
    private boolean operational = false;

    // ─────────────────────────────────────────────────────────────────────────

    private void loadCustomConfigs() {
        saveDefaultConfig(); // config.yml

        File modelsFile = new File(getDataFolder(), "models.yml");
        if (!modelsFile.exists()) saveResource("models.yml", false);
        modelsConfig = YamlConfiguration.loadConfiguration(modelsFile);
        try (var defaultsStream = getResource("models.yml")) {
            if (defaultsStream != null) {
                modelsConfig.addDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException e) {
            getLogger().severe("[MCAIA] Could not load bundled model defaults: " + e.getMessage());
        }

        File bannedFile = new File(getDataFolder(), "banned-commands.yml");
        if (!bannedFile.exists()) saveResource("banned-commands.yml", false);
        bannedCommandsConfig = YamlConfiguration.loadConfiguration(bannedFile);
    }

    @Override
    public void onEnable() {
        new Metrics(this, 34296);

        // 1. Save and load config
        loadCustomConfigs();

        // 2. TOS gate — plugin refuses to work without explicit acceptance
        if (!getConfig().getBoolean("tos-accepted", false)) {
            getLogger().severe("=======================================================");
            getLogger().severe("  MCAIA is DISABLED — Terms of Service not accepted!   ");
            getLogger().severe("  Open plugins/MCAIA/config.yml and set:               ");
            getLogger().severe("    tos-accepted: true                                 ");
            getLogger().severe("  Then restart your server.                            ");
            getLogger().severe("=======================================================");
            // Don't disable the plugin entirely (so /aiadmin status still works),
            // but mark it non-operational so /ai commands are blocked.
            operational = false;
        }

        // 3. Detect optional plugin integrations
        pluginCompat = new PluginCompat(this);
        pluginCompat.detect();

        // 4. Logging
        fileLogger   = new FileLogger(this);
        fileLogger.initialize();

        webhookLogger = new WebhookLogger(this);
        webhookLogger.initialize();

        // 5. Permissions + Rate limiting
        permissionManager = new PermissionManager(this, pluginCompat);
        permissionManager.initialize();

        int cooldown = getConfig().getInt("rate-limit.cooldown-seconds", 15);
        rateLimiter  = new RateLimiter(cooldown);

        // 6. AI components
        int maxHistory = getConfig().getInt("ai.max-history-length", 10);
        conversationManager = new AIConversationManager(maxHistory);
        conversationManager.setQueryTimeoutSeconds(getConfig().getLong("ai.query-timeout-seconds", 60));

        llmClient = new LlmClient(this, fileLogger);
        llmClient.initialize();
        if (getConfig().getBoolean("tos-accepted", false) && !llmClient.hasConfiguredProvider()) {
            getLogger().severe("[MCAIA] No usable AI provider configured. Set up a hosted provider key or an Ollama model.");
        }
        operational = getConfig().getBoolean("tos-accepted", false) && llmClient.hasConfiguredProvider();

        serverDataProvider = new ServerDataProvider(this);

        responseRouter = new ResponseRouter(
                this, llmClient, conversationManager, serverDataProvider, fileLogger, webhookLogger);

        // 7. Register commands via Paper's LifecycleEvents API
        String cmdName = getConfig().getString("command-name", "ai");
        if (cmdName == null || cmdName.isBlank()) cmdName = "ai";

        final String finalCmdName = cmdName;

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            AICommand      aiCmd    = new AICommand(this, responseRouter, conversationManager,
                                                    permissionManager, rateLimiter, pluginCompat);
            AIAdminCommand adminCmd = new AIAdminCommand(this, conversationManager);

            // Register /ai (or custom name) with "aiadmin" as a separate command
            event.registrar().register(
                    finalCmdName,
                    "AI-powered Minecraft server assistant",
                    List.of(),
                    aiCmd
            );

            event.registrar().register(
                    "aiadmin",
                    "MCAIA admin command",
                    List.of("mcaiadmin"),
                    adminCmd
            );
        });

        // 8. Register event listeners
        updateChecker = new UpdateChecker(this, serverDataProvider);
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, conversationManager, updateChecker, serverDataProvider, responseRouter), this);
        getServer().getPluginManager().registerEvents(
                new PlayerChatListener(this, conversationManager, responseRouter), this);

        // 9. Schedule periodic stale conversation expiry (every 5 minutes)
        FoliaTasks.asyncRepeating(this, conversationManager::expireStale, 5, 5, java.util.concurrent.TimeUnit.MINUTES);

        updateChecker.start();

        // Done
        if (operational) {
            getLogger().info("MCAIA enabled. AI command: /" + finalCmdName);
            fileLogger.info("Plugin enabled. AI command: /" + finalCmdName +
                    " | Providers: " + String.join(", ", llmClient.getConfiguredProviders()));
        } else {
            getLogger().warning("MCAIA loaded but is non-operational (accept the TOS and configure at least one AI provider).");
        }
    }

    @Override
    public void onDisable() {
        if (webhookLogger != null) webhookLogger.shutdown();
        if (fileLogger != null) fileLogger.shutdown();
        getLogger().info("MCAIA disabled.");
    }

    // ── Config reload (called by /aiadmin reload) ─────────────────────────────

    public void reloadPluginConfig() {
        reloadConfig();
        loadCustomConfigs();

        // Propagate new config values to all components
        if (fileLogger    != null) fileLogger.initialize();
        if (webhookLogger != null) webhookLogger.reload();
        if (permissionManager != null) permissionManager.reload();
        if (llmClient != null) llmClient.reload();
        if (rateLimiter != null) rateLimiter.setCooldownSeconds(getConfig().getInt("rate-limit.cooldown-seconds", 15));
        if (conversationManager != null) {
            conversationManager.setQueryTimeoutSeconds(getConfig().getLong("ai.query-timeout-seconds", 60));
        }

        operational = getConfig().getBoolean("tos-accepted", false)
                && llmClient != null && llmClient.hasConfiguredProvider();
        if (getConfig().getBoolean("tos-accepted", false) && llmClient != null
                && !llmClient.hasConfiguredProvider()) {
            getLogger().severe("[MCAIA] No usable AI provider configured. Set up a hosted provider key or an Ollama model.");
        }
        getLogger().info("Configuration reloaded. Operational: " + operational);
    }

    // ── Getters (for commands/listeners that need cross-component access) ─────

    public PluginCompat      getPluginCompat()      { return pluginCompat;       }
    public FileLogger        getFileLogger()        { return fileLogger;         }
    public WebhookLogger     getWebhookLogger()     { return webhookLogger;      }
    public PermissionManager getPermissionManager() { return permissionManager;  }
    public RateLimiter       getRateLimiter()       { return rateLimiter;        }
    public LlmClient         getLlmClient()         { return llmClient;          }
    public ServerDataProvider getServerDataProvider() { return serverDataProvider; }
    public ResponseRouter    getResponseRouter()    { return responseRouter;     }
    public boolean           isOperational()        { return operational;        }

    public FileConfiguration getModelsConfig()      { return modelsConfig;       }
    public FileConfiguration getBannedCommandsConfig() { return bannedCommandsConfig; }

    // ── Copyright ─────────────────────────────────────────────────────────────
    // Copyright (c) 2026 TIS199
    // Licensed under GNU GPL version 3 only (GPL-3.0-only); see LICENSE.
    // GitHub: https://github.com/TIS199
}
