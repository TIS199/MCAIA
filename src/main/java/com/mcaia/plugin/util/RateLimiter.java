package com.mcaia.plugin.util;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cooldown manager for the /ai command.
 */
public class RateLimiter {

    private final Map<UUID, Long> lastUsed = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public RateLimiter(int cooldownSeconds) {
        this.cooldownMs = cooldownSeconds * 1000L;
    }

    /**
     * Returns -1 if the player is allowed (not rate-limited),
     * or the remaining cooldown seconds if they must wait.
     */
    public long getRemainingCooldown(Player player) {
        if (cooldownMs <= 0) return -1;
        Long last = lastUsed.get(player.getUniqueId());
        if (last == null) return -1;
        long elapsed  = System.currentTimeMillis() - last;
        long remaining = cooldownMs - elapsed;
        return remaining > 0 ? (long) Math.ceil(remaining / 1000.0) : -1;
    }

    /** Record that the player just used the command. */
    public void recordUse(Player player) {
        lastUsed.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Remove a player's cooldown entry (e.g., on disconnect). */
    public void clearPlayer(UUID uuid) {
        lastUsed.remove(uuid);
    }
}
