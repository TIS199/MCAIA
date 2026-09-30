package com.mcaia.plugin.ai;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import com.mcaia.plugin.permissions.PermissionManager;

import java.util.List;
import java.util.TreeSet;

final class PlayerCommandAccess {

    private static final int MAX_ALLOWED_COMMANDS = 200;

    private PlayerCommandAccess() {
    }

    static List<String> allowedCommands(Player player, PermissionManager permissions) {
        TreeSet<String> allowed = new TreeSet<>();
        for (var entry : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            Command command = entry.getValue();
            String permission = command.getPermission();
            boolean permitted = command.testPermissionSilent(player);
            if (!permitted && permission != null && !permission.isBlank()) {
                permitted = permissions.hasPermission(player, permission);
            }
            if (permitted) {
                allowed.add(entry.getKey().toLowerCase());
                if (allowed.size() == MAX_ALLOWED_COMMANDS) break;
            }
        }
        return List.copyOf(allowed);
    }
}
