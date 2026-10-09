package com.mcaia.plugin.ai;

import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores per-player conversation history for the configured LLM provider, and manages
 * pending player queries (when the AI asks the player a follow-up question).
 *
 * History is stored as a list of role/parts pairs compatible with the
 * Gemini generateContent API (role = "user" | "model"); provider adapters map
 * these roles to their own conversation formats.
 */
public class AIConversationManager {

    /** Represents a single turn in the conversation (user or model). */
    public record Turn(String role, String text) {}

    /** Represents a pending question the AI asked the player. */
    public record PendingQuery(String question, long timestampMs) {}

    private final int maxHistoryLength;

    private static final class Conversation {
        private final ArrayDeque<Turn> turns = new ArrayDeque<>();
        private long lastActivityMs = System.currentTimeMillis();
    }

    // All access to each Conversation is made inside ConcurrentHashMap.compute*.
    private final Map<UUID, Conversation> histories = new ConcurrentHashMap<>();

    // Players currently waiting to answer an AI question
    private final Map<UUID, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    private static final long EXPIRY_MS = 10L * 60 * 1000; // Conversation history: 10 minutes.
    private volatile long queryTimeoutMs = 60_000L;

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
        long now = System.currentTimeMillis();
        histories.compute(uuid, (key, conversation) -> {
            Conversation current = conversation == null ? new Conversation() : conversation;
            current.turns.addLast(turn);
            trim(current.turns);
            current.lastActivityMs = now;
            return current;
        });
    }

    private void trim(ArrayDeque<Turn> turns) {
        int maxTurns = Math.max(2, maxHistoryLength * 2);
        while (turns.size() > maxTurns) {
            turns.pollFirst();
            // Keep the history valid for providers that require a user first.
            if (!turns.isEmpty() && "model".equals(turns.peekFirst().role())) {
                turns.pollFirst();
            }
        }
    }

    /** Get conversation history as an ordered list (oldest first). */
    public List<Turn> getHistory(UUID uuid) {
        AtomicReference<List<Turn>> snapshot = new AtomicReference<>(List.of());
        histories.computeIfPresent(uuid, (key, conversation) -> {
            snapshot.set(List.copyOf(conversation.turns));
            return conversation;
        });
        return snapshot.get();
    }

    /** Clear a player's conversation history. */
    public void clearHistory(UUID uuid) {
        histories.remove(uuid);
    }

    public void clearAll() {
        histories.clear();
        pendingQueries.clear();
    }

    public int getHistoryPlayerCount() {
        return histories.size();
    }

    public void setQueryTimeoutSeconds(long seconds) {
        queryTimeoutMs = Math.max(1L, seconds) * 1000L;
    }

    /** Expire conversations that have been idle for more than EXPIRY_MS. */
    public void expireStale() {
        long now = System.currentTimeMillis();
        histories.forEach((uuid, conversation) -> histories.computeIfPresent(uuid, (key, current) -> {
            if (now - current.lastActivityMs > EXPIRY_MS) {
                pendingQueries.remove(key);
                return null;
            }
            return current;
        }));
    }

    // ── Pending query management ──────────────────────────────────────────────

    /** Register that the AI is waiting for the player to answer a question. */
    public PendingQuery setPendingQuery(UUID uuid, String question) {
        PendingQuery pending = new PendingQuery(question, System.currentTimeMillis());
        pendingQueries.put(uuid, pending);
        return pending;
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
        if (System.currentTimeMillis() - pq.timestampMs() > queryTimeoutMs) {
            pendingQueries.remove(uuid);
            return false;
        }
        return true;
    }

    public boolean expirePendingQuery(UUID uuid, long expectedTimestamp) {
        PendingQuery current = pendingQueries.get(uuid);
        return current != null && current.timestampMs() == expectedTimestamp
                && pendingQueries.remove(uuid, current);
    }
}
