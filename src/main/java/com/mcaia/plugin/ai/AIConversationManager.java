package com.mcaia.plugin.ai;

import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-player conversation history for the Gemini API, and manages
 * pending player queries (when the AI asks the player a follow-up question).
 *
 * History is stored as a list of role/parts pairs compatible with the
 * Gemini generateContent API (role = "user" | "model").
 */
public class AIConversationManager {

    /** Represents a single turn in the conversation (user or model). */
    public record Turn(String role, String text) {}

    /** Represents a pending question the AI asked the player. */
    public record PendingQuery(String question, long timestampMs) {}

    private final int maxHistoryLength;

    // Per-player conversation history: UUID → deque of turns
    private final Map<UUID, ArrayDeque<Turn>> histories = new ConcurrentHashMap<>();

    // Players currently waiting to answer an AI question
    private final Map<UUID, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    // Last activity timestamp per player, for auto-expiry
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    private static final long EXPIRY_MS = 10L * 60 * 1000; // 10 minutes

    public AIConversationManager(int maxHistoryLength) {
        this.maxHistoryLength = maxHistoryLength;
    }

    // ── History management ────────────────────────────────────────────────────

    /** Add a user message to the player's history. */
    public void addUserTurn(UUID uuid, String text) {
        addTurn(uuid, new Turn("user", text));
    }

    /** Add an AI (model) message to the player's history. */
    public void addModelTurn(UUID uuid, String text) {
        addTurn(uuid, new Turn("model", text));
    }

    private void addTurn(UUID uuid, Turn turn) {
        ArrayDeque<Turn> history = histories.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        history.addLast(turn);
        // Trim to maxHistoryLength pairs (each pair = 1 user + 1 model turn)
        while (history.size() > maxHistoryLength * 2) {
            history.pollFirst();
        }
        lastActivity.put(uuid, System.currentTimeMillis());
    }

    /** Get conversation history as an ordered list (oldest first). */
    public List<Turn> getHistory(UUID uuid) {
        ArrayDeque<Turn> history = histories.get(uuid);
        if (history == null) return List.of();
        return List.copyOf(history);
    }

    /** Clear a player's conversation history. */
    public void clearHistory(UUID uuid) {
        histories.remove(uuid);
        lastActivity.remove(uuid);
    }

    /** Expire conversations that have been idle for more than EXPIRY_MS. */
    public void expireStale() {
        long now = System.currentTimeMillis();
        lastActivity.entrySet().removeIf(e -> {
            if (now - e.getValue() > EXPIRY_MS) {
                histories.remove(e.getKey());
                pendingQueries.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    // ── Pending query management ──────────────────────────────────────────────

    /** Register that the AI is waiting for the player to answer a question. */
    public void setPendingQuery(UUID uuid, String question) {
        pendingQueries.put(uuid, new PendingQuery(question, System.currentTimeMillis()));
    }

    /** Returns the pending query for the player, or null if none. */
    public PendingQuery getPendingQuery(UUID uuid) {
        return pendingQueries.get(uuid);
    }

    /** Clear the pending query (called after player replies or timeout). */
    public void clearPendingQuery(UUID uuid) {
        pendingQueries.remove(uuid);
    }

    /** Returns true if the player has an active pending query. */
    public boolean hasPendingQuery(UUID uuid) {
        PendingQuery pq = pendingQueries.get(uuid);
        if (pq == null) return false;
        // Auto-expire if too old
        if (System.currentTimeMillis() - pq.timestampMs() > EXPIRY_MS) {
            pendingQueries.remove(uuid);
            return false;
        }
        return true;
    }
}
