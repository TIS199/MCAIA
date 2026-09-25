package com.mcaia.plugin.compat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Detects optional plugin integrations at startup and provides unified helpers.
 * All soft-dependency checks use Bukkit's PluginManager to avoid ClassNotFound errors.
 */
public class PluginCompat {

    private final JavaPlugin plugin;
    private final Logger log;

    public boolean hasVault      = false;
    public boolean hasLuckPerms  = false;
    public boolean hasGeyser     = false;
    public boolean hasFloodgate  = false;
    public boolean hasEssentials = false;
    public boolean hasViaVersion = false;

    public PluginCompat(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    /** Run once during onEnable to detect which optional plugins are loaded. */
    public void detect() {
        hasVault      = isPluginLoaded("Vault");
        hasLuckPerms  = isPluginLoaded("LuckPerms");
        hasGeyser     = isPluginLoaded("Geyser-Spigot") || isPluginLoaded("Geyser-Paper");
        hasFloodgate  = isPluginLoaded("floodgate");
        hasEssentials = isPluginLoaded("Essentials");
        hasViaVersion = isPluginLoaded("ViaVersion");

        log.info("Plugin integrations:");
        log.info("  Vault       : " + (hasVault      ? "FOUND"     : "not found (fallback active)"));
        log.info("  LuckPerms   : " + (hasLuckPerms  ? "FOUND"     : "not found"));
        log.info("  Geyser      : " + (hasGeyser     ? "FOUND"     : "not found"));
        log.info("  Floodgate   : " + (hasFloodgate  ? "FOUND"     : "not found"));
        log.info("  EssentialsX : " + (hasEssentials ? "FOUND"     : "not found"));
        log.info("  ViaVersion  : " + (hasViaVersion ? "FOUND"     : "not found"));
    }

    /**
     * Returns true if the player is a Bedrock player connected via Floodgate.
     * Safe to call even if Floodgate is not installed.
     */
    public boolean isBedrockPlayer(Player player) {
        if (!hasFloodgate) return false;
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance()
                    .isFloodgatePlayer(player.getUniqueId());
        } catch (Throwable t) {
            log.warning("[MCAIA] Floodgate check failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Returns the player's display name, optionally stripping the Floodgate '.' prefix
     * from Bedrock player names.
     */
    public String getCleanName(Player player, boolean stripBedrockPrefix) {
        String name = player.getName();
        if (stripBedrockPrefix && name.startsWith(".")) {
            return name.substring(1);
        }
        return name;
    }

    private boolean isPluginLoaded(String name) {
        Plugin p = plugin.getServer().getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }
}
