package com.mcaia.plugin.permissions;

import com.mcaia.plugin.compat.PluginCompat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * Unified permission check: LuckPerms native API → Vault → OP + config list fallback.
 *
 * We use Bukkit's standard hasPermission() which LuckPerms and Vault both inject into,
 * so we don't need direct LuckPerms API calls — Bukkit's permission system covers it.
 * The Vault / LuckPerms detection is used only for logging which system is active.
 */
public class PermissionManager {

    public enum Backend { LUCKPERMS, VAULT, FALLBACK }

    private final JavaPlugin plugin;
    private final PluginCompat compat;
    private final Logger log;

    private Backend activeBackend = Backend.FALLBACK;
    private Permission vaultPerms = null;

    // Config fallback options
    private boolean fallbackRequireOp   = true;
    private List<String> permittedPlayers = List.of();

    public PermissionManager(JavaPlugin plugin, PluginCompat compat) {
        this.plugin = plugin;
        this.compat = compat;
        this.log    = plugin.getLogger();
    }

    public void initialize() {
        loadConfig();

        if (compat.hasLuckPerms) {
            // LuckPerms injects itself into Bukkit's permission system automatically.
            // hasPermission() on a Player will call LuckPerms under the hood.
            activeBackend = Backend.LUCKPERMS;
            log.info("[MCAIA] Permission backend: LuckPerms");
        } else if (compat.hasVault) {
            RegisteredServiceProvider<Permission> rsp =
                    plugin.getServer().getServicesManager().getRegistration(Permission.class);
            if (rsp != null) {
                vaultPerms    = rsp.getProvider();
                activeBackend = Backend.VAULT;
                log.info("[MCAIA] Permission backend: Vault (" + vaultPerms.getName() + ")");
            } else {
                log.warning("[MCAIA] Vault found but no permission provider registered — using fallback.");
            }
        } else {
            log.info("[MCAIA] Permission backend: Fallback (OP check + permitted-players list)");
        }
    }

    private void loadConfig() {
        fallbackRequireOp = plugin.getConfig().getBoolean("permissions.fallback-require-op", true);
        permittedPlayers  = plugin.getConfig().getStringList("permissions.permitted-players");
    }

    /**
     * Returns true if the player has the given permission node,
     * using whichever backend is active.
     */
    public boolean hasPermission(Player player, String permNode) {
        return switch (activeBackend) {
            case LUCKPERMS, VAULT -> player.hasPermission(permNode);
            case FALLBACK         -> fallbackCheck(player, permNode);
        };
    }

    private boolean fallbackCheck(Player player, String permNode) {
        // mcaia.bypass-rate-limit and mcaia.admin always require OP in fallback
        if (player.isOp()) return true;
        // For basic use permission, also check the permitted-players list
        if ("mcaia.use".equals(permNode) || "mcaia.history".equals(permNode)) {
            if (!fallbackRequireOp) return true;
            String name = player.getName();
            // Strip Floodgate prefix if present
            if (name.startsWith(".")) name = name.substring(1);
            return permittedPlayers.contains(name);
        }
        return false;
    }

    public Backend getActiveBackend() { return activeBackend; }

    /** Reload config values (called by /aiadmin reload). */
    public void reload() { loadConfig(); }
}
